package com.quietconsensus.engine;

import java.util.Map;

/**
 * 메뉴 이름 매칭. 태그 목록을 늘리지 않고, 목록에 없는 음식은 식당의 메뉴 이름에서 찾는다.
 * 판단은 코드가 한다: LLM은 "카레"라고 적기만 하고, 어느 식당이 카레를 파는지는 여기서 정한다.
 */
public final class Menus {

    /** 같은 음식의 다른 표기. 데모용으로 작게 시작한다. */
    private static final Map<String, String> SYNONYMS = Map.of(
            "커리", "카레",
            "자장", "짜장",
            "돈까스", "돈가스",
            "스시", "초밥");

    /** 공백을 지우고 동의어를 한 표기로 맞춘다. 너무 짧거나 긴 것은 버린다(null). */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\s+", "");
        for (var e : SYNONYMS.entrySet()) s = s.replace(e.getKey(), e.getValue());
        return (s.length() < 1 || s.length() > 20) ? null : s;
    }

    /** 이 식당이 그 메뉴를 파는가: 메뉴 이름이나 가게 이름에 키워드가 들어 있으면 판다고 본다 ("카레" ⊂ "카레돈가스"). */
    public static boolean serves(Restaurant r, String keyword) {
        String k = normalize(keyword);
        if (k == null) return false;
        if (normalize(r.name()).contains(k)) return true;
        for (String m : r.menus()) {
            if (normalize(m).contains(k)) return true;
        }
        return false;
    }

    private Menus() {
    }
}
