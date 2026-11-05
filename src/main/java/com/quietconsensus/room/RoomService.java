package com.quietconsensus.room;

import com.quietconsensus.engine.Conditions;
import com.quietconsensus.engine.Engine;
import com.quietconsensus.engine.Foods;
import com.quietconsensus.engine.MockRestaurants;
import com.quietconsensus.engine.Restaurant;
import com.quietconsensus.mediator.Blockers;
import com.quietconsensus.mediator.MediatorAi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 방 관리. 핵심 규칙:
 * - 방마다 lock 하나로 한 줄 서기 (동시에 눌러도 한 번에 하나씩 처리)
 * - 조율은 실시간: 누르는 즉시 반영하고 다시 계산한다. 단, 카드는 사람마다 따로 넘어간다
 *   → 내 폰 카드는 내가 누를 때만 바뀐다. 남이 빨리 눌러도 내가 고민 중인 카드가 사라지지 않는다
 * - 공용 화면은 "한 장"이 아니라 후보 블록 보드. 2초씩 묶어서 보내서, 누른 순간과 화면 변화가 바로 이어지지 않는다(익명)
 * - 끝: 전원이 같은 곳에 👍 / 모두가 모든 후보에 반응 / 시간 종료. 시간이 끝났는데 유력 후보에 의견을 안 낸
 *   사람이 있으면 한 번 연장하고, 그 사람 폰에 그 후보를 띄운다 (고민하다 결정을 놓치지 않게)
 * - 결정 규칙: "가장 덜 만족한 사람도 하한 이상"인 후보 중 👍 최다, 없으면 maximin (Engine.decide)
 * - AI 중재자: 후보가 적거나 막혔을 때, 코드가 "풀면 후보가 가장 많이 늘어나는 조건 하나"를 찾아
 *   그 사람에게만 묻는다. AI는 말투만 쓰고, 숫자와 결정은 코드가 한다. AI 호출은 락 밖에서 한다
 * - 연결이 끊겨도 바로 빼지 않는다: 유예 시간 안에 돌아오면 같은 사람이고, 안 돌아오면
 *   제출 전인 사람은 방에서 빼고, 제출한 사람은 조건(알레르기 포함)을 남긴 채 기다리는 대상에서만 뺀다
 */
@Service
public class RoomService {

    public static final int MIN_PEOPLE = 3;
    public static final int MAX_PEOPLE = 8;
    private static final long FRESH_MS = 6000;

    /**
     * negotiateMs: 조율 제한시간(0이면 타이머 없음) · extendMs: 고민 중인 사람이 있을 때 한 번 연장하는 시간
     * boardMs: 공용 보드를 묶어 보내는 간격(0이면 바로) · floor: 결정 규칙의 최저 만족도 하한
     * mediatorBelow: 후보가 이보다 적으면 중재를 시작 · mediatorTimeoutMs: AI 응답을 기다리는 최대 시간
     */
    public record Settings(long negotiateMs, long extendMs, long boardMs, long awayGraceMs,
                           double floor, int mediatorBelow, long mediatorTimeoutMs) {
        static Settings forTests() {
            return new Settings(0, 15000, 0, 30000, 0.5, 4, 3000);
        }
    }

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Publisher publisher;
    private final MediatorAi ai;
    private final Settings cfg;
    private final ScheduledExecutorService scheduler;

    /** 테스트용: 타이머 없이 tick / expire를 직접 부른다 */
    RoomService(Publisher publisher, MediatorAi ai, Settings cfg, boolean timers) {
        this.publisher = publisher;
        this.ai = ai;
        this.cfg = cfg;
        if (timers) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "room-timer");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(() -> tick(System.currentTimeMillis()), 250, 250, TimeUnit.MILLISECONDS);
        } else {
            scheduler = null;
        }
    }

    @Autowired
    public RoomService(Publisher publisher, MediatorAi ai,
                       @Value("${qc.negotiate-ms:120000}") long negotiateMs,
                       @Value("${qc.extend-ms:15000}") long extendMs,
                       @Value("${qc.board-ms:2000}") long boardMs,
                       @Value("${qc.away-grace-ms:30000}") long awayGraceMs,
                       @Value("${qc.fair-floor:0.5}") double floor,
                       @Value("${qc.mediator-below:4}") int mediatorBelow,
                       @Value("${qc.mediator-timeout-ms:8000}") long mediatorTimeoutMs) {
        this(publisher, ai, new Settings(negotiateMs, extendMs, boardMs, awayGraceMs, floor, mediatorBelow, mediatorTimeoutMs), true);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    // ---------------------------------------------------------------- 입장 / 입력 단계

    /** 테스트용: 연결 하나뿐인 사람 */
    public void join(String roomId, String key) {
        join(roomId, key, key);
    }

    public void join(String roomId, String key, String sessionId) {
        Room room = rooms.computeIfAbsent(roomId, Room::new);
        withLock(room, () -> {
            Room.Person known = room.people.get(key);
            if (known != null) {
                // 새로고침·재연결: 같은 사람이다. 지금 상태를 다시 보내 화면을 복구한다
                known.sessions.add(sessionId);
                known.disconnectedAt = 0;
                known.away = false;
                room.version++;
                publishAll(room);
                return;
            }
            if (room.people.size() >= MAX_PEOPLE) {
                publisher.toPerson(key, privateNotice(room, "방이 꽉 찼어요 (최대 " + MAX_PEOPLE + "명)"));
                return;
            }
            Room.Person p = new Room.Person(key);
            p.sessions.add(sessionId);
            room.people.put(key, p);
            if (room.phase == Views.Phase.NEGOTIATE && room.result != null) {
                assignCard(room, p);
                Engine.Decision d = decide(room);
                if (d.pick() != null) {
                    // 넘겨받기: 늦게 온 사람에게 지금 상황만 요약해 준다
                    p.notice = "지금 유력 후보는 " + d.pick().restaurant().name()
                            + " (가장 덜 만족한 사람도 " + percent(d.pick().min()) + "%). 조건을 내면 바로 같이 계산돼요";
                }
            }
            room.version++;
            publishAll(room);
        });
    }

    /** 연결 하나가 끊겼다. 여기서는 시각만 적어 두고, 정리는 유예 시간이 지난 뒤 sweepAway가 한다 */
    public void disconnected(String key, String sessionId) {
        for (Room room : rooms.values()) {
            withLock(room, () -> {
                Room.Person p = room.people.get(key);
                if (p != null && p.sessions.remove(sessionId) && p.sessions.isEmpty()) {
                    p.disconnectedAt = System.currentTimeMillis();
                }
            });
        }
    }

    /** 유예 시간이 지나도 돌아오지 않은 사람을 정리하고, 그 사람을 기다리던 단계를 다시 확인한다 */
    void sweepAway(long now) {
        for (Room room : rooms.values()) {
            withLock(room, () -> {
                if (room.phase == Views.Phase.DONE) return;
                boolean changed = false, removed = false;
                for (Iterator<Room.Person> it = room.people.values().iterator(); it.hasNext(); ) {
                    Room.Person p = it.next();
                    if (p.disconnectedAt == 0 || p.away || now - p.disconnectedAt < cfg.awayGraceMs()) continue;
                    changed = true;
                    if (room.question != null && room.question.key.equals(p.key)) room.question = null; // 대답할 사람이 없다
                    if (p.submitted) {
                        p.away = true; // 폰이 꺼졌다고 알레르기 조건이 사라지면 안 된다
                    } else {
                        it.remove();
                        room.dislikes.remove(p.key);
                        removed = true;
                    }
                }
                if (!changed) return;
                room.version++;
                if (room.phase == Views.Phase.INPUT) {
                    if (everyoneSubmitted(room)) startLocked(room);
                    else publishAll(room);
                    return;
                }
                if (removed) recompute(room);
                if (!checkEnd(room)) maybeMediate(room);
                publishAll(room);
            });
        }
    }

    public void submit(String roomId, String key, Conditions conditions) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        withLock(room, () -> {
            Room.Person p = room.people.get(key);
            if (p == null || room.phase == Views.Phase.DONE) return;
            p.conditions = conditions;
            p.submitted = true;
            room.version++;
            if (room.phase == Views.Phase.NEGOTIATE) {
                // 조율 중에 늦게 낸 조건도 바로 계산에 넣는다 (누가 냈는지는 남기지 않는다)
                recompute(room);
                room.log("새 조건이 반영됐어요");
                if (!checkEnd(room)) maybeMediate(room);
                publishAll(room);
                return;
            }
            if (everyoneSubmitted(room)) {
                startLocked(room); // 전원 제출 → 그때 처음으로 후보 공개
            } else {
                publishAll(room); // 입력 단계에는 "몇 명 제출"만 나간다
            }
        });
    }

    /** 진행자가 시작을 누른 경우 */
    public void start(String roomId) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        withLock(room, () -> {
            long submitted = room.people.values().stream().filter(p -> p.submitted).count();
            if (room.phase != Views.Phase.INPUT || submitted < MIN_PEOPLE) return;
            room.version++;
            startLocked(room);
        });
    }

    // ---------------------------------------------------------------- 조율 단계 (실시간)

    /**
     * 👍/👎 — 바로 반영한다. 내 폰은 즉시 다음 카드로, 공용 보드는 묶어서 갱신.
     * 이미 반응한 후보를 다시 누르면 반응을 바꾼다 (지난 블록을 눌러서).
     */
    public void react(String roomId, String key, int restaurantId, boolean up) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        withLock(room, () -> {
            Room.Person p = room.people.get(key);
            if (room.phase != Views.Phase.NEGOTIATE || p == null || !live(room).contains(restaurantId)) return;
            Boolean before = p.reactions.put(restaurantId, up);
            if (Objects.equals(before, up) && !Objects.equals(p.current, restaurantId)) return; // 같은 반응을 또 누름
            Set<Integer> dis = room.dislikes.computeIfAbsent(p.key, k -> new HashSet<>());
            if (up) dis.remove(restaurantId);
            else dis.add(restaurantId);
            if (Objects.equals(p.current, restaurantId)) p.current = null;
            recompute(room); // 👎는 그 사람 만족도를 바꾸므로 순위가 달라질 수 있다 (다른 사람 카드는 그대로)
            room.version++;
            if (checkEnd(room)) {
                publishAll(room);
                return;
            }
            maybeMediate(room); // 질문이 준비되면 deliverQuestion이 그 사람에게만 보낸다
            publisher.toPerson(p.key, privateView(room, p)); // 누른 사람 폰은 즉시
            p.notice = null;
            board(room, false);
        });
    }

    /** 거부권: 1인 1장, 바로 빠진다. 그 후보를 보고 있던 사람들의 카드도 다음으로 넘어간다 */
    public void veto(String roomId, String key, int restaurantId) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        withLock(room, () -> {
            Room.Person p = room.people.get(key);
            if (room.phase != Views.Phase.NEGOTIATE || p == null) return;
            Restaurant target = MockRestaurants.byId(restaurantId);
            room.version++;
            if (p.vetoLeft == 0) {
                p.notice = "거부권은 이미 썼어요";
            } else if (target == null || room.vetoed.contains(restaurantId) || !live(room).contains(restaurantId)) {
                // 같은 후보에 거부권이 겹치면 두 번째 사람 것은 돌려준다
                p.notice = "이미 빠진 후보라 거부권을 돌려드렸어요";
            } else {
                room.vetoed.add(restaurantId);
                p.vetoLeft--;
                p.notice = "거부권을 썼어요: " + target.name() + " 제외";
                room.log("누군가 거부권을 썼어요 🚫 → " + target.name() + " 제외");
                recompute(room);
                if (!checkEnd(room)) maybeMediate(room);
            }
            publishAll(room);
        });
    }

    /** 후보가 0개일 때 "절대 안 됨" 풀기 — 기다릴 이유가 없으니 바로 반영 */
    public void relax(String roomId, String key, String tag) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        withLock(room, () -> {
            Room.Person p = room.people.get(key);
            if (room.phase != Views.Phase.NEGOTIATE || p == null || !p.conditions.relaxable().contains(tag)) return;
            applyChange(room, p, p.conditions.relax(tag), "누군가 '절대 안 됨' 하나를 풀어 줬어요");
        });
    }

    /** 진행자가 "확정"을 누름 — 연장 없이 지금 규칙대로 정한다 */
    public void finish(String roomId) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        withLock(room, () -> {
            if (room.phase != Views.Phase.NEGOTIATE) return;
            Engine.Decision d = decide(room);
            if (d.pick() == null) return;
            room.version++;
            finalizeLocked(room, d, null);
            publishAll(room);
        });
    }

    /** 타이머: 끝날 시간이 된 방을 처리하고, 묶어 둔 보드를 보낸다. 시각은 lock 안에서 다시 확인한다 */
    void tick(long now) {
        sweepAway(now);
        for (Room room : rooms.values()) {
            withLock(room, () -> {
                if (room.phase == Views.Phase.NEGOTIATE && room.endsAt > 0 && now >= room.endsAt) {
                    onDeadline(room, now);
                }
                if (room.boardDirty && now - room.boardSentAt >= cfg.boardMs()) sendBoard(room, now);
            });
        }
    }

    /** 테스트용: 제한시간이 지난 것처럼 */
    void expire(String roomId) {
        Room room = rooms.get(roomId);
        withLock(room, () -> {
            if (room.phase == Views.Phase.NEGOTIATE) onDeadline(room, System.currentTimeMillis());
        });
    }

    private void onDeadline(Room room, long now) {
        Engine.Decision d = decide(room);
        room.version++;
        if (d.pick() == null) {
            // 맞는 곳이 하나도 없으면 정할 수가 없다 → 조금 더 기다리며 풀기/중재를 기다린다
            room.endsAt = now + Math.max(cfg.extendMs(), 1000);
            room.log("맞는 곳이 없어서 조금 더 기다려요 — 각자 폰을 확인해 주세요");
            publishAll(room);
            return;
        }
        int pickId = d.pick().restaurant().id();
        List<Room.Person> undecided = activePeople(room).stream().filter(p -> !p.reactions.containsKey(pickId)).toList();
        if (!room.extended && !undecided.isEmpty() && cfg.extendMs() > 0) {
            room.extended = true;
            room.endsAt = now + cfg.extendMs();
            for (Room.Person p : undecided) {
                p.current = pickId; // 고민 중이던 사람 폰에 "곧 정해질 후보"를 바로 띄운다
                p.notice = "⏳ 곧 정해져요! 지금 유력한 " + d.pick().restaurant().name() + "에 의견을 남겨 주세요";
            }
            room.log("⏳ 유력 후보에 아직 의견을 안 낸 사람이 있어서 " + cfg.extendMs() / 1000 + "초 더 기다려요");
            publishAll(room);
            return;
        }
        finalizeLocked(room, d, null);
        publishAll(room);
    }

    // ---------------------------------------------------------------- AI 중재자

    public record MediateMsg(String questionId, String choice, String text) {
    }

    /**
     * 중재 질문에 대한 답.
     * ok: "상관없어요" 버튼 → 그 "절대 안 됨"을 지운다 (버튼 자체가 확인)
     * soften: "가능하면 피할게요" 버튼 → "가능하면 피함"으로 내린다
     * keep: "그대로 둘게요" → 아무것도 안 바꾼다
     * text: 자유 답변 → AI가 "이렇게 바꿀까요?" 제안으로 정리 → 본인이 apply를 눌러야 반영
     * apply / cancel: 제안 적용 / 취소
     */
    public void mediate(String roomId, String key, MediateMsg msg) {
        Room room = rooms.get(roomId);
        if (room == null || msg == null) return;
        withLock(room, () -> {
            Room.Question q = room.question;
            Room.Person p = room.people.get(key);
            if (room.phase != Views.Phase.NEGOTIATE || q == null || p == null || !q.key.equals(key)
                    || !q.id.equals(msg.questionId()) || "WAITING".equals(q.state)) return;
            room.version++;
            switch (String.valueOf(msg.choice())) {
                case "ok" -> applyChange(room, p, p.conditions.drop(q.blocker.item()), null);
                case "soften" -> applyChange(room, p, p.conditions.relax(q.blocker.item()), null);
                case "keep" -> {
                    room.question = null;
                    p.notice = "알겠어요, ‘" + q.blocker.item() + "’은(는) 그대로 둘게요 🙂";
                    room.log("🤖 중재: 조건은 그대로 — 다른 방법을 찾아볼게요");
                    maybeMediate(room);
                    publishAll(room);
                }
                case "text" -> {
                    String text = msg.text() == null ? "" : msg.text().trim();
                    if (text.isEmpty()) return;
                    if (!ai.enabled()) {
                        p.notice = "지금은 AI가 꺼져 있어요. 아래 버튼으로 골라 주세요";
                        publisher.toPerson(p.key, privateView(room, p));
                        p.notice = null;
                        return;
                    }
                    q.state = "THINKING";
                    publisher.toPerson(p.key, privateView(room, p));
                    MediatorAi.Facts f = facts(q.blocker);
                    String qid = q.id;
                    // 원문은 AI에 한 번 보내고 끝 — 저장하지도 로그에 남기지도 않는다
                    room.afterUnlock.add(() -> ai.interpret(f, text)
                            .orTimeout(cfg.mediatorTimeoutMs(), TimeUnit.MILLISECONDS)
                            .whenComplete((r, err) -> deliverProposal(room.id, qid, err == null ? r : null)));
                }
                case "apply" -> {
                    if ("PROPOSAL".equals(q.state) && q.proposed != null) applyChange(room, p, q.proposed, null);
                }
                case "cancel" -> {
                    q.state = "ASKING";
                    q.proposed = null;
                    publisher.toPerson(p.key, privateView(room, p));
                }
                default -> {
                }
            }
        });
    }

    /**
     * 막혔는지 보고, 막혔으면 코드가 고른 한 사람에게 질문을 준비한다 (락 안에서 호출).
     * 막힘 = 후보 0개 / 후보가 mediatorBelow보다 적음 / 하한을 넘는 후보가 없음
     */
    private boolean maybeMediate(Room room) {
        if (room.phase != Views.Phase.NEGOTIATE || room.question != null || room.result == null) return false;
        boolean stuck = room.result.conflict() || room.result.ranking().size() < cfg.mediatorBelow()
                || !decide(room).qualified();
        if (!stuck) return false;
        Set<String> askable = new HashSet<>();
        activePeople(room).forEach(p -> askable.add(p.key));
        Optional<Blockers.Blocker> found = Blockers.find(MockRestaurants.ALL, conditions(room), askable, room.asked,
                room.dislikes, room.vetoed, MockRestaurants.BASE_WALK);
        if (found.isEmpty()) return false;
        Blockers.Blocker b = found.get();
        Room.Question q = new Room.Question("q" + (++room.questionSeq), b.key(), b);
        room.question = q;
        room.asked.add(Blockers.askedKey(b.key(), b.item()));
        MediatorAi.Facts f = facts(b);
        String qid = q.id;
        room.afterUnlock.add(() -> ai.question(f)
                .orTimeout(cfg.mediatorTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((text, err) -> deliverQuestion(room.id, qid, err == null ? text : null)));
        return true;
    }

    /** AI 문장이 도착(또는 실패). 그 사이 상황이 바뀌었을 수 있으니 숫자를 다시 센다 */
    private void deliverQuestion(String roomId, String qid, String aiText) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        withLock(room, () -> {
            Room.Question q = room.question;
            if (q == null || !q.id.equals(qid) || room.phase != Views.Phase.NEGOTIATE) return;
            Room.Person p = room.people.get(q.key);
            Blockers.Blocker fresh = p == null ? null : remeasure(room, p, q.blocker.item());
            if (fresh == null) { // 이미 풀렸거나 더는 도움이 안 된다
                room.question = null;
                maybeMediate(room);
                return;
            }
            boolean sameNumbers = fresh.before() == q.blocker.before() && fresh.after() == q.blocker.after();
            q.blocker = fresh;
            MediatorAi.Facts f = facts(fresh);
            boolean ok = sameNumbers && f.accepts(aiText);
            q.text = ok ? aiText.trim() : f.defaultQuestion();
            q.byAi = ok;
            q.state = "ASKING";
            room.version++;
            room.log("🤖 AI 중재자가 누군가에게 조용히 물어보고 있어요");
            publisher.toPerson(p.key, privateView(room, p));
            p.notice = null;
            board(room, false);
        });
    }

    /** 자유 답변 해석이 도착. 바뀔 내용은 코드가 만들고 문장으로 보여 준 뒤, 본인이 "적용"을 눌러야 반영한다 */
    private void deliverProposal(String roomId, String qid, MediatorAi.Reply r) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        withLock(room, () -> {
            Room.Question q = room.question;
            if (q == null || !q.id.equals(qid) || !"THINKING".equals(q.state) || room.phase != Views.Phase.NEGOTIATE) return;
            Room.Person p = room.people.get(q.key);
            if (p == null) return;
            room.version++;
            String item = q.blocker.item();
            Conditions c = p.conditions;
            List<String> lines = new ArrayList<>();
            if (r != null) {
                if (r.action() == MediatorAi.Action.OK && c.relaxable().contains(item)) {
                    c = c.drop(item); // "괜찮아요" = 조건 자체가 없어진다 (피함으로 남기면 만족도가 계속 깎인다)
                    lines.add("‘" + item + "’: 절대 안 됨 → 상관없음");
                } else if (r.action() == MediatorAi.Action.SOFTEN && c.relaxable().contains(item)) {
                    c = c.relax(item);
                    lines.add("‘" + item + "’: 절대 안 됨 → 가능하면 피함");
                }
                Set<String> avoid = new LinkedHashSet<>(c.preferNot()), want = new LinkedHashSet<>(c.want());
                for (String t : r.avoid()) {
                    // 완화 방향만: 알레르기·절대 안 됨·먹고 싶음에 이미 있는 태그는 건드리지 않는다
                    if (Foods.isKnown(t) && !c.allergy().contains(t) && !c.mustNot().contains(t) && !want.contains(t) && avoid.add(t)) {
                        lines.add("‘" + t + "’: 가능하면 피함 추가");
                    }
                }
                for (String t : r.want()) {
                    if (Foods.isKnown(t) && !c.allergy().contains(t) && !c.mustNot().contains(t) && !avoid.contains(t) && want.add(t)) {
                        lines.add("‘" + t + "’: 먹고 싶음 추가");
                    }
                }
                c = new Conditions(c.allergy(), c.allergyOther(), c.mustNot(), avoid, want,
                        c.dishNever(), c.dishAvoid(), c.dishWant(), c.maxPrice());
            }
            if (r != null && r.action() == MediatorAi.Action.KEEP && lines.isEmpty()) {
                room.question = null;
                p.notice = "알겠어요, ‘" + item + "’은(는) 그대로 둘게요 🙂";
                room.log("🤖 중재: 조건은 그대로 — 다른 방법을 찾아볼게요");
                maybeMediate(room);
                publishAll(room);
                return;
            }
            if (lines.isEmpty()) {
                q.state = "ASKING";
                p.notice = "답을 잘 이해하지 못했어요. 아래 버튼으로 골라 주실래요?";
                publisher.toPerson(p.key, privateView(room, p));
                p.notice = null;
                return;
            }
            q.state = "PROPOSAL";
            q.proposed = c;
            q.proposalLines = List.copyOf(lines);
            Map<String, Conditions> trial = conditions(room);
            trial.put(p.key, c);
            Engine.Result tr = Engine.rank(MockRestaurants.ALL, trial, room.dislikes, room.vetoed, MockRestaurants.BASE_WALK);
            q.proposalAfter = tr.conflict() ? 0 : tr.ranking().size();
            publisher.toPerson(p.key, privateView(room, p));
        });
    }

    /** 조건 변경을 반영하고 다시 계산 (중재 답 · 0개일 때 풀기 공통) */
    private void applyChange(Room room, Room.Person p, Conditions next, String logLine) {
        int before = candidateCount(room);
        p.conditions = next;
        if (room.question != null && room.question.key.equals(p.key)) room.question = null;
        recompute(room);
        int after = candidateCount(room);
        room.version++;
        room.log(logLine != null ? logLine : "🤖 누군가 조건을 바꿔 줘서 후보가 " + before + "곳 → " + after + "곳");
        p.notice = after > before ? "고마워요! 후보가 " + after + "곳이 됐어요 🙌" : "반영했어요";
        if (!checkEnd(room)) maybeMediate(room);
        publishAll(room);
    }

    // ---------------------------------------------------------------- 내부

    private void startLocked(Room room) {
        room.phase = Views.Phase.NEGOTIATE;
        room.endsAt = cfg.negotiateMs() > 0 ? System.currentTimeMillis() + cfg.negotiateMs() : 0;
        recompute(room);
        room.seen.replaceAll((id, t) -> 0L); // 처음 후보들은 "새 후보" 표시를 하지 않는다
        room.log("모두의 조건으로 후보를 골랐어요 — 각자 폰에서 하나씩 👍/👎");
        maybeMediate(room);
        publishAll(room);
    }

    private void recompute(Room room) {
        room.result = Engine.rank(MockRestaurants.ALL, conditions(room), room.dislikes, room.vetoed, MockRestaurants.BASE_WALK);
        long now = System.currentTimeMillis();
        room.result.ranking().forEach(s -> room.seen.putIfAbsent(s.restaurant().id(), now));
        room.result.relaxNotes().forEach(n -> {
            if (!room.timeline.contains(n)) room.log(n);
        });
        room.people.values().forEach(p -> assignCard(room, p));
    }

    /** 내 카드: 지금 보던 후보가 살아 있으면 그대로, 아니면 공정 순서로 아직 반응 안 한 다음 후보 */
    private void assignCard(Room room, Room.Person p) {
        Set<Integer> live = live(room);
        if (p.current != null && live.contains(p.current)) return;
        p.current = room.result.ranking().stream().map(s -> s.restaurant().id())
                .filter(id -> !p.reactions.containsKey(id)).findFirst().orElse(null);
    }

    /** 끝낼 때인가: 전원 같은 곳 👍 → 확정, 모두가 모든 후보에 반응 → 규칙대로 확정. 끝냈으면 true */
    private boolean checkEnd(Room room) {
        if (room.phase != Views.Phase.NEGOTIATE || room.result == null || room.result.ranking().isEmpty()) return false;
        List<Room.Person> active = activePeople(room);
        if (active.isEmpty()) return false;
        for (Engine.Scored s : room.result.ranking()) {
            int id = s.restaurant().id();
            if (active.stream().allMatch(p -> Boolean.TRUE.equals(p.reactions.get(id)))) {
                finalizeLocked(room, new Engine.Decision(s, s.min() + 1e-9 >= cfg.floor(), active.size()), "모두 👍");
                return true;
            }
        }
        boolean allDone = active.stream().allMatch(p -> p.current == null);
        if (allDone && room.question == null) { // 중재 질문이 열려 있으면 답을 기다린다
            finalizeLocked(room, decide(room), null);
            return true;
        }
        return false;
    }

    private void finalizeLocked(Room room, Engine.Decision d, String why) {
        room.phase = Views.Phase.DONE;
        room.endsAt = 0;
        room.question = null;
        room.winner = d.pick();
        int floorPct = percent(cfg.floor());
        room.winnerReason = why != null ? why
                : d.qualified() ? "가장 덜 만족한 사람도 " + floorPct + "% 이상인 후보 중 👍가 가장 많아요 (" + d.likes() + "개)"
                : d.likes() > 0
                ? floorPct + "%를 넘는 후보가 없어서, 가장 덜 만족한 사람 점수가 비슷한 곳 중 👍가 가장 많은 곳으로 (" + d.likes() + "개)"
                : floorPct + "%를 넘는 후보가 없어서, 가장 덜 만족한 사람이 가장 만족하는 곳으로";
        room.log("✅ " + d.pick().restaurant().name() + "(으)로 확정! — " + room.winnerReason);
    }

    private Engine.Decision decide(Room room) {
        return Engine.decide(room.result, likeCounts(room), cfg.floor());
    }

    private Map<Integer, Integer> likeCounts(Room room) {
        Map<Integer, Integer> m = new HashMap<>();
        for (Room.Person p : room.people.values()) {
            p.reactions.forEach((id, up) -> {
                if (up) m.merge(id, 1, Integer::sum);
            });
        }
        return m;
    }

    private Blockers.Blocker remeasure(Room room, Room.Person p, String item) {
        if (!p.conditions.relaxable().contains(item)) return null;
        int before = candidateCount(room);
        Map<String, Conditions> trial = conditions(room);
        trial.put(p.key, p.conditions.relax(item));
        Engine.Result r = Engine.rank(MockRestaurants.ALL, trial, room.dislikes, room.vetoed, MockRestaurants.BASE_WALK);
        int after = r.conflict() ? 0 : r.ranking().size();
        return after > before ? new Blockers.Blocker(p.key, item, p.conditions.dishNever().contains(item), before, after) : null;
    }

    private static MediatorAi.Facts facts(Blockers.Blocker b) {
        return new MediatorAi.Facts(b.item(), b.dish(), b.before(), b.after());
    }

    private static int candidateCount(Room room) {
        return room.result == null || room.result.conflict() ? 0 : room.result.ranking().size();
    }

    private static Map<String, Conditions> conditions(Room room) {
        Map<String, Conditions> conds = new HashMap<>();
        room.people.forEach((k, p) -> conds.put(k, p.conditions));
        return conds;
    }

    private static Set<Integer> live(Room room) {
        Set<Integer> s = new HashSet<>();
        if (room.result != null) room.result.ranking().forEach(x -> s.add(x.restaurant().id()));
        return s;
    }

    private boolean everyoneSubmitted(Room room) {
        return room.people.size() >= MIN_PEOPLE && room.people.values().stream().allMatch(p -> p.submitted);
    }

    /** 의견을 기다려야 하는 사람들 (제출하고 떠난 사람 제외) */
    private List<Room.Person> activePeople(Room room) {
        return room.people.values().stream().filter(p -> !p.away).toList();
    }

    /** 모두에게 보낸다: 공용 보드는 바로, 개인 화면은 각자에게 */
    private void publishAll(Room room) {
        sendBoard(room, System.currentTimeMillis());
        for (Room.Person p : room.people.values()) {
            publisher.toPerson(p.key, privateView(room, p));
            p.notice = null; // 알림은 한 번만 보낸다
        }
    }

    /** 공용 보드: 묶어 보내기가 켜져 있으면 표시만 해 두고 타이머가 보낸다 */
    private void board(Room room, boolean now) {
        if (now || cfg.boardMs() <= 0) sendBoard(room, System.currentTimeMillis());
        else room.boardDirty = true;
    }

    private void sendBoard(Room room, long now) {
        room.boardDirty = false;
        room.boardSentAt = now;
        publisher.toRoom(room.id, publicView(room));
    }

    Views.PublicView publicView(Room room) {
        int submitted = (int) room.people.values().stream().filter(p -> p.submitted).count();
        List<Room.Person> active = activePeople(room);
        long now = System.currentTimeMillis();
        long remaining = room.endsAt > 0 ? Math.max(0, room.endsAt - now) : 0;
        int floorPct = percent(cfg.floor());
        if (room.phase == Views.Phase.INPUT || room.result == null) {
            return new Views.PublicView(room.version, room.phase, room.people.size(), submitted, active.size(),
                    List.of(), null, 0, floorPct, 0, false, false, List.copyOf(room.timeline), null, null, null);
        }
        Map<Integer, Integer> likes = likeCounts(room);
        List<Views.Block> board = new ArrayList<>();
        Set<Integer> live = live(room);
        for (Engine.Scored s : room.result.ranking()) {
            int id = s.restaurant().id();
            long seenAt = room.seen.getOrDefault(id, 0L);
            board.add(new Views.Block(place(s.restaurant()), likes.getOrDefault(id, 0), percent(s.min()),
                    s.min() + 1e-9 >= cfg.floor(), false, seenAt > 0 && now - seenAt < FRESH_MS));
        }
        for (Integer id : room.seen.keySet()) { // 빠진 후보도 지우지 않고 뒤에 회색으로
            if (!live.contains(id)) {
                board.add(new Views.Block(place(MockRestaurants.byId(id)), likes.getOrDefault(id, 0), null, false, true, false));
            }
        }
        Integer leaderId = null;
        int undecided = 0;
        if (room.phase == Views.Phase.NEGOTIATE) {
            Engine.Decision d = decide(room);
            if (d.pick() != null) {
                leaderId = d.pick().restaurant().id();
                int lid = leaderId;
                undecided = (int) active.stream().filter(p -> !p.reactions.containsKey(lid)).count();
            }
        }
        String notice = room.result.conflict() ? "필수 조건끼리 부딪혀서 맞는 곳이 없어요. 각자 폰을 확인해 주세요" : null;
        return new Views.PublicView(room.version, room.phase, room.people.size(), submitted, active.size(),
                board, leaderId, undecided, floorPct, remaining, room.extended, room.question != null,
                List.copyOf(room.timeline), notice,
                room.winner == null ? null : place(room.winner.restaurant()), room.winnerReason);
    }

    private Views.PrivateView privateView(Room room, Room.Person p) {
        Views.Card card = null;
        List<Views.Past> history = new ArrayList<>();
        List<String> relaxable = List.of();
        Views.Mediator mediator = null;
        Set<Integer> mine = room.dislikes.getOrDefault(p.key, Set.of());
        if (room.result != null) {
            Set<Integer> live = live(room);
            Integer show = room.phase == Views.Phase.DONE && room.winner != null ? Integer.valueOf(room.winner.restaurant().id()) : p.current;
            Restaurant r = show == null ? null : MockRestaurants.byId(show);
            if (r != null) {
                int[] s = Engine.satisfiedCount(r, p.conditions, mine);
                int reactedLive = (int) p.reactions.keySet().stream().filter(live::contains).count();
                card = new Views.Card(place(r), s[0], s[1], Math.min(reactedLive + 1, live.size()), live.size());
            }
            List<Integer> ids = new ArrayList<>(p.reactions.keySet());
            for (int i = ids.size() - 1; i >= 0; i--) { // 최근 것부터
                int id = ids.get(i);
                history.add(new Views.Past(place(MockRestaurants.byId(id)), p.reactions.get(id), !live.contains(id)));
            }
            if (room.result.conflict()) relaxable = List.copyOf(p.conditions.relaxable()); // 알레르기는 넣지 않는다
            Room.Question q = room.question;
            if (q != null && q.key.equals(p.key) && !"WAITING".equals(q.state)) {
                mediator = new Views.Mediator(q.id, q.text, q.byAi, q.state, q.proposalLines, q.proposalAfter);
            }
        }
        List<String> unmatched = Engine.unmatchedDishes(p.conditions, MockRestaurants.ALL);
        return new Views.PrivateView(room.version, room.phase, p.conditions, card, history, p.vetoLeft, relaxable,
                p.notice, mediator, unmatched, allergyCheck(room, p), p.allergyRequested, p.submitted);
    }

    /** 확정 후, 식당 데이터로 거를 수 없는 알레르기(땅콩·우유…)가 있는 사람에게만 보여 줄 문구 */
    private String allergyCheck(Room room, Room.Person p) {
        if (room.phase != Views.Phase.DONE || p.conditions.allergyOther().isEmpty() || room.winner == null) return null;
        return "“" + room.winner.restaurant().name() + "”에 " + String.join(", ", p.conditions.allergyOther())
                + "이(가) 들어가는지 이 앱은 알 수 없어요. 주문 전에 직접 확인해 주세요";
    }

    /** 본인이 원할 때만: 공용 화면에 "확인이 필요한 알레르기가 있어요: 땅콩"을 이름 없이 띄운다 (알레르기는 민감한 건강 정보) */
    public void requestAllergyCheck(String roomId, String key) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        withLock(room, () -> {
            Room.Person p = room.people.get(key);
            if (p == null || room.phase != Views.Phase.DONE || p.allergyRequested || p.conditions.allergyOther().isEmpty()) return;
            p.allergyRequested = true;
            room.log("참가자 중 확인이 필요한 알레르기가 있어요: " + String.join(", ", p.conditions.allergyOther()));
            room.version++;
            publishAll(room);
        });
    }

    private Views.PrivateView privateNotice(Room room, String notice) {
        return new Views.PrivateView(room.version, room.phase, Conditions.empty(), null, List.of(), 0, List.of(), notice,
                null, List.of(), null, false, false);
    }

    private static Views.Place place(Restaurant r) {
        List<String> tags = r.tags().stream().sorted(Comparator.comparingInt(Foods.ALL::indexOf)).toList();
        return new Views.Place(r.id(), r.name(), tags, r.price(), r.walkMin());
    }

    private static int percent(double v) {
        return (int) Math.round(v * 100);
    }

    /** 락 안에서 할 일을 하고, 락을 푼 뒤 afterUnlock(AI 호출 등)을 실행한다 */
    private static void withLock(Room room, Runnable action) {
        List<Runnable> later = List.of();
        room.lock.lock();
        try {
            action.run();
        } finally {
            if (room.lock.getHoldCount() == 1 && !room.afterUnlock.isEmpty()) {
                later = new ArrayList<>(room.afterUnlock);
                room.afterUnlock.clear();
            }
            room.lock.unlock();
        }
        later.forEach(Runnable::run);
    }

    /** 테스트용: 현재 상태로 계산을 새로 했을 때와 방이 들고 있는 결과가 같은지 확인 */
    Engine.Result recomputeFresh(String roomId) {
        Room room = rooms.get(roomId);
        return Engine.rank(MockRestaurants.ALL, conditions(room), room.dislikes, room.vetoed, MockRestaurants.BASE_WALK);
    }

    Room room(String roomId) {
        return rooms.get(roomId);
    }
}
