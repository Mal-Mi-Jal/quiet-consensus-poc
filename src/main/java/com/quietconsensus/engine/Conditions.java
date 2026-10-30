package com.quietconsensus.engine;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 한 사람의 조건. 원문 평문은 여기까지 오지 않는다 (변환 직후 버린다).
 *
 * allergy   : 절대 완화하지 않는 필수 조건 (건강)
 * mustNot   : "절대 안 됨" — 1인당 최대 2개, 후보가 0개면 본인 동의로 preferNot으로 바꿀 수 있다
 * preferNot : "가능하면 피함" — 만족도 감점
 * want      : "먹고 싶음" — 만족도 가점
 * maxPrice  : 1인 예산 상한(원), 없으면 null
 */
public record Conditions(Set<String> allergy, Set<String> mustNot, Set<String> preferNot,
                         Set<String> want, Integer maxPrice) {

    public static final int MUST_NOT_LIMIT = 2;

    public Conditions {
        allergy = clean(allergy);
        mustNot = clean(mustNot);
        preferNot = clean(preferNot);
        want = clean(want);
        // "절대 안 됨"이 2개를 넘으면 넘친 것은 "가능하면 피함"으로 내린다 (한 사람이 혼자 결정하는 것을 막음)
        if (mustNot.size() > MUST_NOT_LIMIT) {
            Set<String> keep = new LinkedHashSet<>();
            Set<String> moved = new LinkedHashSet<>(preferNot);
            for (String t : mustNot) {
                if (keep.size() < MUST_NOT_LIMIT) keep.add(t);
                else moved.add(t);
            }
            mustNot = Collections.unmodifiableSet(keep);
            preferNot = Collections.unmodifiableSet(moved);
        }
        if (maxPrice != null && maxPrice <= 0) maxPrice = null;
    }

    public static Conditions empty() {
        return new Conditions(Set.of(), Set.of(), Set.of(), Set.of(), null);
    }

    /** "절대 안 됨" 하나를 "가능하면 피함"으로 바꾼 사본 (알레르기는 대상이 아니다). */
    public Conditions relax(String tag) {
        if (!mustNot.contains(tag)) return this;
        Set<String> m = new LinkedHashSet<>(mustNot);
        m.remove(tag);
        Set<String> p = new LinkedHashSet<>(preferNot);
        p.add(tag);
        return new Conditions(allergy, m, p, want, maxPrice);
    }

    private static Set<String> clean(Set<String> in) {
        if (in == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String t : in) {
            if (t != null && Foods.isKnown(t.trim())) out.add(t.trim());
        }
        // Set.copyOf는 JVM마다 순서가 달라질 수 있어서, 입력 순서를 지키는 집합을 쓴다
        return Collections.unmodifiableSet(out);
    }
}
