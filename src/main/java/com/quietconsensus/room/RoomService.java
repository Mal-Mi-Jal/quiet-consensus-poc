package com.quietconsensus.room;

import com.quietconsensus.engine.Conditions;
import com.quietconsensus.engine.Engine;
import com.quietconsensus.engine.Foods;
import com.quietconsensus.engine.MockRestaurants;
import com.quietconsensus.engine.Restaurant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 방 관리. 핵심 규칙:
 * - 방마다 lock 하나로 한 줄 서기 (동시에 눌러도 한 번에 하나씩 처리)
 * - 조율 단계 이벤트는 큐에 모았다가 batchMs마다 한 번에 반영 → 누가 누른 순간 바뀌었는지 티가 안 남
 * - 반영할 때마다 "현재 전체 상태"로 처음부터 다시 계산하고 version을 1 올려 방송
 */
@Service
public class RoomService {

    public static final int MIN_PEOPLE = 3;
    public static final int MAX_PEOPLE = 8;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Publisher publisher;
    private final ScheduledExecutorService scheduler;

    public RoomService(Publisher publisher, @Value("${qc.batch-ms:3000}") long batchMs) {
        this.publisher = publisher;
        if (batchMs > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "room-flush");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::flushAll, batchMs, batchMs, TimeUnit.MILLISECONDS);
        } else {
            scheduler = null; // 테스트에서는 flush를 직접 부른다
        }
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    // ---------------------------------------------------------------- 입장 / 입력 단계

    public void join(String roomId, String key) {
        Room room = rooms.computeIfAbsent(roomId, Room::new);
        withLock(room, () -> {
            if (room.people.containsKey(key)) return;
            if (room.people.size() >= MAX_PEOPLE) {
                publisher.toPerson(key, privateNotice(room, "방이 꽉 찼어요 (최대 " + MAX_PEOPLE + "명)"));
                return;
            }
            Room.Person p = new Room.Person(key);
            room.people.put(key, p);
            if (room.phase != Views.Phase.INPUT && room.result != null && room.result.top() != null) {
                // 넘겨받기: 늦게 온 사람에게 지금 상황만 요약해 준다
                p.notice = "지금 1순위는 " + room.result.top().restaurant().name()
                        + " (가장 덜 만족한 사람도 " + percent(room.result.top().min()) + "%). 조건을 내면 다음 반영 때 같이 계산돼요";
            }
            room.version++;
            publishAll(room);
        });
    }

    public void submit(String roomId, String key, Conditions conditions) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        if (room.phase == Views.Phase.NEGOTIATE) {
            room.pending.add(new Room.Submit(key, conditions));
            return;
        }
        withLock(room, () -> {
            Room.Person p = room.people.get(key);
            if (p == null || room.phase != Views.Phase.INPUT) return;
            p.conditions = conditions;
            p.submitted = true;
            room.version++;
            if (room.people.size() >= MIN_PEOPLE && room.people.values().stream().allMatch(x -> x.submitted)) {
                startLocked(room); // 전원 제출 → 그때 처음으로 후보 공개
            } else {
                publishAll(room); // 입력 단계에는 "몇 명 제출"만 나간다
            }
        });
    }

    /** 제한시간이 끝났거나 진행자가 시작을 누른 경우 */
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

    // ---------------------------------------------------------------- 조율 단계 (큐에 쌓기만 한다)

    public void react(String roomId, String key, int restaurantId, boolean up) {
        enqueue(roomId, new Room.React(key, restaurantId, up));
    }

    public void veto(String roomId, String key, int restaurantId) {
        enqueue(roomId, new Room.Veto(key, restaurantId));
    }

    public void relax(String roomId, String key, String tag) {
        enqueue(roomId, new Room.Relax(key, tag));
    }

    private void enqueue(String roomId, Room.Event e) {
        Room room = rooms.get(roomId);
        if (room != null && room.phase == Views.Phase.NEGOTIATE) room.pending.add(e);
    }

    public void flushAll() {
        for (Room room : rooms.values()) flush(room.id);
    }

    /** 모아 둔 이벤트를 도착 순서대로 반영하고, 바뀐 게 있으면 한 번만 다시 계산해서 방송한다 */
    public void flush(String roomId) {
        Room room = rooms.get(roomId);
        if (room == null || room.pending.isEmpty()) return;
        withLock(room, () -> {
            if (room.phase != Views.Phase.NEGOTIATE) {
                room.pending.clear();
                return;
            }
            String oldTop = topName(room);
            boolean changed = false;
            boolean newConditions = false, reactions = false, relaxed = false;
            List<String> vetoLines = new ArrayList<>();
            Room.Event e;
            while ((e = room.pending.poll()) != null) {
                Room.Person p = room.people.get(e.key());
                if (p == null) continue;
                switch (e) {
                    case Room.Submit s -> {
                        p.conditions = s.conditions();
                        p.submitted = true;
                        newConditions = changed = true;
                    }
                    case Room.React r -> {
                        Set<Integer> dis = room.dislikes.computeIfAbsent(p.key, k -> new HashSet<>());
                        if (r.up()) {
                            room.likes.put(p.key, r.restaurantId());
                            dis.remove(r.restaurantId());
                        } else {
                            dis.add(r.restaurantId());
                            room.likes.remove(p.key, r.restaurantId());
                        }
                        reactions = changed = true;
                    }
                    case Room.Veto v -> {
                        Restaurant target = MockRestaurants.byId(v.restaurantId());
                        if (p.vetoLeft == 0) {
                            p.notice = "거부권은 이미 썼어요";
                        } else if (target == null || room.vetoed.contains(v.restaurantId()) || !inRanking(room, v.restaurantId())) {
                            // 동시에 같은 후보에 거부권이 들어오면 두 번째 사람 것은 돌려준다
                            p.notice = "이미 빠진 후보라 거부권을 돌려드렸어요";
                        } else {
                            room.vetoed.add(v.restaurantId());
                            p.vetoLeft--;
                            p.notice = "거부권을 썼어요: " + target.name() + " 제외";
                            vetoLines.add("누군가 거부권을 썼어요 🚫 → " + target.name() + " 제외");
                            changed = true;
                        }
                    }
                    case Room.Relax x -> {
                        if (p.conditions.mustNot().contains(x.tag())) {
                            p.conditions = p.conditions.relax(x.tag());
                            relaxed = changed = true;
                        }
                    }
                }
            }
            room.version++; // 개인 알림만 바뀌어도 새 버전으로 보낸다
            if (changed) {
                recompute(room);
                if (newConditions) room.log("새 조건이 반영됐어요");
                if (reactions) room.log("반응이 반영됐어요");
                if (relaxed) room.log("누군가 '절대 안 됨' 하나를 풀어 줬어요");
                vetoLines.forEach(room::log);
                String newTop = topName(room);
                if (!Objects.equals(oldTop, newTop) && newTop != null) {
                    room.log("1순위: " + (oldTop == null ? "없음" : oldTop) + " → " + newTop);
                }
                checkDone(room);
            }
            publishAll(room);
        });
    }

    // ---------------------------------------------------------------- 내부

    private void startLocked(Room room) {
        room.phase = Views.Phase.NEGOTIATE;
        recompute(room);
        room.log("모두의 조건으로 첫 후보를 골랐어요");
        publishAll(room);
    }

    private void recompute(Room room) {
        Map<String, Conditions> conds = new HashMap<>();
        room.people.forEach((k, p) -> conds.put(k, p.conditions));
        room.result = Engine.rank(MockRestaurants.ALL, conds, room.dislikes, room.vetoed, MockRestaurants.BASE_WALK);
        room.result.relaxNotes().forEach(n -> {
            if (!room.timeline.contains(n)) room.log(n);
        });
    }

    private void checkDone(Room room) {
        Engine.Scored top = room.result.top();
        if (top == null) return;
        long likes = room.likes.values().stream().filter(id -> id == top.restaurant().id()).count();
        if (likes == room.people.size()) {
            room.phase = Views.Phase.DONE;
            room.log("모두 👍 — " + top.restaurant().name() + "(으)로 확정!");
        }
    }

    public void finish(String roomId) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        withLock(room, () -> {
            if (room.phase != Views.Phase.NEGOTIATE || room.result == null || room.result.top() == null) return;
            room.phase = Views.Phase.DONE;
            room.version++;
            room.log("시간 종료 — " + room.result.top().restaurant().name() + "(으)로 확정!");
            publishAll(room);
        });
    }

    private boolean inRanking(Room room, int id) {
        return room.result != null && room.result.ranking().stream().anyMatch(s -> s.restaurant().id() == id);
    }

    private String topName(Room room) {
        return room.result == null || room.result.top() == null ? null : room.result.top().restaurant().name();
    }

    private void publishAll(Room room) {
        publisher.toRoom(room.id, publicView(room));
        for (Room.Person p : room.people.values()) {
            publisher.toPerson(p.key, privateView(room, p));
            p.notice = null; // 알림은 한 번만 보낸다
        }
    }

    Views.PublicView publicView(Room room) {
        int submitted = (int) room.people.values().stream().filter(p -> p.submitted).count();
        if (room.phase == Views.Phase.INPUT || room.result == null) {
            return new Views.PublicView(room.version, room.phase, room.people.size(), submitted,
                    null, List.of(), null, 0, 0, List.copyOf(room.timeline), null);
        }
        Engine.Scored top = room.result.top();
        List<Views.Place> next = room.result.ranking().stream().skip(1).limit(2).map(s -> place(s.restaurant())).toList();
        int likes = top == null ? 0 : (int) room.likes.values().stream().filter(id -> id == top.restaurant().id()).count();
        String notice = room.result.conflict() ? "필수 조건끼리 부딪혀서 맞는 곳이 없어요. 각자 폰을 확인해 주세요" : null;
        return new Views.PublicView(room.version, room.phase, room.people.size(), submitted,
                top == null ? null : place(top.restaurant()), next,
                top == null ? null : percent(top.min()), room.result.ranking().size(), likes,
                List.copyOf(room.timeline), notice);
    }

    private Views.PrivateView privateView(Room room, Room.Person p) {
        Integer ok = null, total = null;
        List<String> relaxable = List.of();
        if (room.result != null && room.result.top() != null) {
            int[] s = Engine.satisfiedCount(room.result.top().restaurant(), p.conditions,
                    room.dislikes.getOrDefault(p.key, Set.of()));
            ok = s[0];
            total = s[1];
        }
        if (room.result != null && room.result.conflict()) {
            relaxable = List.copyOf(p.conditions.mustNot()); // 알레르기는 넣지 않는다
        }
        return new Views.PrivateView(room.version, room.phase, p.conditions, ok, total, p.vetoLeft, relaxable, p.notice);
    }

    private Views.PrivateView privateNotice(Room room, String notice) {
        return new Views.PrivateView(room.version, room.phase, Conditions.empty(), null, null, 0, List.of(), notice);
    }

    private static Views.Place place(Restaurant r) {
        List<String> tags = r.tags().stream().sorted(Comparator.comparingInt(Foods.ALL::indexOf)).toList();
        return new Views.Place(r.id(), r.name(), tags, r.price(), r.walkMin());
    }

    private static int percent(double v) {
        return (int) Math.round(v * 100);
    }

    private static void withLock(Room room, Runnable action) {
        room.lock.lock();
        try {
            action.run();
        } finally {
            room.lock.unlock();
        }
    }

    /** 테스트용: 현재 상태로 계산을 새로 했을 때와 방이 들고 있는 결과가 같은지 확인 */
    Engine.Result recomputeFresh(String roomId) {
        Room room = rooms.get(roomId);
        Map<String, Conditions> conds = new HashMap<>();
        room.people.forEach((k, p) -> conds.put(k, p.conditions));
        return Engine.rank(MockRestaurants.ALL, conds, room.dislikes, room.vetoed, MockRestaurants.BASE_WALK);
    }

    Room room(String roomId) {
        return rooms.get(roomId);
    }
}
