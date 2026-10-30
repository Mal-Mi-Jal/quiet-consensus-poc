package com.quietconsensus;

import com.quietconsensus.engine.Conditions;
import com.quietconsensus.engine.Engine;
import com.quietconsensus.engine.Foods;
import com.quietconsensus.engine.MockRestaurants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * 가상의 그룹 수천 개로 "어떤 기준으로 고르느냐"를 비교한다 (seed 고정 → 다시 돌려도 같은 숫자).
 * FAIR  = 가장 덜 만족한 사람 기준 (우리 방식)
 * SUM   = 평균 만족도 최대 (다수결과 비슷)
 * BOSS  = 한 사람(부장님) 만족도 최대 — "눈치" 상황
 */
class SimulationReport {

    private static final int GROUPS = 3000;

    record Stat(double minAvg, double avgAvg, double zeroRate) {
    }

    @Test
    void 기준별_비교() {
        System.out.println();
        System.out.println("=== 시뮬레이션: 그룹 " + GROUPS + "개씩, 후보 = mock 식당 15곳 ===");
        for (int n : new int[]{4, 8}) {
            Random rnd = new Random(42 + n);
            double[][] acc = new double[3][3];
            int conflicts = 0, conflictsNoCap = 0;
            for (int g = 0; g < GROUPS; g++) {
                List<Raw> raws = new ArrayList<>();
                for (int i = 0; i < n; i++) raws.add(Raw.random(rnd));
                Map<String, Conditions> capped = new TreeMap<>(), noCap = new TreeMap<>();
                for (int i = 0; i < n; i++) {
                    capped.put("p" + i, raws.get(i).capped());
                    noCap.put("p" + i, raws.get(i).noCap());
                }
                if (rank(noCap).conflict()) conflictsNoCap++;
                Engine.Result r = rank(capped);
                if (r.conflict()) {
                    conflicts++;
                    continue;
                }
                List<Engine.Scored> cands = r.ranking();
                Engine.Scored[] picks = {
                        cands.get(0),
                        Collections.min(cands, Comparator.comparingDouble(Engine.Scored::avg).reversed()
                                .thenComparingInt(s -> s.restaurant().id())),
                        Collections.min(cands, Comparator.<Engine.Scored>comparingDouble(s -> s.satisfaction().get("p0")).reversed()
                                .thenComparing(Comparator.comparingDouble(Engine.Scored::avg).reversed())
                                .thenComparingInt(s -> s.restaurant().id()))};
                for (int k = 0; k < 3; k++) {
                    acc[k][0] += picks[k].min();
                    acc[k][1] += picks[k].avg();
                    acc[k][2] += picks[k].min() == 0.0 ? 1 : 0;
                }
            }
            int ok = GROUPS - conflicts;
            System.out.printf("%n[%d명]  후보 0개(완화 후에도): 절대 2개 제한 %.1f%%  vs  제한 없음 %.1f%%%n",
                    n, 100.0 * conflicts / GROUPS, 100.0 * conflictsNoCap / GROUPS);
            System.out.println("기준   | 가장 덜 만족한 사람(평균) | 전체 평균 만족도 | 0%인 사람이 생긴 그룹");
            String[] names = {"FAIR ", "SUM  ", "BOSS "};
            for (int k = 0; k < 3; k++) {
                System.out.printf("%s  |        %5.1f%%           |     %5.1f%%      |       %5.1f%%%n",
                        names[k], 100 * acc[k][0] / ok, 100 * acc[k][1] / ok, 100 * acc[k][2] / ok);
            }
        }
    }

    private static Engine.Result rank(Map<String, Conditions> people) {
        return Engine.rank(MockRestaurants.ALL, people, Map.of(), Set.of(), MockRestaurants.BASE_WALK);
    }

    /** 한 사람의 "날것" 조건. 절대 안 됨이 2개를 넘을 수 있다. */
    record Raw(Set<String> allergy, List<String> mustNot, Set<String> preferNot, Set<String> want, Integer price) {

        static Raw random(Random rnd) {
            List<String> pool = new ArrayList<>(Foods.ALL);
            Collections.shuffle(pool, rnd);
            int i = 0;
            Set<String> allergy = rnd.nextDouble() < 0.06 ? Set.of(rnd.nextBoolean() ? "갑각류" : "해산물") : Set.of();
            double p = rnd.nextDouble();
            int mustCount = p < 0.45 ? 0 : p < 0.75 ? 1 : p < 0.90 ? 2 : p < 0.97 ? 3 : 4;
            List<String> must = new ArrayList<>(pool.subList(i, i += mustCount));
            Set<String> prefer = new LinkedHashSet<>(pool.subList(i, i += rnd.nextInt(3)));
            Set<String> want = new LinkedHashSet<>(pool.subList(i, i += rnd.nextInt(3)));
            int[] prices = {8000, 10000, 12000, 15000};
            Integer price = rnd.nextDouble() < 0.3 ? prices[rnd.nextInt(prices.length)] : null;
            return new Raw(allergy, must, prefer, want, price);
        }

        Conditions capped() {
            return new Conditions(allergy, new LinkedHashSet<>(mustNot), preferNot, want, price);
        }

        /** 제한이 없다면: 절대 안 됨을 전부 필수로 (알레르기 칸에 넣어 제한을 우회) */
        Conditions noCap() {
            Set<String> all = new LinkedHashSet<>(allergy);
            all.addAll(mustNot);
            return new Conditions(all, Set.of(), preferNot, want, price);
        }
    }
}
