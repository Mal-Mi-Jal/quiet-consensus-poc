package com.quietconsensus.engine;

import java.util.Set;

/** 후보 식당 한 곳. tags는 Foods.ALL 안의 단어만 쓴다 (LLM이 뽑는 조건과 같은 단어여야 비교가 된다). */
public record Restaurant(int id, String name, Set<String> tags, int price, int walkMin) {
}
