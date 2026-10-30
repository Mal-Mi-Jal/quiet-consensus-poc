package com.quietconsensus.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** LLM 없이 도는 코드 규칙 (평가 문장과 겹치지 않는 예시로 확인) */
class TextRulesTest {

    @Test
    void 알레르기_문장을_키워드로_찾는다() {
        assertThat(TextRules.allergyHints("호두 알레르기가 있어요")).containsExactly("호두");
        assertThat(TextRules.allergyHints("메밀 알러지 있음")).containsExactly("메밀");
        assertThat(TextRules.allergyHints("키위를 먹으면 가렵더라고요")).containsExactly("키위");
        assertThat(TextRules.allergyHints("국밥 먹고 싶어요")).isEmpty();
    }

    @Test
    void 아는_메뉴는_긴_이름부터_찾는다() {
        assertThat(TextRules.knownDishes("카레우동 먹고 싶다")).containsExactly("카레우동");
        assertThat(TextRules.knownDishes("분짜랑 월남쌈")).containsExactlyInAnyOrder("분짜", "월남쌈");
        assertThat(TextRules.knownDishes("아무거나 좋아요")).isEmpty();
    }

    @Test
    void 단서로_좋고_싫음을_정한다() {
        assertThat(TextRules.attitudeByCue("순대는 절대 안 돼")).isEqualTo("never");
        assertThat(TextRules.attitudeByCue("포케는 좀 별로")).isEqualTo("avoid");
        assertThat(TextRules.attitudeByCue("수제비 좋다")).isEqualTo("want");
    }

    @Test
    void 메뉴를_파는_가게의_태그만_그럴듯한_태그로_본다() {
        assertThat(TextRules.tagsOfShopsServing(java.util.List.of("마라탕"))).doesNotContain("분식").contains("중식");
    }
}
