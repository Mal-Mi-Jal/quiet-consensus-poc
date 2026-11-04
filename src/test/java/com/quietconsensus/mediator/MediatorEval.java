package com.quietconsensus.mediator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 실제 Claude Haiku로 AI 중재자를 잰다. 유료라서 평소에는 꺼져 있다.
 * 실행: (환경변수 ANTHROPIC_API_KEY 설정 후) ./gradlew test --tests "*MediatorEval" -Dclaude=true
 * 호출 수 = 질문 4 + 답 해석 10 = 14번. 점수만 출력하고 실패로 처리하지 않는다 (측정용).
 */
@EnabledIfSystemProperty(named = "claude", matches = "true")
class MediatorEval {

    @Test
    void 평가() throws Exception {
        String key = System.getenv("ANTHROPIC_API_KEY");
        ClaudeMediatorAi ai = new ClaudeMediatorAi(key == null ? "" : key, "claude-haiku-5-5");
        if (!ai.enabled()) throw new IllegalStateException("ANTHROPIC_API_KEY가 없습니다");

        System.out.println("\n[질문 문장] 통과 = 코드가 준 숫자만 · 140자 이내 · 알레르기 언급 없음");
        int qPass = 0;
        for (MediatorCases.Q q : MediatorCases.QUESTIONS) {
            MediatorAi.Facts f = new MediatorAi.Facts(q.item(), q.dish(), q.before(), q.after());
            long t0 = System.currentTimeMillis();
            String text;
            try {
                text = ai.question(f).get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                text = null;
            }
            boolean ok = f.accepts(text);
            if (ok) qPass++;
            System.out.printf("  %s %s %d→%d (%d자, %dms): %s%n", ok ? "✅" : "❌", q.item(), q.before(), q.after(),
                    text == null ? 0 : text.length(), System.currentTimeMillis() - t0, text);
        }

        System.out.println("\n[답 해석] 정답 action + 꼭 나와야 할 태그");
        int rPass = 0;
        for (MediatorCases.R r : MediatorCases.REPLIES) {
            MediatorAi.Facts f = new MediatorAi.Facts(r.item(), false, 2, 6);
            MediatorAi.Reply got;
            try {
                got = ai.interpret(f, r.answer()).get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                got = null;
            }
            boolean actionOk = got != null && (got.action() == r.expect() || r.acceptAlso().contains(got.action()));
            boolean tagsOk = got != null && got.avoid().containsAll(r.avoid()) && got.want().containsAll(r.want());
            boolean ok = actionOk && tagsOk;
            if (ok) rPass++;
            System.out.printf("  %s %-10s \"%s\" → %s (정답 %s%s)%n", ok ? "✅" : "❌", r.id(), r.answer(),
                    got == null ? "실패" : got.action() + " avoid" + got.avoid() + " want" + got.want(),
                    r.expect(), r.acceptAlso().isEmpty() ? "" : " 또는 " + r.acceptAlso());
        }
        System.out.printf("%n[요약] 질문 %d/%d · 답 해석 %d/%d · 모델 claude-haiku-5-5%n",
                qPass, MediatorCases.QUESTIONS.size(), rPass, MediatorCases.REPLIES.size());
    }

    @SuppressWarnings("unused")
    private static final List<String> NOTE = List.of("평가셋은 MediatorCases — 결과를 본 뒤 프롬프트를 고치면 새 문장으로 다시 잰다");
}
