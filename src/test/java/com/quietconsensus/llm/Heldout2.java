package com.quietconsensus.llm;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 새 평가 문장 (HELDOUT2). 메뉴 키워드·알레르기 작업을 시작하기 "전에" 작성해 커밋했다.
 * 고치는 동안 이 문장들의 결과는 보지 않고, 마지막에 한 번만 잰다.
 *
 * dishes: 목록(태그)에 없는 메뉴 이름 → "neg"(싫음/빼 줘) 또는 "want"(먹고 싶음)
 * allergyHint: 알레르기 문장이면 true — 새 설계에서는 LLM이 알레르기를 채우지 않고 "칩에서 고르세요" 안내만 한다
 */
final class Heldout2 {

    record Case(String text, Set<String> negTags, Set<String> wantTags, int price,
                Map<String, String> dishes, boolean allergyHint, String kind) {
    }

    static final Set<String> N = Set.of();

    static final List<Case> CASES = List.of(
            new Case("카레는 싫어요", N, N, 0, Map.of("카레", "neg"), false, "메뉴 싫음"),
            new Case("돈가스는 진짜 질렸어", N, N, 0, Map.of("돈가스", "neg"), false, "메뉴 싫음"),
            new Case("짜장면은 빼 주세요", N, N, 0, Map.of("짜장면", "neg"), false, "메뉴 싫음"),
            new Case("부대찌개 절대 안 돼요", N, N, 0, Map.of("부대찌개", "neg"), false, "메뉴 싫음"),
            new Case("마라탕 먹고 싶다", N, N, 0, Map.of("마라탕", "want"), false, "메뉴 원함"),
            new Case("오늘은 쌀국수 땡겨요", N, N, 0, Map.of("쌀국수", "want"), false, "메뉴 원함"),
            new Case("김치찌개 먹고 싶어요", N, N, 0, Map.of("김치찌개", "want"), false, "메뉴 원함"),
            new Case("샐러드 같은 가벼운 거", N, N, 0, Map.of("샐러드", "want"), false, "메뉴 원함"),
            new Case("갈비탕 먹고 싶은데 만오천원 안으로", N, N, 15000, Map.of("갈비탕", "want"), false, "메뉴+예산"),
            new Case("1만원 이하면 다 좋아요", N, N, 10000, Map.of(), false, "예산"),
            new Case("오늘은 아무거나 괜찮아요, 진짜로", N, N, 0, Map.of(), false, "빈 입력"),
            new Case("견과류 알레르기가 있어서요", N, N, 0, Map.of(), true, "알레르기"),
            new Case("새우 알레르기 있어요", N, N, 0, Map.of(), true, "알레르기"),
            new Case("계란 알레르기 있습니다", N, N, 0, Map.of(), true, "알레르기"));

    private Heldout2() {
    }
}
