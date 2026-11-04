package com.quietconsensus.mediator;

import com.quietconsensus.engine.Conditions;
import com.quietconsensus.engine.Engine;
import com.quietconsensus.engine.Restaurant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * AI 중재자의 "계산" 부분 — LLM 없이 코드가 한다.
 * 사람마다 "절대 안 됨" 하나씩을 풀어 봤을 때 후보가 몇 곳 늘어나는지 세고, 가장 많이 늘어나는 하나를 고른다.
 * LLM은 여기서 나온 숫자를 받아 말투만 만든다 (숫자를 지어내면 코드 문장으로 바꿔 보낸다).
 * 알레르기는 relaxable()에 들어 있지 않으므로 절대 묻지 않는다.
 */
public final class Blockers {

    /** key의 item("면" 또는 메뉴 이름)을 풀면 후보가 before → after 곳 */
    public record Blocker(String key, String item, boolean dish, int before, int after) {
        public int gain() {
            return after - before;
        }
    }

    public static Optional<Blocker> find(List<Restaurant> pool, Map<String, Conditions> people, Set<String> askable,
                                         Set<String> alreadyAsked, Map<String, Set<Integer>> dislikes,
                                         Set<Integer> vetoed, int baseWalk) {
        int before = count(Engine.rank(pool, people, dislikes, vetoed, baseWalk));
        Blocker best = null;
        // 키 순서를 고정해서, 같은 상태면 항상 같은 사람에게 묻는다
        for (var e : new TreeMap<>(people).entrySet()) {
            if (!askable.contains(e.getKey())) continue;
            Conditions c = e.getValue();
            for (String item : c.relaxable()) {
                if (alreadyAsked.contains(askedKey(e.getKey(), item))) continue;
                Map<String, Conditions> trial = new HashMap<>(people);
                trial.put(e.getKey(), c.relax(item));
                int after = count(Engine.rank(pool, trial, dislikes, vetoed, baseWalk));
                if (after > before && (best == null || after > best.after())) {
                    best = new Blocker(e.getKey(), item, c.dishNever().contains(item), before, after);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public static String askedKey(String key, String item) {
        return key + "|" + item;
    }

    private static int count(Engine.Result r) {
        return r.conflict() ? 0 : r.ranking().size();
    }

    private Blockers() {
    }
}
