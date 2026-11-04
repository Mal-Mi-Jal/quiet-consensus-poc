package com.quietconsensus.room;

import com.quietconsensus.engine.Conditions;
import com.quietconsensus.engine.Engine;
import com.quietconsensus.mediator.Blockers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** 방 하나의 상태. 모든 변경은 lock 안에서만 한다 (방 단위로 한 줄 서기). */
final class Room {

    static final class Person {
        final String key;
        Conditions conditions = Conditions.empty();
        boolean submitted;
        int vetoLeft = 1;
        String notice;
        boolean allergyRequested;
        /** 지금 내 폰에 떠 있는 후보. 내가 반응하거나 그 후보가 빠질 때만 바뀐다 (남이 눌러서 넘어가지 않게) */
        Integer current;
        /** 내가 반응한 순서대로: 후보 id → 👍(true)/👎(false) */
        final LinkedHashMap<Integer, Boolean> reactions = new LinkedHashMap<>();
        /** 지금 살아 있는 연결들. 재연결이 먼저 오고 옛 연결의 끊김이 늦게 올 수 있어서 개수로 판단한다 */
        final Set<String> sessions = new HashSet<>();
        /** 연결이 모두 끊긴 시각 (0이면 연결 중) */
        long disconnectedAt;
        /** 제출하고 떠난 사람: 조건은 계산에 남기고, 의견을 기다리는 대상에서만 뺀다 */
        boolean away;

        Person(String key) {
            this.key = key;
        }
    }

    /** AI 중재 질문 하나. 방에 동시에 하나만 열어 둔다 (여러 명에게 한꺼번에 몰아치지 않게) */
    static final class Question {
        final String id;
        final String key;
        Blockers.Blocker blocker;
        String text;
        boolean byAi;
        /** WAITING(AI가 문장 만드는 중, 아직 안 보냄) / ASKING / THINKING / PROPOSAL */
        String state = "WAITING";
        /** PROPOSAL 단계: 적용하면 바뀔 조건. 서버가 들고 있고, 폰은 "적용"만 누른다 (폰이 보낸 조건을 믿지 않음) */
        Conditions proposed;
        List<String> proposalLines = List.of();
        Integer proposalAfter;

        Question(String id, String key, Blockers.Blocker blocker) {
            this.id = id;
            this.key = key;
            this.blocker = blocker;
        }
    }

    final String id;
    final ReentrantLock lock = new ReentrantLock();
    final Map<String, Person> people = new LinkedHashMap<>();
    final Set<Integer> vetoed = new HashSet<>();
    /** Engine이 쓰는 모양으로도 들고 있는다 (사람별 👎 = 그 사람 만족도 감점) */
    final Map<String, Set<Integer>> dislikes = new HashMap<>();
    final Deque<String> timeline = new ArrayDeque<>();
    /** 한 번이라도 후보였던 곳 (보드에서 지우지 않고 회색으로 남기려고) + 처음 보인 시각 */
    final Map<Integer, Long> seen = new LinkedHashMap<>();
    /** 중재에서 이미 물어본 (사람, 항목) — 같은 질문을 두 번 하지 않는다 */
    final Set<String> asked = new LinkedHashSet<>();
    /** 락을 푼 뒤에 할 일 (AI 호출처럼 오래 걸리는 것은 락 안에서 하지 않는다) */
    final List<Runnable> afterUnlock = new ArrayList<>();

    Views.Phase phase = Views.Phase.INPUT;
    long version;
    Engine.Result result;
    /** 조율이 끝나는 시각 (0이면 타이머 없음), 고민 중인 사람 때문에 한 번 연장했는지 */
    long endsAt;
    boolean extended;
    /** 공용 보드를 다시 보내야 하는지 + 마지막으로 보낸 시각 (묶어서 보낸다) */
    boolean boardDirty;
    long boardSentAt;
    Question question;
    int questionSeq;
    /** 확정된 곳과 이유 */
    Engine.Scored winner;
    String winnerReason;

    Room(String id) {
        this.id = id;
    }

    void log(String line) {
        timeline.addFirst(line);
        while (timeline.size() > 8) timeline.removeLast();
    }
}
