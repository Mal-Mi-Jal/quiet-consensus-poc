package com.quietconsensus;

import com.quietconsensus.engine.Conditions;
import com.quietconsensus.engine.Engine;
import com.quietconsensus.engine.MockRestaurants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EngineTest {

    private static Conditions c(Set<String> allergy, Set<String> mustNot, Set<String> preferNot, Set<String> want, Integer price) {
        return new Conditions(allergy, mustNot, preferNot, want, price);
    }

    private static Engine.Result rank(Map<String, Conditions> people) {
        return Engine.rank(MockRestaurants.ALL, people, Map.of(), Set.of(), MockRestaurants.BASE_WALK);
    }

    @Test
    void 알레르기와_절대안됨은_후보에서_빠진다() {
        var r = rank(Map.of(
                "a", c(Set.of("갑각류"), Set.of(), Set.of(), Set.of(), null),
                "b", c(Set.of(), Set.of("중식"), Set.of(), Set.of(), null),
                "c", Conditions.empty()));
        assertThat(r.ranking()).noneMatch(s -> s.restaurant().tags().contains("갑각류"));
        assertThat(r.ranking()).noneMatch(s -> s.restaurant().tags().contains("중식"));
    }

    @Test
    void 절대안됨이_2개를_넘으면_나머지는_가능하면피함으로_내려간다() {
        var cond = c(Set.of(), new java.util.LinkedHashSet<>(List.of("한식", "중식", "일식", "양식")), Set.of(), Set.of(), null);
        assertThat(cond.mustNot()).containsExactly("한식", "중식");
        assertThat(cond.preferNot()).containsExactlyInAnyOrder("일식", "양식");
    }

    @Test
    void 공정성_기준은_가장_덜_만족한_사람을_먼저_본다() {
        // 부장님은 한식 고기, a는 고기를 피하고 국물을 원하고, b는 매운 걸 피하고 싶다
        Map<String, Conditions> people = Map.of(
                "boss", c(Set.of(), Set.of(), Set.of(), Set.of("한식", "고기"), null),
                "a", c(Set.of(), Set.of(), Set.of("고기"), Set.of("국물"), null),
                "b", c(Set.of(), Set.of(), Set.of("매운음식"), Set.of(), null));
        var ranking = rank(people).ranking();
        var bossPick = ranking.stream().max(java.util.Comparator.comparingDouble(s -> s.satisfaction().get("boss"))).orElseThrow();
        // 부장님 기준으로 고르면 화로 삼겹살 → a는 0%
        assertThat(bossPick.restaurant().name()).isEqualTo("화로 삼겹살");
        assertThat(bossPick.min()).isZero();
        // 공정 기준은 진국 칼국수 → 부장님 50%, a 100%, b 100% → 가장 덜 만족한 사람도 50%
        assertThat(ranking.get(0).restaurant().name()).isEqualTo("진국 칼국수");
        assertThat(ranking.get(0).min()).isEqualTo(0.5);
    }

    @Test
    void 거리부터_완화하고_그래도_없으면_conflict() {
        // 샐러드 볼(11분)만 원하는 조건 → 10분 기준엔 없음 → 거리 +5분 완화로 찾는다
        var onlySalad = c(Set.of(), Set.of("한식", "중식"), Set.of(), Set.of(), null);
        var r1 = rank(Map.of("a", onlySalad,
                "b", c(Set.of(), Set.of("일식", "분식"), Set.of(), Set.of(), null),
                "c", c(Set.of(), Set.of("아시안", "고기"), Set.of(), Set.of(), null),
                "d", c(Set.of(), Set.of("면"), Set.of(), Set.of(), null)));
        assertThat(r1.top().restaurant().name()).isEqualTo("샐러드 볼");
        assertThat(r1.relaxNotes()).containsExactly("거리 기준을 5분 넓혔어요");

        // 한식·중식·일식·분식·양식이 다 막히면 남는 건 쌀국수(11000원)뿐인데, 예산 9000원 +10%로도 안 됨 → conflict
        var r2 = rank(Map.of("a", c(Set.of(), Set.of("중식", "일식"), Set.of(), Set.of(), 9000),
                "b", c(Set.of(), Set.of("분식", "양식"), Set.of(), Set.of(), null),
                "c", c(Set.of(), Set.of("한식"), Set.of(), Set.of(), null)));
        assertThat(r2.conflict()).isTrue();
        assertThat(r2.ranking()).isEmpty();
    }

    @Test
    void 참가자_순서가_달라도_결과는_같다() {
        Random rnd = new Random(7);
        Map<String, Conditions> base = new LinkedHashMap<>();
        base.put("p1", c(Set.of(), Set.of("매운음식"), Set.of("고기"), Set.of("국물"), null));
        base.put("p2", c(Set.of(), Set.of(), Set.of("면"), Set.of("밥"), 12000));
        base.put("p3", c(Set.of("갑각류"), Set.of(), Set.of(), Set.of("한식"), null));
        base.put("p4", c(Set.of(), Set.of("양식"), Set.of("튀김"), Set.of(), null));
        List<Integer> expected = rank(base).ranking().stream().map(s -> s.restaurant().id()).toList();
        List<String> keys = new ArrayList<>(base.keySet());
        for (int i = 0; i < 500; i++) {
            Collections.shuffle(keys, rnd);
            Map<String, Conditions> shuffled = new LinkedHashMap<>();
            keys.forEach(k -> shuffled.put(k, base.get(k)));
            assertThat(rank(shuffled).ranking().stream().map(s -> s.restaurant().id()).toList()).isEqualTo(expected);
        }
    }

    @Test
    void 프롬프트로는_가중치를_바꿀_수_없다() {
        // Conditions에는 "우선순위"나 "가중치" 칸이 없다 → LLM이 무엇을 출력해도 한 사람 = 한 표
        assertThat(Conditions.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("allergy", "allergyOther", "mustNot", "preferNot", "want",
                        "dishNever", "dishAvoid", "dishWant", "maxPrice");
    }

    private static Conditions dishes(Set<String> never, Set<String> avoid, Set<String> want) {
        return new Conditions(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), never, avoid, want, null);
    }

    @Test
    void 목록에_없는_메뉴는_식당_메뉴에서_찾는다() {
        var curry = MockRestaurants.byId(14);   // 카레 하우스
        var cutlet = MockRestaurants.byId(3);   // 돈가스 상회 (카레돈가스)
        var gimbap = MockRestaurants.byId(8);   // 김밥 천사 (분식)
        var c = dishes(Set.of(), Set.of("카레"), Set.of());
        assertThat(Engine.satisfaction(curry, c, Set.of())).isZero();
        assertThat(Engine.satisfaction(cutlet, c, Set.of())).isZero();   // "카레" ⊂ "카레돈가스"
        assertThat(Engine.satisfaction(gimbap, c, Set.of())).isEqualTo(1.0); // 예전처럼 분식이 감점되지 않는다
        // 동의어: "커리"도 카레로 본다
        assertThat(Engine.satisfaction(curry, dishes(Set.of(), Set.of("커리"), Set.of()), Set.of())).isZero();
    }

    @Test
    void 절대_안_되는_메뉴는_후보에서_빠지고_못_찾은_메뉴는_알려준다() {
        var r = rank(Map.of("a", dishes(Set.of("짜장면"), Set.of(), Set.of("랍스터")),
                "b", Conditions.empty(), "c", Conditions.empty()));
        assertThat(r.ranking()).noneMatch(s -> s.restaurant().name().equals("짬뽕 대가"));
        assertThat(Engine.unmatchedDishes(dishes(Set.of("짜장면"), Set.of(), Set.of("랍스터")), MockRestaurants.ALL))
                .containsExactly("랍스터");
    }

    @Test
    void 절대_안_됨은_태그와_메뉴를_합쳐_2개까지() {
        var c = new Conditions(Set.of(), Set.of(), Set.of("중식"), Set.of(), Set.of(),
                new java.util.LinkedHashSet<>(List.of("카레", "마라탕")), Set.of(), Set.of(), null);
        assertThat(c.mustNot()).containsExactly("중식");
        assertThat(c.dishNever()).containsExactly("카레");
        assertThat(c.dishAvoid()).containsExactly("마라탕");
    }

    @Test
    void 거를_수_없는_알레르기는_후보를_빼지_않는다() {
        var nut = new Conditions(Set.of(), Set.of("땅콩"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null);
        var withNut = rank(Map.of("a", nut, "b", Conditions.empty(), "c", Conditions.empty()));
        var without = rank(Map.of("a", Conditions.empty(), "b", Conditions.empty(), "c", Conditions.empty()));
        assertThat(withNut.ranking()).hasSameSizeAs(without.ranking());
    }
}
