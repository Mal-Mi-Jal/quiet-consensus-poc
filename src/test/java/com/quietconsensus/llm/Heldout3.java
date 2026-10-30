package com.quietconsensus.llm;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 새 평가 문장 (HELDOUT3). v5(코드 규칙: 알레르기 감지 + 아는 메뉴 사전) 작업 "전에" 작성해 커밋했다.
 * HELDOUT2는 v4 결과를 자세히 본 뒤라 더 이상 깨끗한 시험지가 아니다.
 */
final class Heldout3 {

    static final Set<String> N = Set.of();

    static final List<Heldout2.Case> CASES = List.of(
            new Heldout2.Case("오므라이스 먹고 싶어요", N, N, 0, Map.of("오므라이스", "want"), false, "메뉴 원함"),
            new Heldout2.Case("떡볶이는 오늘 별로예요", N, N, 0, Map.of("떡볶이", "neg"), false, "메뉴 싫음"),
            new Heldout2.Case("보쌈 땡긴다", N, N, 0, Map.of("보쌈", "want"), false, "메뉴 원함"),
            new Heldout2.Case("초밥은 절대 싫어", N, N, 0, Map.of("초밥", "neg"), false, "메뉴 싫음"),
            new Heldout2.Case("버거 같은 거 말고", N, N, 0, Map.of("버거", "neg"), false, "메뉴 싫음"),
            new Heldout2.Case("탕수육 먹고 싶다", N, N, 0, Map.of("탕수육", "want"), false, "메뉴 원함"),
            new Heldout2.Case("리조또 좋아요", N, N, 0, Map.of("리조또", "want"), false, "메뉴 원함"),
            new Heldout2.Case("라멘 먹고 싶어", N, N, 0, Map.of("라멘", "want"), false, "없는 메뉴"),
            new Heldout2.Case("밀가루 알레르기 있어요", N, N, 0, Map.of(), true, "알레르기"),
            new Heldout2.Case("복숭아 먹으면 두드러기 나요", N, N, 0, Map.of(), true, "알레르기"),
            new Heldout2.Case("고등어 알러지 있어요", N, N, 0, Map.of(), true, "알레르기"),
            new Heldout2.Case("점심 2시에 먹어요", N, N, 0, Map.of(), false, "관계없음"));

    private Heldout3() {
    }
}
