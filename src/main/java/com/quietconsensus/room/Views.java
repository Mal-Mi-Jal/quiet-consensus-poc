package com.quietconsensus.room;

import com.quietconsensus.engine.Conditions;

import java.util.List;

/**
 * 밖으로 나가는 메시지 모양.
 * PublicView 는 방 전체 채널로, PrivateView 는 그 사람 연결로만 간다.
 * PublicView 에는 누구의 조건도, 사람별 만족도도, 누가 무엇을 눌렀는지도 넣지 않는다 — 화면에서 가리는 게 아니라 아예 보내지 않는다.
 */
public final class Views {

    public enum Phase { INPUT, NEGOTIATE, DONE }

    public record Place(int id, String name, List<String> tags, int price, int walkMin) {
    }

    /**
     * 공용 화면의 후보 블록 하나.
     * minPercent: 이 후보에서 가장 덜 만족한 사람의 만족도 (빠진 후보는 null)
     * qualified: minPercent가 하한(floor) 이상이라 최종 후보 자격이 있는지
     * out: 거부권 등으로 빠진 후보 (지워지지 않고 회색으로 남는다)
     * fresh: 방금 새로 생긴 후보 (중재로 조건이 풀렸을 때 등)
     */
    public record Block(Place place, int likes, Integer minPercent, boolean qualified, boolean out, boolean fresh) {
    }

    /**
     * board: 지금 후보(공정 순서) + 빠진 후보. 공용 화면은 이 보드를 통째로 보여 주고 "한 장씩 넘기지" 않는다
     * leaderId: 지금 규칙으로 정하면 뽑힐 후보, undecidedOnLeader: 그 후보에 아직 의견을 안 낸 사람 수(누군지는 없음)
     * remainingMs: 끝날 때까지 남은 시간(보낸 순간 기준), extended: 고민 중인 사람 때문에 연장했는지
     * winner / winnerReason: 확정 후에만
     */
    public record PublicView(long version, Phase phase, int joined, int submitted, int active,
                             List<Block> board, Integer leaderId, int undecidedOnLeader, int floorPercent,
                             long remainingMs, boolean extended, boolean mediating,
                             List<String> timeline, String notice, Place winner, String winnerReason) {
    }

    /** 내 폰에 지금 떠 있는 카드. 내가 누르기 전에는 다른 사람 때문에 바뀌지 않는다 */
    public record Card(Place place, int mySatisfied, int myTotal, int position, int total) {
    }

    /** 내가 이미 반응한 후보 (블록으로 남고, 눌러서 바꿀 수 있다) */
    public record Past(Place place, boolean up, boolean out) {
    }

    /**
     * AI 중재자가 나에게만 보내는 질문.
     * state: ASKING(버튼 + 자유 답변) / THINKING(답을 정리 중) / PROPOSAL(이렇게 바꿀까요? → 적용/취소)
     * byAi: 문장을 AI가 썼는지 (false면 코드 기본 문장)
     * proposal: 바뀔 내용을 코드가 쓴 줄들, proposalAfter: 적용하면 후보 수
     */
    public record Mediator(String id, String text, boolean byAi, String state, List<String> proposal, Integer proposalAfter) {
    }

    /**
     * unmatchedDishes: 내가 적은 메뉴 중 후보 어디서도 안 파는 것 (조용히 무시하지 않고 알려 준다)
     * allergyCheck: 확정 후, 이 앱이 거를 수 없는 내 알레르기에 대한 "직접 확인하세요" 문구 (없으면 null)
     * submitted: 이미 제출했는지 (새로고침한 화면이 입력 칸을 다시 띄우지 않게)
     */
    public record PrivateView(long version, Phase phase, Conditions myConditions,
                              Card card, List<Past> history, int vetoLeft,
                              List<String> relaxable, String notice, Mediator mediator,
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
