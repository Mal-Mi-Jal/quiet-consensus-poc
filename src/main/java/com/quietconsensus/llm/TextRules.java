package com.quietconsensus.llm;

import com.quietconsensus.engine.Menus;
import com.quietconsensus.engine.MockRestaurants;
import com.quietconsensus.engine.Restaurant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM이 없어도 되는 일은 코드 규칙으로 한다 (v4 측정: 7B가 알레르기 문장 9개 중 0개를 잡았고, 메뉴 이름을 분식으로 던져 넣음).
 * - 알레르기 문장 감지: 키워드 규칙. 조건에 넣지 않고 "칩에서 골라 주세요" 안내에만 쓰니 규칙으로 충분하다
 * - 아는 메뉴 찾기: 식당 데이터에 있는 메뉴 이름을 문장에서 직접 찾는다. 좋다/싫다만 정하면 된다
 */
final class TextRules {

    private static final Pattern ALLERGY = Pattern.compile("([가-힣A-Za-z]+?)\\s*(?:알레르기|알러지)");
    private static final Pattern REACTION = Pattern.compile("([가-힣A-Za-z]+?)\\s*(?:을|를)?\\s*먹으면\\s*(?:두드러기|가렵|간지럽|숨)");
    private static final Pattern STRONG_NEG = Pattern.compile("절대|무조건|진짜\\s*싫|못\\s*먹");
    private static final Pattern NEG = Pattern.compile("싫|빼|말고|질렸|별로|안\\s*돼|패스|그만|피하|아니면");

    /** 데이터에 있는 메뉴 이름 (긴 것부터: "카레우동"을 "카레"보다 먼저 찾는다) */
    private static final List<String> KNOWN = MockRestaurants.ALL.stream()
            .flatMap(r -> r.menus().stream())
            .map(Menus::normalize)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    static List<String> allergyHints(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (Pattern p : List.of(ALLERGY, REACTION)) {
            Matcher m = p.matcher(text);
            while (m.find()) out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    /** 문장에 나온 아는 메뉴. 긴 이름이 이미 잡혔으면 그 안의 짧은 이름은 건너뛴다 */
    static List<String> knownDishes(String text) {
        String t = Menus.normalize(text.length() > 100 ? text.substring(0, 100) : text);
        if (t == null) t = text.replaceAll("\\s+", "");
        List<String> found = new ArrayList<>();
        for (String m : KNOWN) {
            if (t.contains(m) && found.stream().noneMatch(f -> f.contains(m))) found.add(m);
        }
        return found;
    }

    /** LLM이 메뉴의 좋고 싫음을 주지 않았을 때 쓰는 단서 규칙 */
    static String attitudeByCue(String text) {
        if (STRONG_NEG.matcher(text).find()) return "never";
        if (NEG.matcher(text).find()) return "avoid";
        return "want";
    }

    /** 그 메뉴를 파는 식당들의 태그 — 메뉴와 함께 나온 태그가 이 안에 없으면 LLM이 지어낸 것으로 본다 */
    static Set<String> tagsOfShopsServing(List<String> dishes) {
        Set<String> tags = new LinkedHashSet<>();
        for (Restaurant r : MockRestaurants.ALL) {
            if (dishes.stream().anyMatch(d -> Menus.serves(r, d))) tags.addAll(r.tags());
        }
        return tags;
    }

    private TextRules() {
    }
}
