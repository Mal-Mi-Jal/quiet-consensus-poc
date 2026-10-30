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
        RoomService s = new RoomService(cap, 0); // 스케줄러 없이 flush를 직접 부른다
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
    void 조율_단계_반응은_flush_전까지_방송되지_않는다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 3);
        for (int i = 0; i < 3; i++) s.submit("r", "p" + i, Conditions.empty());
        int before = cap.publics.size();
        int top = cap.lastPublic().top().id();
        s.react("r", "p0", top, false);
        s.react("r", "p1", top, true);
        assertThat(cap.publics).hasSize(before); // 누른 순간에는 아무것도 안 나간다
        s.flush("r");
        assertThat(cap.publics).hasSize(before + 1); // 묶어서 한 번에
    }

    @Test
    void 같은_후보에_동시에_거부권을_쓰면_두번째는_돌려준다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 4);
        for (int i = 0; i < 4; i++) s.submit("r", "p" + i, Conditions.empty());
        int top = cap.lastPublic().top().id();
        s.veto("r", "p1", top);
        s.veto("r", "p2", top);
        s.flush("r");
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
        pool.submit(() -> { // 누르는 동안 다른 스레드가 계속 flush
            go.await();
            while (running.get()) s.flush("r");
            return null;
        });
        go.countDown();
        Thread.sleep(1500);
        running.set(false);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        s.flush("r");

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
        System.out.printf("%n[동시성] 8명 × 300번 누르기 + 동시 flush → 방송 %d회, 버전 단조 증가 ✅, 거부권 사용 %d장 = 제외 %d곳 ✅%n",
                versions.size(), used, room.vetoed.size());
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
    void 아홉번째_사람은_들어올_수_없다() {
        Capture cap = new Capture();
        RoomService s = ready(cap, "r", 8);
        s.join("r", "p8");
        assertThat(cap.lastPrivate("p8").notice()).contains("꽉 찼어요");
        assertThat(cap.lastPublic().joined()).isEqualTo(8);
    }
}
