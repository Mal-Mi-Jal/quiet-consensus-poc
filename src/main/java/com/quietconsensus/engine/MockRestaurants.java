package com.quietconsensus.engine;

import java.util.List;
import java.util.Set;

/** 데모용 가상 식당 15곳 (실제 가게 아님). 방의 기본 거리 기준은 도보 10분. */
public final class MockRestaurants {

    public static final int BASE_WALK = 10;

    public static final List<Restaurant> ALL = List.of(
            new Restaurant(1, "진국 칼국수", Set.of("한식", "국물", "면"), 9000, 5),
            new Restaurant(2, "포하우스 쌀국수", Set.of("아시안", "국물", "면"), 11000, 7),
            new Restaurant(3, "돈가스 상회", Set.of("일식", "고기", "튀김", "밥"), 10000, 4),
            new Restaurant(4, "화로 삼겹살", Set.of("한식", "고기"), 16000, 8),
            new Restaurant(5, "마라 공방", Set.of("중식", "매운음식", "국물"), 13000, 9),
            new Restaurant(6, "짬뽕 대가", Set.of("중식", "매운음식", "국물", "면", "해산물"), 10000, 6),
            new Restaurant(7, "스시 하루", Set.of("일식", "해산물", "밥"), 18000, 10),
            new Restaurant(8, "김밥 천사", Set.of("분식", "밥"), 6000, 3),
            new Restaurant(9, "떡볶이 연구소", Set.of("분식", "매운음식"), 7000, 5),
            new Restaurant(10, "파스타 키친", Set.of("양식", "면"), 15000, 9),
            new Restaurant(11, "버거 스탠드", Set.of("양식", "고기", "튀김"), 11000, 6),
            new Restaurant(12, "비빔밥 정원", Set.of("한식", "밥"), 9000, 7),
            new Restaurant(13, "꽃게 탕집", Set.of("한식", "해산물", "갑각류", "국물", "매운음식"), 17000, 12),
            new Restaurant(14, "카레 하우스", Set.of("일식", "밥"), 9500, 8),
            new Restaurant(15, "샐러드 볼", Set.of("양식"), 12000, 11));

    public static Restaurant byId(int id) {
        for (Restaurant r : ALL) if (r.id() == id) return r;
        return null;
    }

    private MockRestaurants() {
    }
}
