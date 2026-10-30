package com.quietconsensus.room;

import com.quietconsensus.engine.Conditions;
import com.quietconsensus.engine.Engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
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

        Person(String key) {
            this.key = key;
        }
    }

    sealed interface Event permits Submit, React, Veto, Relax {
        String key();
    }

    record Submit(String key, Conditions conditions) implements Event {
    }

    record React(String key, int restaurantId, boolean up) implements Event {
    }

    record Veto(String key, int restaurantId) implements Event {
    }

    record Relax(String key, String tag) implements Event {
    }

    final String id;
    final ReentrantLock lock = new ReentrantLock();
    final Map<String, Person> people = new LinkedHashMap<>();
    /** 조율 단계 이벤트는 바로 반영하지 않고 여기 모았다가 라운드가 끝날 때 도착 순서대로 처리한다 */
    final Queue<Event> pending = new ConcurrentLinkedQueue<>();
    final Set<Integer> vetoed = new HashSet<>();
    final Map<String, Set<Integer>> dislikes = new HashMap<>();
    final Map<String, Integer> likes = new HashMap<>();
    final Deque<String> timeline = new ArrayDeque<>();

    Views.Phase phase = Views.Phase.INPUT;
    long version;
    Engine.Result result;
    /** 조율 단계 라운드: 이 라운드에 반응한 사람과 라운드가 끝나는 시각 (0이면 타이머 없음) */
    int round;
    long roundEndsAt;
    final Set<String> reacted = new HashSet<>();

    Room(String id) {
        this.id = id;
    }

    void log(String line) {
        timeline.addFirst(line);
        while (timeline.size() > 6) timeline.removeLast();
    }
}
