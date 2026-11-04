package com.quietconsensus.room;

import com.quietconsensus.engine.Conditions;
import com.quietconsensus.mediator.MediatorAi;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RoomServiceTest {

    /** 방송 내용을 메모리에 모으는 가짜 Publisher */
    static class Capture implements Publisher {
        final List<Views.PublicView> publics = Collections.synchronizedList(new ArrayList<>());
        final Map<String, List<Views.PrivateView>> privates = new ConcurrentHashMap<>();

        @Override
        public void toRoom(String roomId, Views.PublicView view) {
            publics.add(view);
        }

        @Override
        public void toPerson(String key, Views.PrivateView view) {
            privates.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(view);
        }

        Views.PublicView lastPublic() {
            return publics.get(publics.size() - 1);
        }

        Views.PrivateView lastPrivate(String key) {
            List<Views.PrivateView> l = privates.get(key);
            return l.get(l.size() - 1);
        }
    }

    /** 가짜 AI — 진짜 Haiku는 부르지 않는다. 무엇을 받았는지 기록해서 "남의 조건이 프롬프트에 없는지"도 본다 */
    static class FakeAi implements MediatorAi {
        String questionText = "‘{item}’이 꼭 안 되는 이유가 있을까요? 괜찮다면 후보가 {after}곳으로 늘어나요 🙂";
        Reply reply = new Reply(Action.UNCLEAR, List.of(), List.of());
        boolean enabled = true;
        final List<Facts> asked = Collections.synchronizedList(new ArrayList<>());
        final List<String> answers = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<String> question(Facts f) {
            asked.add(f);
            if (questionText == null) return CompletableFuture.failedFuture(new RuntimeException("꺼짐"));
            return CompletableFuture.completedFuture(questionText.replace("{item}", f.item()).replace("{after}", String.valueOf(f.after())));
        }

        @Override
        public CompletableFuture<Reply> interpret(Facts f, String answer) {
            answers.add(answer);
            return reply == null ? CompletableFuture.failedFuture(new RuntimeException("꺼짐")) : CompletableFuture.completedFuture(reply);
        }

        @Override
        public boolean enabled() {
            return enabled;
        }
    }

    private static Conditions want(String... tags) {
        return new Conditions(Set.of(), Set.of(), Set.of(), Set.of(tags), null);
    }

    private static Conditions preferNot(String... tags) {
        return new Conditions(Set.of(), Set.of(), Set.of(tags), Set.of(), null);
    }

    private static Conditions never(String... tags) {
        return new Conditions(Set.of(), Set.of(tags), Set.of(), Set.of(), null);
    }

    private RoomService ready(Capture cap, String room, int n) {
        return ready(cap, new FakeAi(), RoomService.Settings.forTests(), room, n);
    }

    private RoomService ready(Capture cap, FakeAi ai, RoomService.Settings cfg, String room, int n) {
        RoomService s = new RoomService(cap, ai, cfg, false); // 타이머 없이 tick / expire를 직접 부른다
        for (int i = 0; i < n; i++) s.join(room, "p" + i);
        return s;
    }

    private static int cardId(Capture cap, String key) {
        return cap.lastPrivate(key).card().place().id();
    }

    private static List<Integer> liveIds(Views.PublicView v) {
        return v.board().stream().filter(b -> !b.out()).map(b -> b.place().id()).toList();
    }

    @Test
    void 입력_단계에서는_후보를_보여주지_않고_전원_제출해야_공개된다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 4);
        s.submit("r", "p0", want("고기"));
        s.submit("r", "p1", preferNot("고기"));
        s.submit("r", "p2", want("국물"));
        assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.INPUT);
        assertThat(cap.lastPublic().board()).isEmpty();
        assertThat(cap.lastPublic().submitted()).isEqualTo(3);

        s.submit("r", "p3", Conditions.empty());
        assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.NEGOTIATE);
        assertThat(cap.lastPublic().board()).isNotEmpty();
        assertThat(cap.lastPrivate("p0").card()).isNotNull();
    }

    @Test
    void 개인_정보는_본인에게만_간다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        s.submit("r", "p0", want("고기"));
        s.submit("r", "p1", preferNot("매운음식"));
        s.submit("r", "p2", want("면"));
        assertThat(cap.lastPrivate("p0").myConditions().want()).containsExactly("고기");
        assertThat(cap.lastPrivate("p1").myConditions().preferNot()).containsExactly("매운음식");
        // 공개 상태에는 조건 칸 자체가 없다 (구조로 막는다)
        assertThat(Views.PublicView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getType)
                .doesNotContain((Class) Conditions.class);
        assertThat(Views.Block.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("people", "keys", "dislikes");
    }

    // ---------------------------------------------------------------- 실시간: 카드는 사람마다 따로

    @Test
    void 내가_누르면_내_카드만_바로_다음으로_넘어간다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        int first = cardId(cap, "p0");
        assertThat(cardId(cap, "p1")).isEqualTo(first);

        s.react("r", "p0", first, false);
        assertThat(cardId(cap, "p0")).isNotEqualTo(first);           // 내 폰은 즉시 다음 카드
        assertThat(cap.lastPrivate("p0").history()).extracting(p -> p.place().id()).containsExactly(first);
        assertThat(s.room("r").people.get("p1").current).isEqualTo(first); // 남의 카드는 그대로
    }

    @Test
    void 남의_반응으로_순위가_바뀌어도_고민_중인_내_카드는_사라지지_않는다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        int first = cardId(cap, "p1");

        s.react("r", "p0", first, false); // p0의 👎로 first의 최저 만족도가 0% → 순위가 뒤로 밀린다
        assertThat(liveIds(s.publicView(s.room("r"))).get(0)).isNotEqualTo(first);
        assertThat(s.room("r").people.get("p1").current).isEqualTo(first); // 그래도 p1이 보던 카드는 그대로
        assertThat(s.room("r").people.get("p2").current).isEqualTo(first);
    }

    @Test
    void 공용_보드는_묶어서_보내고_빠진_후보도_지우지_않는다() {
        Capture cap = new Capture();
        var cfg = new RoomService.Settings(0, 15000, 2000, 30000, 0.5, 4, 3000);
        RoomService s = ready(cap, new FakeAi(), cfg, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        int sent = cap.publics.size();
        int first = cardId(cap, "p0");

        s.react("r", "p0", first, true);
        s.react("r", "p1", first, true);
        assertThat(cap.publics).hasSize(sent);                // 누른 순간에는 공용 화면이 안 바뀐다
        s.tick(System.currentTimeMillis() + 2500);
        assertThat(cap.publics).hasSize(sent + 1);            // 묶어서 한 번
        assertThat(cap.lastPublic().board().get(0).likes()).isEqualTo(2);

        s.veto("r", "p2", first);                             // 거부권은 바로 방송
        Views.Block gone = cap.lastPublic().board().stream().filter(b -> b.place().id() == first).findFirst().orElseThrow();
        assertThat(gone.out()).isTrue();                      // 회색으로 남는다
        assertThat(cardId(cap, "p2")).isNotEqualTo(first);
    }

    @Test
    void 지난_카드를_다시_눌러_반응을_바꿀_수_있다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        int first = cardId(cap, "p0");
        s.react("r", "p0", first, false);
        s.react("r", "p0", first, true); // 지난 블록을 눌러 마음을 바꿈
        assertThat(s.room("r").people.get("p0").reactions).containsEntry(first, true).hasSize(1);
        assertThat(s.room("r").dislikes.get("p0")).doesNotContain(first);
        assertThat(cap.lastPrivate("p0").history().get(0).up()).isTrue();
    }

    @Test
    void 전원이_같은_곳에_좋아요면_바로_확정된다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        int top = cardId(cap, "p0");
        s.react("r", "p0", top, true);
        s.react("r", "p1", top, true);
        assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.NEGOTIATE);
        s.react("r", "p2", top, true);
        assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.DONE);
        assertThat(cap.lastPublic().winner().id()).isEqualTo(top);
        assertThat(cap.lastPublic().winnerReason()).isEqualTo("모두 👍");
    }

    // ---------------------------------------------------------------- 마감 · 결정 규칙

    @Test
    void 시간이_끝나도_유력_후보에_의견을_안_낸_사람이_있으면_한_번_연장하고_그_사람에게_띄운다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        int top = cardId(cap, "p0");
        s.react("r", "p0", top, true);
        s.react("r", "p1", top, true);
        int p2Card = s.room("r").people.get("p2").current;
        s.react("r", "p2", p2Card == top ? liveIds(cap.lastPublic()).get(1) : p2Card, false); // p2는 다른 카드를 보고 넘김

        s.expire("r");
        Views.PublicView v = cap.lastPublic();
        assertThat(v.phase()).isEqualTo(Views.Phase.NEGOTIATE);
        assertThat(v.extended()).isTrue();
        assertThat(v.undecidedOnLeader()).isEqualTo(1);
        assertThat(cardId(cap, "p2")).isEqualTo(v.leaderId());       // 고민 중이던 사람 폰에 유력 후보
        assertThat(cap.lastPrivate("p2").notice()).contains("곧 정해져요");
        assertThat(cap.lastPrivate("p0").notice()).isNull();          // 다른 사람에게는 안 간다

        s.expire("r"); // 연장은 한 번뿐
        assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.DONE);
    }

    @Test
    void 좋아요가_많아도_한_명이_하한_아래면_뽑히지_않는다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        int x = cardId(cap, "p0");
        s.react("r", "p0", x, true);
        s.react("r", "p1", x, true);
        s.react("r", "p2", x, false); // 두 명이 좋아해도 p2의 만족도는 0% → 하한 50% 아래
        s.expire("r");
        s.expire("r");
        Views.PublicView v = cap.lastPublic();
        assertThat(v.phase()).isEqualTo(Views.Phase.DONE);
        assertThat(v.winner().id()).isNotEqualTo(x);
        assertThat(v.winnerReason()).contains("50% 이상");
    }

    @Test
    void 하한을_넘는_후보_중에서는_좋아요가_많은_곳이_뽑힌다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        List<Integer> ids = liveIds(cap.lastPublic());
        int second = ids.get(1);
        s.react("r", "p0", second, true);
        s.react("r", "p1", second, true);
        s.finish("r");
        assertThat(cap.lastPublic().winner().id()).isEqualTo(second); // 공정 순서 1등이 아니어도 👍 최다
        assertThat(cap.lastPublic().winnerReason()).contains("👍가 가장 많아요 (2개)");
    }

    @Test
    void 모두가_모든_후보에_반응하면_기다리지_않고_정한다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        List<Integer> ids = liveIds(cap.lastPublic());
        for (int i = 0; i < 3; i++) for (int id : ids) s.react("r", "p" + i, id, i != 2); // p2는 전부 👎
        Views.PublicView v = cap.lastPublic();
        assertThat(v.phase()).isEqualTo(Views.Phase.DONE);
        assertThat(v.winnerReason()).contains("넘는 후보가 없어서"); // 모두 p2 만족도 0% → maximin으로
    }

    @Test
    void 시간이_다_되면_저절로_끝난다() throws Exception {
        Capture cap = new Capture();
        var cfg = new RoomService.Settings(300, 0, 0, 30000, 0.5, 4, 3000); // 0.3초 + 진짜 타이머, 연장 없음
        RoomService s = new RoomService(cap, new FakeAi(), cfg, true);
        try {
            for (int i = 0; i < 3; i++) s.join("r", "p" + i);
            for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
            assertThat(cap.lastPublic().remainingMs()).isBetween(1L, 300L);
            Thread.sleep(900);
            assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.DONE);
        } finally {
            s.stop();
        }
    }

    @Test
    void 같은_후보에_동시에_거부권을_쓰면_두번째는_돌려준다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 4);
        for (int i = 0; i < 4; i++) s.submit("r", "p" + i, Conditions.empty());
        int top = cardId(cap, "p0");
        s.veto("r", "p1", top);
        s.veto("r", "p2", top);
        assertThat(cap.lastPrivate("p1").vetoLeft()).isZero();
        assertThat(cap.lastPrivate("p2").vetoLeft()).isEqualTo(1);
        assertThat(cap.lastPrivate("p2").notice()).contains("돌려드렸어요");
        assertThat(liveIds(cap.lastPublic())).doesNotContain(top);
        assertThat(cap.lastPublic().timeline()).anyMatch(l -> l.startsWith("누군가 거부권"));
    }

    @Test
    void 여덟_명이_동시에_마구_눌러도_상태가_꼬이지_않는다() throws Exception {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 8);
        for (int i = 0; i < 8; i++) s.submit("r", "p" + i, Conditions.empty());

        String[] tags = {"고기", "국물", "면", "매운음식", "밥", "튀김", "해산물"};
        ExecutorService pool = Executors.newFixedThreadPool(9);
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < 8; t++) {
            String key = "p" + t;
            Random rnd = new Random(t);
            pool.submit(() -> {
                try {
                    go.await();
                    for (int k = 0; k < 300; k++) {
                        int id = 1 + rnd.nextInt(15);
                        String tag = tags[rnd.nextInt(tags.length)];
                        switch (rnd.nextInt(6)) {
                            case 0, 1 -> s.react("r", key, id, rnd.nextBoolean());
                            case 2 -> s.veto("r", key, id);
                            case 3 -> s.submit("r", key, rnd.nextBoolean() ? want(tag) : never(tag)); // 중재도 같이 돈다
                            case 4 -> {
                                Room.Question q = s.room("r").question; // 락 밖에서 읽는 건 일부러 (오래된 id로 답해도 안전해야 한다)
                                if (q != null) s.mediate("r", key, new RoomService.MediateMsg(q.id, rnd.nextBoolean() ? "relax" : "keep", null));
                            }
                            default -> s.react("r", key, id, true);
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
                return null;
            });
        }
        pool.submit(() -> { // 누르는 동안 다른 스레드가 계속 타이머를 돌린다
            go.await();
            while (running.get()) {
                s.tick(System.currentTimeMillis());
                Thread.sleep(2);
            }
            return null;
        });
        go.countDown();
        Thread.sleep(1500);
        running.set(false);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();
        // 1) 방송된 버전은 항상 증가 (뒤집히거나 같은 버전이 두 번 나가지 않음)
        List<Long> versions = cap.publics.stream().map(Views.PublicView::version).toList();
        for (int i = 1; i < versions.size(); i++) assertThat(versions.get(i)).isGreaterThan(versions.get(i - 1));
        // 2) 잃어버린 업데이트 없음: 방이 들고 있는 결과 = 현재 상태로 새로 계산한 결과
        Room room = s.room("r");
        assertThat(room.result.ranking().stream().map(x -> x.restaurant().id()).toList())
                .isEqualTo(s.recomputeFresh("r").ranking().stream().map(x -> x.restaurant().id()).toList());
        // 3) 거부권은 1인 1장: 쓴 장수 = 빠진 후보 수
        int used = room.people.values().stream().mapToInt(p -> 1 - p.vetoLeft).sum();
        assertThat(room.vetoed).hasSize(used);
        // 4) 각자 카드는 살아 있는 후보이거나 없음
        var live = room.result.ranking().stream().map(x -> x.restaurant().id()).toList();
        room.people.values().forEach(p -> assertThat(p.current == null || live.contains(p.current)).isTrue());
        System.out.printf("%n[동시성] 8명 × 300번 (반응·거부권·조건·중재 답) + 타이머 → 방송 %d회, 버전 단조 증가 ✅, 거부권 %d장 = 제외 %d곳 ✅, 상태 %s%n",
                versions.size(), used, room.vetoed.size(), room.phase);
    }

    @Test
    void 늦게_온_사람은_지금_상황_요약을_받는다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        s.join("r", "late");
        assertThat(cap.privates.get("late").get(0).notice()).startsWith("지금 유력 후보는");
        assertThat(cap.privates.get("late").get(0).card()).isNotNull();
    }

    @Test
    void 거를_수_없는_알레르기는_확정_후_본인에게만_알리고_요청할_때만_공개한다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        var nut = new Conditions(Set.of(), Set.of("땅콩"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null);
        s.submit("r", "p0", nut);
        s.submit("r", "p1", Conditions.empty());
        s.submit("r", "p2", Conditions.empty());
        assertThat(cap.lastPrivate("p0").allergyCheck()).isNull(); // 확정 전에는 없음
        s.finish("r");
        assertThat(cap.lastPrivate("p0").allergyCheck()).contains("땅콩").contains("직접 확인");
        assertThat(cap.lastPrivate("p1").allergyCheck()).isNull();    // 다른 사람에겐 안 보임
        assertThat(cap.lastPublic().timeline()).noneMatch(l -> l.contains("땅콩")); // 공용 화면에도 아직 없음

        s.requestAllergyCheck("r", "p0");
        assertThat(cap.lastPublic().timeline()).anyMatch(l -> l.equals("참가자 중 확인이 필요한 알레르기가 있어요: 땅콩"));
        assertThat(cap.lastPrivate("p0").allergyRequested()).isTrue();
    }

    @Test
    void 후보_어디서도_안_파는_메뉴는_본인에게_알려준다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        s.submit("r", "p0", new Conditions(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of("랍스터", "마라탕"), null));
        assertThat(cap.lastPrivate("p0").unmatchedDishes()).containsExactly("랍스터");
    }

    @Test
    void 아홉번째_사람은_들어올_수_없다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 8);
        s.join("r", "p8");
        assertThat(cap.lastPrivate("p8").notice()).contains("꽉 찼어요");
        assertThat(cap.lastPublic().joined()).isEqualTo(8);
    }

    // ---------------------------------------------------------------- AI 중재자
    // 시나리오: p0 = 면·국물 절대 안 됨, p1 = 밥·튀김 절대 안 됨, p2 = 없음 → 후보 2곳(화로 삼겹살, 떡볶이 연구소)
    // 하나씩 풀어 보면 p1의 "밥"이 가장 많이 늘린다(2 → 6). 이건 코드가 센 숫자다.

    private RoomService stuckRoom(Capture cap, FakeAi ai) {
        RoomService s = ready(cap, ai, RoomService.Settings.forTests(), "r", 3);
        s.submit("r", "p0", never("면", "국물"));
        s.submit("r", "p1", never("밥", "튀김"));
        s.submit("r", "p2", Conditions.empty());
        return s;
    }

    @Test
    void 후보가_적으면_가장_많이_막는_조건의_주인에게만_조용히_묻는다() {
        Capture cap = new Capture();
        FakeAi ai = new FakeAi();
        RoomService s = stuckRoom(cap, ai);
        assertThat(liveIds(cap.lastPublic())).hasSize(2);

        Views.Mediator m = cap.lastPrivate("p1").mediator();
        assertThat(m).isNotNull();
        assertThat(m.text()).isEqualTo("‘밥’이 꼭 안 되는 이유가 있을까요? 괜찮다면 후보가 6곳으로 늘어나요 🙂");
        assertThat(m.byAi()).isTrue();
        assertThat(cap.lastPrivate("p0").mediator()).isNull();
        assertThat(cap.lastPrivate("p2").mediator()).isNull();
        // AI가 받은 것은 그 사람의 항목 하나와 숫자뿐
        assertThat(ai.asked).containsExactly(new MediatorAi.Facts("밥", false, 2, 6));
        // 공용 화면에는 누구에게 물었는지 없다
        assertThat(cap.lastPublic().mediating()).isTrue();
        assertThat(cap.lastPublic().timeline()).anyMatch(l -> l.contains("누군가에게 조용히"));
    }

    @Test
    void AI가_숫자를_지어내면_코드_문장으로_바꿔_보낸다() {
        Capture cap = new Capture();
        FakeAi ai = new FakeAi();
        ai.questionText = "밥이 싫으신가요? 풀면 후보가 10곳으로 늘어나요!";
        stuckRoom(cap, ai);
        Views.Mediator m = cap.lastPrivate("p1").mediator();
        assertThat(m.byAi()).isFalse();
        assertThat(m.text()).isEqualTo("‘밥’이(가) 꼭 안 되는 이유가 있을까요? 괜찮다면 후보가 2곳 → 6곳으로 늘어나요");
    }

    @Test
    void AI가_꺼져_있거나_실패해도_기본_문장으로_묻는다() {
        Capture cap = new Capture();
        FakeAi ai = new FakeAi();
        ai.questionText = null;
        stuckRoom(cap, ai);
        assertThat(cap.lastPrivate("p1").mediator().byAi()).isFalse();
        assertThat(cap.lastPrivate("p1").mediator().text()).contains("2곳 → 6곳");
    }

    @Test
    void 괜찮아요를_누르면_바로_반영되고_후보가_늘어난다() {
        Capture cap = new Capture();
        RoomService s = stuckRoom(cap, new FakeAi());
        String qid = cap.lastPrivate("p1").mediator().id();
        s.mediate("r", "p1", new RoomService.MediateMsg(qid, "relax", null));

        assertThat(liveIds(cap.lastPublic())).hasSize(6);
        assertThat(s.room("r").people.get("p1").conditions.preferNot()).contains("밥");
        assertThat(cap.lastPublic().timeline()).anyMatch(l -> l.contains("2곳 → 6곳"));
        assertThat(cap.lastPublic().board().stream().filter(Views.Block::fresh)).hasSize(4); // 새로 생긴 4곳 표시
    }

    @Test
    void 자유_답변은_제안으로_보여주고_적용을_눌러야_반영한다() {
        Capture cap = new Capture();
        FakeAi ai = new FakeAi();
        ai.reply = new MediatorAi.Reply(MediatorAi.Action.RELAX, List.of("국물", "없는태그"), List.of());
        RoomService s = stuckRoom(cap, ai);
        String qid = cap.lastPrivate("p1").mediator().id();

        s.mediate("r", "p1", new RoomService.MediateMsg(qid, "text", "밥은 점심엔 괜찮은데 국물은 좀 별로예요"));
        Views.Mediator m = cap.lastPrivate("p1").mediator();
        assertThat(m.state()).isEqualTo("PROPOSAL");
        assertThat(m.proposal()).containsExactly("‘밥’: 절대 안 됨 → 가능하면 피함", "‘국물’: 가능하면 피함 추가");
        assertThat(m.proposalAfter()).isEqualTo(6);
        assertThat(s.room("r").people.get("p1").conditions.mustNot()).contains("밥"); // 아직 안 바뀜

        s.mediate("r", "p1", new RoomService.MediateMsg(qid, "apply", null));
        Conditions c = s.room("r").people.get("p1").conditions;
        assertThat(c.mustNot()).containsExactly("튀김");
        assertThat(c.preferNot()).containsExactlyInAnyOrder("밥", "국물");
    }

    @Test
    void 아직_안_된다고_답하면_조건은_그대로이고_같은_질문은_다시_안_한다() {
        Capture cap = new Capture();
        FakeAi ai = new FakeAi();
        ai.reply = new MediatorAi.Reply(MediatorAi.Action.KEEP, List.of(), List.of());
        RoomService s = stuckRoom(cap, ai);
        String qid = cap.lastPrivate("p1").mediator().id();
        s.mediate("r", "p1", new RoomService.MediateMsg(qid, "text", "밥은 진짜 안 돼요"));

        assertThat(s.room("r").people.get("p1").conditions.mustNot()).contains("밥");
        assertThat(s.room("r").asked).contains("p1|밥");
        assertThat(ai.asked).extracting(MediatorAi.Facts::item).containsOnlyOnce("밥"); // 다음 질문은 다른 항목
        assertThat(ai.asked).hasSizeGreaterThan(1);
    }

    @Test
    void 남에게_온_질문에는_답할_수_없다() {
        Capture cap = new Capture();
        RoomService s = stuckRoom(cap, new FakeAi());
        String qid = cap.lastPrivate("p1").mediator().id();
        s.mediate("r", "p0", new RoomService.MediateMsg(qid, "relax", null));
        assertThat(s.room("r").people.get("p1").conditions.mustNot()).contains("밥");
        assertThat(liveIds(cap.lastPublic())).hasSize(2);
    }

    @Test
    void 알레르기는_중재_질문_대상이_아니다() {
        Capture cap = new Capture();
        FakeAi ai = new FakeAi();
        var cfg = new RoomService.Settings(0, 15000, 0, 30000, 0.5, 100, 3000); // 늘 "막힘"으로 보게
        RoomService s = ready(cap, ai, cfg, "r", 3);
        s.submit("r", "p0", new Conditions(Set.of("갑각류", "해산물"), Set.of(), Set.of(), Set.of(), null));
        s.submit("r", "p1", Conditions.empty());
        s.submit("r", "p2", Conditions.empty());
        assertThat(ai.asked).isEmpty();
        assertThat(cap.lastPublic().mediating()).isFalse();
    }

    // ---------------------------------------------------------------- 새로고침 · 연결 끊김

    private static long afterGrace() {
        return System.currentTimeMillis() + 31000;
    }

    @Test
    void 새로고침해도_같은_사람이고_제출_상태와_거부권과_카드가_복구된다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, i == 0 ? want("국물") : Conditions.empty());
        int first = cardId(cap, "p0");
        s.react("r", "p0", first, true);
        int now = cardId(cap, "p0");
        s.disconnected("p0", "p0");
        s.join("r", "p0", "새연결");

        assertThat(cap.lastPublic().joined()).isEqualTo(3);
        Views.PrivateView me = cap.lastPrivate("p0");
        assertThat(me.submitted()).isTrue();
        assertThat(me.myConditions().want()).contains("국물");
        assertThat(me.vetoLeft()).isEqualTo(1);
        assertThat(me.card().place().id()).isEqualTo(now);
        assertThat(me.history()).extracting(p -> p.place().id()).containsExactly(first);
    }

    @Test
    void 유예_시간_안에_돌아오면_빠지지_않는다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        s.disconnected("p0", "p0");
        s.sweepAway(System.currentTimeMillis()); // 아직 유예 시간 안
        assertThat(s.room("r").people).containsKey("p0");

        s.join("r", "p0", "새연결");
        s.sweepAway(afterGrace());
        assertThat(s.room("r").people).containsKey("p0");
    }

    @Test
    void 재연결이_먼저_오고_옛_연결_끊김이_늦게_와도_빠지지_않는다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        s.join("r", "p0", "새연결");
        s.disconnected("p0", "p0"); // 옛 연결의 끊김이 뒤늦게 도착
        s.disconnected("p0", "p0"); // 같은 끊김이 두 번 와도 괜찮다
        s.sweepAway(afterGrace());
        assertThat(s.room("r").people).containsKey("p0");
    }

    @Test
    void 제출_전에_떠난_사람은_빠지고_남은_사람이_전원_제출이면_시작된다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 4);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, want("국물"));
        assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.INPUT);

        s.disconnected("p3", "p3");
        s.sweepAway(afterGrace());

        assertThat(cap.lastPublic().joined()).isEqualTo(3);
        assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.NEGOTIATE);
    }

    @Test
    void 제출하고_떠난_사람의_조건은_남고_의견은_기다리지_않는다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        s.submit("r", "p0", new Conditions(Set.of("갑각류"), Set.of(), Set.of(), Set.of(), null));
        s.submit("r", "p1", want("국물"));
        s.submit("r", "p2", want("국물"));

        s.disconnected("p0", "p0");
        s.sweepAway(afterGrace());

        assertThat(s.room("r").people).containsKey("p0");
        assertThat(s.recomputeFresh("r").ranking()).extracting(x -> x.restaurant().tags())
                .noneMatch(tags -> tags.contains("갑각류")); // 알레르기는 계속 지킨다
        assertThat(cap.lastPublic().joined()).isEqualTo(3);
        assertThat(cap.lastPublic().active()).isEqualTo(2);
    }

    @Test
    void 남은_사람이_모두_좋아요면_떠난_사람을_기다리지_않고_확정된다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, want("국물"));
        s.disconnected("p0", "p0");
        s.sweepAway(afterGrace());
        int top = cardId(cap, "p1");
        s.react("r", "p1", top, true);
        s.react("r", "p2", top, true);
        assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.DONE);
    }

    @Test
    void 떠났던_사람이_돌아오면_다시_의견_대상이_된다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, want("국물"));
        s.disconnected("p0", "p0");
        s.sweepAway(afterGrace());
        assertThat(cap.lastPublic().active()).isEqualTo(2);

        s.join("r", "p0", "새연결");
        assertThat(cap.lastPublic().active()).isEqualTo(3);
        assertThat(cap.lastPrivate("p0").submitted()).isTrue();
    }
}
