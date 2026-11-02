package com.quietconsensus.room;

import com.quietconsensus.engine.Conditions;

import java.util.List;

/**
 * 밖으로 나가는 메시지 모양.
 * PublicView 는 방 전체 채널로, PrivateView 는 그 사람 연결로만 간다.
 * PublicView 에는 누구의 조건도, 사람별 만족도도 넣지 않는다 — 화면에서 가리는 게 아니라 아예 보내지 않는다.
 */
public final class Views {

    public enum Phase { INPUT, NEGOTIATE, DONE }

    public record Place(int id, String name, List<String> tags, int price, int walkMin) {
    }

    /**
     * round·roundRemainingMs·reactedCount: 라운드 번호, 남은 시간(보낸 순간 기준), 이번 라운드에 반응한 사람 수(누군지는 없음)
     * active: 반응을 기다리는 사람 수 (제출하고 떠난 사람은 joined에는 있고 여기에는 없다)
     */
    public record PublicView(long version, Phase phase, int joined, int submitted,
                             Place top, List<Place> nextUp, Integer minSatisfactionPercent,
                             int remaining, int likesOnTop, List<String> timeline, String notice,
                             int round, long roundRemainingMs, int reactedCount, int active) {
    }

    /**
     * unmatchedDishes: 내가 적은 메뉴 중 후보 어디서도 안 파는 것 (조용히 무시하지 않고 알려 준다)
     * allergyCheck: 확정 후, 이 앱이 거를 수 없는 내 알레르기에 대한 "직접 확인하세요" 문구 (없으면 null)
     * allergyRequested: 익명 확인 요청을 이미 보냈는지
     * submitted: 이미 제출했는지 (새로고침한 화면이 입력 칸을 다시 띄우지 않게)
     */
    public record PrivateView(long version, Phase phase, Conditions myConditions,
                              Integer mySatisfied, Integer myTotal, int vetoLeft,
                              List<String> relaxable, String notice,
                              List<String> unmatchedDishes, String allergyCheck, boolean allergyRequested,
                              boolean submitted) {
    }

    /**
     * 평문 → 조건 변환 결과. 원문은 담지 않는다.
     * allergyHints: 알레르기 같은 말이 보였다는 신호. 조건에는 넣지 않고 "칩에서 직접 골라 주세요" 안내에만 쓴다
     */
    public record Parsed(Conditions conditions, List<String> allergyHints, List<String> ignored, boolean invalid, long millis) {
    }

    private Views() {
    }
}
