package com.quietconsensus.mediator;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * AI 중재자의 "말" 부분. 구현을 바꿔 끼울 수 있다 (Claude Haiku / 테스트용 가짜).
 * 받는 것은 그 사람 본인의 조건 하나와 코드가 계산한 숫자뿐이다 → 다른 사람의 조건은 프롬프트에 들어갈 수 없다.
 * 실패해도 서비스는 멈추지 않는다: 질문은 코드 기본 문장으로, 답 해석은 버튼으로 대신한다.
 */
public interface MediatorAi {

    /** 프롬프트에 들어가는 전부 */
    record Facts(String item, boolean dish, int before, int after) {

        /** AI가 없거나 실패했을 때, 또는 AI 문장이 숫자를 틀렸을 때 쓰는 코드 문장 */
        public String defaultQuestion() {
            return "‘" + item + "’이(가) 꼭 안 되는 이유가 있을까요? 괜찮다면 후보가 "
                    + before + "곳 → " + after + "곳으로 늘어나요";
        }

        /** AI 문장 검사: 코드가 센 숫자가 그대로 들어 있어야 하고, 다른 숫자는 없어야 하고, 너무 길면 안 된다 */
        public boolean accepts(String text) {
            if (text == null || text.isBlank() || text.length() > 140
                    || !text.contains(after + "곳") || text.contains("알레르기")) return false;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(text);
            while (m.find()) {
                String n = m.group();
                if (!n.equals(String.valueOf(before)) && !n.equals(String.valueOf(after))) return false;
            }
            return true;
        }
    }

    /**
     * OK: "괜찮아요/상관없어요" → 그 "절대 안 됨"을 아예 지운다
     * SOFTEN: "웬만하면 피하고 싶지만 양보" → "가능하면 피함"으로 내린다
     * KEEP: 여전히 안 된다 (알레르기·건강 이야기 포함) · UNCLEAR: 판단 불가 → 버튼으로 다시 고르게
     */
    enum Action { OK, SOFTEN, KEEP, UNCLEAR }

    /**
     * 자유 답변 해석 결과. 조건은 "완화" 방향으로만 바꿀 수 있다:
     * action=OK → 그 항목을 지운다, SOFTEN → "절대 안 됨"에서 "가능하면 피함"으로,
     * avoid/want → 태그 목록 안에서만 "가능하면 피함"/"먹고 싶음" 추가. "절대 안 됨"을 새로 만들 수는 없다.
     */
    record Reply(Action action, List<String> avoid, List<String> want) {
    }

    CompletableFuture<String> question(Facts facts);

    CompletableFuture<Reply> interpret(Facts facts, String answer);

    /** 화면에 "🤖" 표시를 붙일지 (키가 없으면 코드 문장만 나간다) */
    boolean enabled();
}
