package com.quietconsensus.engine;

import java.util.List;
import java.util.Set;

/** 조건과 식당 태그가 함께 쓰는 고정 어휘. LLM 출력도 이 목록(enum) 안에서만 고르게 한다. */
public final class Foods {

    public static final List<String> ALL = List.of(
            "한식", "중식", "일식", "양식", "분식", "아시안",
            "고기", "해산물", "갑각류", "매운음식", "국물", "면", "튀김", "밥");

    private static final Set<String> SET = Set.copyOf(ALL);

    public static boolean isKnown(String tag) {
        return SET.contains(tag);
    }

    private Foods() {
    }
}
