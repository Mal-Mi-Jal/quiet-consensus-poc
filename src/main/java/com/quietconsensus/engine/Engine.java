package com.quietconsensus.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 공정성 계산. LLM은 여기 들어오지 않는다 — 같은 입력이면 항상 같은 결과가 나와야 한다.
 *
 * 1) 필수 조건(알레르기, 절대 안 됨, 예산, 방의 거리 기준)을 어기는 식당은 탈락
 * 2) 남은 식당마다 사람별 만족도 = 지킨 희망 조건 수 / 전체 희망 조건 수
 *    (태그는 식당 태그와, 메뉴 이름은 식당 메뉴와 비교 — Menus)
 * 3) "가장 덜 만족한 사람의 만족도"가 가장 높은 곳이 1등 (동점이면 평균, 그다음 id)
 * 4) 후보가 0개면 거리 → 예산 순으로 조금씩 완화하고, 그래도 없으면 conflict
 */
public final class Engine {

    public record Scored(Restaurant restaurant, Map<String, Double> satisfaction, double min, double avg) {
    }

    public record Result(List<Scored> ranking, int walkLimit, double priceSlack,
                         List<String> relaxNotes, boolean conflict) {
        public Scored top() {
            return ranking.isEmpty() ? null : ranking.get(0);
        }
    }

    /** 완화 단계: (거리 +분, 예산 여유 비율) */
    private static final int[][] STEPS = {{0, 0}, {5, 0}, {10, 0}, {10, 10}};

    public static final Comparator<Scored> FAIR_ORDER = Comparator
            .comparingDouble(Scored::min).reversed()
            .thenComparing(Comparator.comparingDouble(Scored::avg).reversed())
            .thenComparingInt(s -> s.restaurant().id());

    public static Result rank(List<Restaurant> pool, Map<String, Conditions> people,
                              Map<String, Set<Integer>> dislikes, Set<Integer> vetoed, int baseWalk) {
        // 참가자 순서가 결과에 영향을 주지 않도록 키 순서를 고정한다
        Map<String, Conditions> sorted = new TreeMap<>(people);
        for (int[] step : STEPS) {
            int walk = baseWalk + step[0];
            double slack = step[1] / 100.0;
            List<Scored> ranking = new ArrayList<>();
            for (Restaurant r : pool) {
                if (vetoed.contains(r.id()) || !allowedForAll(r, sorted, walk, slack)) continue;
                ranking.add(score(r, sorted, dislikes));
            }
            if (!ranking.isEmpty()) {
                ranking.sort(FAIR_ORDER);
                return new Result(List.copyOf(ranking), walk, slack, notes(step), false);
            }
        }
        int[] last = STEPS[STEPS.length - 1];
        return new Result(List.of(), baseWalk + last[0], last[1] / 100.0, notes(last), true);
    }

    public static boolean allowed(Restaurant r, Conditions c, int walkLimit, double priceSlack) {
        if (r.walkMin() > walkLimit) return false;
        for (String t : c.allergy()) if (r.tags().contains(t)) return false;
        for (String t : c.mustNot()) if (r.tags().contains(t)) return false;
        for (String d : c.dishNever()) if (Menus.serves(r, d)) return false;
        // allergyOther(땅콩·우유…)는 식당 데이터로 알 수 없어서 거르지 않는다 → 확정 후 본인에게 확인 알림
        return c.maxPrice() == null || r.price() <= c.maxPrice() * (1 + priceSlack);
    }

    /** 후보 식당 중 어디서도 팔지 않는 메뉴 키워드 — 본인에게 "반영되지 않았어요"라고 알려 주기 위해 */
    public static List<String> unmatchedDishes(Conditions c, List<Restaurant> pool) {
        List<String> out = new ArrayList<>();
        Set<String> all = new java.util.LinkedHashSet<>(c.dishNever());
        all.addAll(c.dishAvoid());
        all.addAll(c.dishWant());
        for (String d : all) {
            if (pool.stream().noneMatch(r -> Menus.serves(r, d))) out.add(d);
        }
        return out;
    }

    /** 희망 조건을 몇 개 지켰는지 [지킨 수, 전체 수]. 조건이 없으면 [0, 0] = 100%로 본다. */
    public static int[] satisfiedCount(Restaurant r, Conditions c, Set<Integer> disliked) {
        int total = 0, ok = 0;
        for (String t : c.preferNot()) {
            total++;
            if (!r.tags().contains(t)) ok++;
        }
        for (String t : c.want()) {
            total++;
            if (r.tags().contains(t)) ok++;
        }
        for (String d : c.dishAvoid()) {
            total++;
            if (!Menus.serves(r, d)) ok++;
        }
        for (String d : c.dishWant()) {
            total++;
            if (Menus.serves(r, d)) ok++;
        }
        if (disliked != null && !disliked.isEmpty()) {
            total++;
            if (!disliked.contains(r.id())) ok++;
        }
        return new int[]{ok, total};
    }

    public static double satisfaction(Restaurant r, Conditions c, Set<Integer> disliked) {
        int[] s = satisfiedCount(r, c, disliked);
        return s[1] == 0 ? 1.0 : (double) s[0] / s[1];
    }

    private static boolean allowedForAll(Restaurant r, Map<String, Conditions> people, int walk, double slack) {
        for (Conditions c : people.values()) if (!allowed(r, c, walk, slack)) return false;
        return true;
    }

    private static Scored score(Restaurant r, Map<String, Conditions> people, Map<String, Set<Integer>> dislikes) {
        Map<String, Double> sat = new TreeMap<>();
        double min = 1.0, sum = 0;
        for (var e : people.entrySet()) {
            double s = satisfaction(r, e.getValue(), dislikes.getOrDefault(e.getKey(), Set.of()));
            sat.put(e.getKey(), s);
            min = Math.min(min, s);
            sum += s;
        }
        double avg = people.isEmpty() ? 1.0 : sum / people.size();
        return new Scored(r, sat, min, avg);
    }

    /** 최종 결정: 무엇으로, 왜 */
    public record Decision(Scored pick, boolean qualified, int likes) {
    }

    /** 하한을 넘는 곳이 없을 때, 공정 1등과 최저 만족도가 이만큼 이내면 "비슷하다"고 보고 👍로 고른다 (판단값, 아직 측정 안 함) */
    public static final double FALLBACK_BAND = 0.10;

    /**
     * 마지막 결정 규칙.
     * 1) "가장 덜 만족한 사람도 floor 이상"인 후보만 자격이 있다 (👎는 그 사람 만족도를 깎으므로, 다수가 한 명을 밀어붙이기 어렵다)
     * 2) 자격 있는 후보 중 👍가 가장 많은 곳. 동점이면 공정 순서(최저 만족도 → 평균 → id)
     * 3) 자격 있는 곳이 없으면: 공정 1등과 최저 만족도가 FALLBACK_BAND 이내인 곳 중 👍가 가장 많은 곳.
     *    👍가 하나도 없으면 공정 1등 (maximin). 아무도 👍하지 않은 곳이 👍 받은 곳을 제치는 일을 줄이되,
     *    최저 만족도가 크게 낮은 곳(누군가 크게 손해 보는 곳)은 👍가 많아도 뽑지 않는다.
     */
    public static Decision decide(Result result, Map<Integer, Integer> likeCounts, double floor) {
        Scored best = pickMostLiked(result.ranking(), likeCounts, floor);
        if (best != null) return new Decision(best, true, likeCounts.getOrDefault(best.restaurant().id(), 0));
        Scored top = result.top();
        if (top == null) return new Decision(null, false, 0);
        Scored near = pickMostLiked(result.ranking(), likeCounts, top.min() - FALLBACK_BAND);
        int likes = likeCounts.getOrDefault(near.restaurant().id(), 0);
        return likes > 0 ? new Decision(near, false, likes) : new Decision(top, false, 0);
    }

    /** 최저 만족도가 minAtLeast 이상인 후보 중 👍 최다. ranking이 이미 공정 순서라 먼저 나온 쪽이 동점 승자 */
    private static Scored pickMostLiked(List<Scored> ranking, Map<Integer, Integer> likeCounts, double minAtLeast) {
        Scored best = null;
        int bestLikes = -1;
        for (Scored s : ranking) {
            if (s.min() + 1e-9 < minAtLeast) continue;
            int likes = likeCounts.getOrDefault(s.restaurant().id(), 0);
            if (likes > bestLikes) {
                best = s;
                bestLikes = likes;
            }
        }
        return best;
    }

    private static List<String> notes(int[] step) {
        List<String> n = new ArrayList<>();
        if (step[0] > 0) n.add("거리 기준을 " + step[0] + "분 넓혔어요");
        if (step[1] > 0) n.add("예산을 " + step[1] + "% 넘는 곳까지 봤어요");
        return n;
    }

    private Engine() {
    }
}
