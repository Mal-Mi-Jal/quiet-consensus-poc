package com.quietconsensus.room;

import com.quietconsensus.engine.Conditions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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

    private static Conditions want(String... tags) {
        return new Conditions(Set.of(), Set.of(), Set.of(), Set.of(tags), null);
    }

    private static Conditions preferNot(String... tags) {
        return new Conditions(Set.of(), Set.of(), Set.of(tags), Set.of(), null);
    }

    private RoomService ready(Capture cap, String room, int n) {
        RoomService s = new RoomService(cap, 0); // 타이머 없이 closeRound를 직접 부른다
        for (int i = 0; i < n; i++) s.join(room, "p" + i);
        return s;
    }

    @Test
    void 입력_단계에서는_후보를_보여주지_않고_전원_제출해야_공개된다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 4);
        s.submit("r", "p0", want("고기"));
        s.submit("r", "p1", preferNot("고기"));
        s.submit("r", "p2", want("국물"));
        assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.INPUT);
        assertThat(cap.lastPublic().top()).isNull();
        assertThat(cap.lastPublic().submitted()).isEqualTo(3);

        s.submit("r", "p3", Conditions.empty());
        assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.NEGOTIATE);
        assertThat(cap.lastPublic().top()).isNotNull();
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
    }

    @Test
    void 라운드_중_반응은_숫자만_보이고_라운드가_끝날_때_반영된다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 4);
        s.submit("r", "p0", want("한식", "고기"));
        s.submit("r", "p1", new Conditions(Set.of(), Set.of(), Set.of("고기"), Set.of("국물"), null));
        s.submit("r", "p2", Conditions.empty());
        s.submit("r", "p3", Conditions.empty());
        Views.PublicView first = cap.lastPublic();
        assertThat(first.round()).isEqualTo(1);
        assertThat(first.top().name()).isEqualTo("진국 칼국수");

        s.react("r", "p0", first.top().id(), false); // 부장님 👎 — 반영되면 1순위가 바뀌는 반응
        Views.PublicView during = cap.lastPublic();
        assertThat(during.reactedCount()).isEqualTo(1);              // "반응 1/4"만 갱신
        assertThat(during.top().name()).isEqualTo("진국 칼국수");     // 1순위는 그대로
        assertThat(s.room("r").dislikes).isEmpty();                   // 아직 계산에 안 들어감

        s.closeRound("r");
        Views.PublicView after = cap.lastPublic();
        assertThat(after.round()).isEqualTo(2);
        assertThat(after.reactedCount()).isZero();
        assertThat(after.top().name()).isEqualTo("비빔밥 정원");      // 라운드가 끝나는 순간 한 번에 바뀜
    }

    @Test
    void 전원이_반응하면_시간이_남아도_라운드가_바로_끝난다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        int top = cap.lastPublic().top().id();
        s.react("r", "p0", top, true);
        s.react("r", "p1", top, true);
        assertThat(cap.lastPublic().round()).isEqualTo(1);
        s.react("r", "p2", top, false);
        assertThat(cap.lastPublic().round()).isEqualTo(2);
    }

    @Test
    void 라운드_안에서는_마지막_반응만_남는다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        int top = cap.lastPublic().top().id();
        s.react("r", "p0", top, false);
        s.react("r", "p0", top, true); // 마음을 바꿈
        assertThat(cap.lastPublic().reactedCount()).isEqualTo(1); // 같은 사람은 한 번만 센다
        s.closeRound("r");
        assertThat(s.room("r").likes).containsEntry("p0", top);
        assertThat(s.room("r").dislikes.get("p0")).doesNotContain(top);
    }

    @Test
    void 거부권은_기다리지_않고_라운드를_바로_닫는다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 4);
        for (int i = 0; i < 4; i++) s.submit("r", "p" + i, Conditions.empty());
        int top = cap.lastPublic().top().id();
        s.react("r", "p0", top, true);
        s.veto("r", "p1", top);
        Views.PublicView v = cap.lastPublic();
        assertThat(v.round()).isEqualTo(2);
        assertThat(v.top().id()).isNotEqualTo(top);
        assertThat(s.room("r").likes).containsEntry("p0", top); // 같은 라운드의 반응도 함께 반영
    }

    @Test
    void 시간이_다_되면_라운드가_저절로_끝난다() throws Exception {
        Capture cap = new Capture();
        RoomService s = new RoomService(cap, 300); // 0.3초 라운드 + 진짜 타이머
        try {
            for (int i = 0; i < 3; i++) s.join("r", "p" + i);
            for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
            assertThat(cap.lastPublic().round()).isEqualTo(1);
            assertThat(cap.lastPublic().roundRemainingMs()).isBetween(1L, 300L);
            Thread.sleep(900);
            assertThat(cap.lastPublic().round()).isGreaterThanOrEqualTo(2);
            assertThat(cap.lastPublic().timeline()).anyMatch(l -> l.contains("반응 없음"));
        } finally {
            s.stop();
        }
    }

    @Test
    void 같은_후보에_동시에_거부권을_쓰면_두번째는_돌려준다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 4);
        for (int i = 0; i < 4; i++) s.submit("r", "p" + i, Conditions.empty());
        int top = cap.lastPublic().top().id();
        s.veto("r", "p1", top);
        s.veto("r", "p2", top);
        assertThat(cap.lastPrivate("p1").vetoLeft()).isZero();
        assertThat(cap.lastPrivate("p2").vetoLeft()).isEqualTo(1);
        assertThat(cap.lastPrivate("p2").notice()).contains("돌려드렸어요");
        assertThat(cap.lastPublic().top().id()).isNotEqualTo(top);
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
                        switch (rnd.nextInt(4)) {
                            case 0 -> s.react("r", key, id, rnd.nextBoolean());
                            case 1 -> s.veto("r", key, id);
                            case 2 -> s.submit("r", key, rnd.nextBoolean() ? want(tags[rnd.nextInt(tags.length)])
                                    : preferNot(tags[rnd.nextInt(tags.length)]));
                            default -> s.react("r", key, id, true);
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
                return null;
            });
        }
        pool.submit(() -> { // 누르는 동안 다른 스레드가 계속 라운드를 닫는다 (타이머 역할)
            go.await();
            while (running.get()) {
                s.closeRound("r");
                Thread.sleep(2); // 빈 라운드도 방송되므로, 쉬지 않고 닫으면 기록이 끝없이 쌓인다
            }
            return null;
        });
        go.countDown();
        Thread.sleep(1500);
        running.set(false);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        s.closeRound("r");

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
        assertThat(used).isLessThanOrEqualTo(8);
        System.out.printf("%n[동시성] 8명 × 300번 누르기 + 동시 라운드 닫기 → 방송 %d회, 라운드 %d개, 버전 단조 증가 ✅, 거부권 사용 %d장 = 제외 %d곳 ✅%n",
                versions.size(), room.round, used, room.vetoed.size());
    }

    @Test
    void 늦게_온_사람은_지금_상황_요약을_받는다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        s.join("r", "late");
        assertThat(cap.privates.get("late").get(0).notice()).startsWith("지금 1순위는");
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

    // ---------------------------------------------------------------- 새로고침 · 연결 끊김

    private static long afterGrace() {
        return System.currentTimeMillis() + 31000;
    }

    @Test
    void 새로고침해도_같은_사람이고_제출_상태와_거부권이_복구된다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        s.submit("r", "p0", want("국물"));
        s.disconnected("p0", "p0");
        s.join("r", "p0", "새연결");

        assertThat(cap.lastPublic().joined()).isEqualTo(3);
        assertThat(cap.lastPublic().submitted()).isEqualTo(1);
        Views.PrivateView me = cap.lastPrivate("p0");
        assertThat(me.submitted()).isTrue();
        assertThat(me.myConditions().want()).contains("국물");
        assertThat(me.vetoLeft()).isEqualTo(1);
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
    void 제출하고_떠난_사람의_조건은_남고_반응은_기다리지_않는다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        s.submit("r", "p0", new Conditions(Set.of("갑각류"), Set.of(), Set.of(), Set.of(), null));
        s.submit("r", "p1", want("국물"));
        s.submit("r", "p2", want("국물"));
        int top = cap.lastPublic().top().id();
        s.react("r", "p1", top, true);
        s.react("r", "p2", top, false);
        int roundBefore = cap.lastPublic().round();

        s.disconnected("p0", "p0");
        s.sweepAway(afterGrace());

        assertThat(s.room("r").people).containsKey("p0");
        assertThat(s.recomputeFresh("r").ranking()).extracting(x -> x.restaurant().tags())
                .noneMatch(tags -> tags.contains("갑각류")); // 알레르기는 계속 지킨다
        assertThat(cap.lastPublic().joined()).isEqualTo(3);
        assertThat(cap.lastPublic().active()).isEqualTo(2);
        assertThat(cap.lastPublic().round()).isGreaterThan(roundBefore); // 남은 둘이 다 반응했으니 라운드가 닫힌다
    }

    @Test
    void 남은_사람이_모두_좋아요면_떠난_사람을_기다리지_않고_확정된다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, want("국물"));
        s.disconnected("p0", "p0");
        s.sweepAway(afterGrace());
        int top = cap.lastPublic().top().id();
        s.react("r", "p1", top, true);
        s.react("r", "p2", top, true);
        assertThat(cap.lastPublic().phase()).isEqualTo(Views.Phase.DONE);
    }

    @Test
    void 떠났던_사람이_돌아오면_다시_반응_대상이_된다() {
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
