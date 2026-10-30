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

    public record PublicView(long version, Phase phase, int joined, int submitted,
                             Place top, List<Place> nextUp, Integer minSatisfactionPercent,
                             int remaining, int likesOnTop, List<String> timeline, String notice) {
    }

    public record PrivateView(long version, Phase phase, Conditions myConditions,
                              Integer mySatisfied, Integer myTotal, int vetoLeft,
                              List<String> relaxable, String notice) {
    }

    /** 평문 → 조건 변환 결과. 원문은 담지 않는다. */
    public record Parsed(Conditions conditions, List<String> ignored, boolean invalid, long millis) {
    }

    private Views() {
    }
}
