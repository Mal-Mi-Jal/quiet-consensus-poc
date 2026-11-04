package com.quietconsensus.engine;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 한 사람의 조건. 원문 평문은 여기까지 오지 않는다 (변환 직후 버린다).
 *
 * allergy      : 가게 종류로 거를 수 있는 알레르기(갑각류, 해산물) — 절대 완화하지 않는 필수 조건. 칩으로만 받는다
 * allergyOther : 가게 종류로는 거를 수 없는 알레르기(땅콩, 우유…) — 거르지 않고, 확정 후 본인에게 "직접 확인" 알림만
 * mustNot      : "절대 안 됨" 태그
 * preferNot    : "가능하면 피함" 태그 — 만족도 감점
 * want         : "먹고 싶음" 태그 — 만족도 가점
 * dishNever / dishAvoid / dishWant : 태그 목록에 없는 메뉴 이름("카레"). 식당 메뉴에서 코드가 찾는다
 * maxPrice     : 1인 예산 상한(원), 없으면 null
 *
 * "절대 안 됨"은 태그와 메뉴를 합쳐 1인 2개까지. 넘치면 "가능하면 피함"으로 내린다 (한 사람이 혼자 결정하는 것을 막음).
 * 가중치·우선순위 칸은 일부러 없다 → LLM이 무엇을 출력해도 한 사람 = 한 표.
 */
public record Conditions(Set<String> allergy, Set<String> allergyOther, Set<String> mustNot, Set<String> preferNot,
                         Set<String> want, Set<String> dishNever, Set<String> dishAvoid, Set<String> dishWant,
                         Integer maxPrice) {

    public static final int MUST_NOT_LIMIT = 2;

    public Conditions {
        allergy = cleanTags(allergy);
        allergyOther = cleanWords(allergyOther, 6);
        mustNot = cleanTags(mustNot);
        preferNot = cleanTags(preferNot);
        want = cleanTags(want);
        dishNever = cleanDishes(dishNever);
        dishAvoid = cleanDishes(dishAvoid);
        dishWant = cleanDishes(dishWant);
        // "절대 안 됨"은 태그 먼저, 그다음 메뉴 순서로 2개까지만 남기고 나머지는 "가능하면 피함"으로 내린다
        Set<String> keepTags = new LinkedHashSet<>(), keepDishes = new LinkedHashSet<>();
        Set<String> movedTags = new LinkedHashSet<>(preferNot), movedDishes = new LinkedHashSet<>(dishAvoid);
        for (String t : mustNot) {
            if (keepTags.size() < MUST_NOT_LIMIT) keepTags.add(t);
            else movedTags.add(t);
        }
        for (String d : dishNever) {
            if (keepTags.size() + keepDishes.size() < MUST_NOT_LIMIT) keepDishes.add(d);
            else movedDishes.add(d);
        }
        mustNot = Collections.unmodifiableSet(keepTags);
        preferNot = Collections.unmodifiableSet(movedTags);
        dishNever = Collections.unmodifiableSet(keepDishes);
        dishAvoid = Collections.unmodifiableSet(movedDishes);
        if (maxPrice != null && maxPrice <= 0) maxPrice = null;
    }

    /** 태그 조건만 있는 경우를 위한 짧은 생성자 (메뉴·기타 알레르기 없음) */
    public Conditions(Set<String> allergy, Set<String> mustNot, Set<String> preferNot, Set<String> want, Integer maxPrice) {
        this(allergy, Set.of(), mustNot, preferNot, want, Set.of(), Set.of(), Set.of(), maxPrice);
    }

    public static Conditions empty() {
        return new Conditions(Set.of(), Set.of(), Set.of(), Set.of(), null);
    }

    /** "절대 안 됨" 하나를 "가능하면 피함"으로 바꾼 사본 (알레르기는 대상이 아니다). 태그와 메뉴 모두 가능 */
    public Conditions relax(String item) {
        if (mustNot.contains(item)) {
            Set<String> m = new LinkedHashSet<>(mustNot);
            m.remove(item);
            Set<String> p = new LinkedHashSet<>(preferNot);
            p.add(item);
            return new Conditions(allergy, allergyOther, m, p, want, dishNever, dishAvoid, dishWant, maxPrice);
        }
        if (dishNever.contains(item)) {
            Set<String> n = new LinkedHashSet<>(dishNever);
            n.remove(item);
            Set<String> a = new LinkedHashSet<>(dishAvoid);
            a.add(item);
            return new Conditions(allergy, allergyOther, mustNot, preferNot, want, n, a, dishWant, maxPrice);
        }
        return this;
    }

    /** "절대 안 됨" 하나를 아예 지운 사본 — 중재 질문에 "상관없어요"라고 답했을 때 (알레르기는 대상이 아니다) */
    public Conditions drop(String item) {
        if (mustNot.contains(item)) {
            Set<String> m = new LinkedHashSet<>(mustNot);
            m.remove(item);
            return new Conditions(allergy, allergyOther, m, preferNot, want, dishNever, dishAvoid, dishWant, maxPrice);
        }
        if (dishNever.contains(item)) {
            Set<String> n = new LinkedHashSet<>(dishNever);
            n.remove(item);
            return new Conditions(allergy, allergyOther, mustNot, preferNot, want, n, dishAvoid, dishWant, maxPrice);
        }
        return this;
    }

    /** "절대 안 됨" 전부 (태그 + 메뉴) — 후보가 0개일 때 풀어 줄 수 있는 것들 */
    public Set<String> relaxable() {
        Set<String> all = new LinkedHashSet<>(mustNot);
        all.addAll(dishNever);
        return all;
    }

    // Set.copyOf는 JVM마다 순서가 달라질 수 있어서, 입력 순서를 지키는 집합을 쓴다
    private static Set<String> cleanTags(Set<String> in) {
        if (in == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String t : in) {
            if (t != null && Foods.isKnown(t.trim())) out.add(t.trim());
        }
        return Collections.unmodifiableSet(out);
    }

    private static Set<String> cleanDishes(Set<String> in) {
        if (in == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String d : in) {
            String n = Menus.normalize(d);
            if (n != null && !Foods.isKnown(n) && out.size() < 5) out.add(n);
        }
        return Collections.unmodifiableSet(out);
    }

    private static Set<String> cleanWords(Set<String> in, int max) {
        if (in == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String w : in) {
            if (w == null) continue;
            String t = w.trim();
            if (!t.isEmpty() && t.length() <= 20 && out.size() < max) out.add(t);
        }
        return Collections.unmodifiableSet(out);
    }
}
