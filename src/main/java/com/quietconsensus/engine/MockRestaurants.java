package com.quietconsensus.engine;

import java.util.List;
import java.util.Set;

/**
 * 데모용 가상 식당 25곳 (실제 가게 아님). 방의 기본 거리 기준은 도보 10분.
 * 16~25는 2026-11-05에 추가 (4명이면 후보가 너무 좁아서). 평가 문장에 "목록에 없는 메뉴"로 쓴 이름
 * (라멘, 부대찌개, 갈비탕, 고등어 등)은 메뉴에 넣지 않는다 — 넣으면 평가셋의 의미가 바뀐다.
 */
public final class MockRestaurants {

    public static final int BASE_WALK = 10;

    public static final List<Restaurant> ALL = List.of(
            new Restaurant(1, "진국 칼국수", Set.of("한식", "국물", "면"), 9000, 5, List.of("칼국수", "수제비", "보쌈")),
            new Restaurant(2, "포하우스 쌀국수", Set.of("아시안", "국물", "면"), 11000, 7, List.of("쌀국수", "분짜", "월남쌈")),
            new Restaurant(3, "돈가스 상회", Set.of("일식", "고기", "튀김", "밥"), 10000, 4, List.of("돈가스", "카레돈가스", "치즈돈가스")),
            new Restaurant(4, "화로 삼겹살", Set.of("한식", "고기"), 16000, 8, List.of("삼겹살", "목살", "된장찌개")),
            new Restaurant(5, "마라 공방", Set.of("중식", "매운음식", "국물"), 13000, 9, List.of("마라탕", "마라샹궈", "꿔바로우")),
            new Restaurant(6, "짬뽕 대가", Set.of("중식", "매운음식", "국물", "면", "해산물"), 10000, 6, List.of("짬뽕", "짜장면", "탕수육")),
            new Restaurant(7, "스시 하루", Set.of("일식", "해산물", "밥"), 18000, 10, List.of("초밥", "회덮밥", "우동")),
            new Restaurant(8, "김밥 천사", Set.of("분식", "밥"), 6000, 3, List.of("김밥", "라면", "떡볶이")),
            new Restaurant(9, "떡볶이 연구소", Set.of("분식", "매운음식"), 7000, 5, List.of("떡볶이", "튀김", "순대")),
            new Restaurant(10, "파스타 키친", Set.of("양식", "면"), 15000, 9, List.of("파스타", "리조또", "샐러드")),
            new Restaurant(11, "버거 스탠드", Set.of("양식", "고기", "튀김"), 11000, 6, List.of("버거", "감자튀김", "핫도그")),
            new Restaurant(12, "비빔밥 정원", Set.of("한식", "밥"), 9000, 7, List.of("비빔밥", "된장찌개", "김치찌개")),
            new Restaurant(13, "꽃게 탕집", Set.of("한식", "해산물", "갑각류", "국물", "매운음식"), 17000, 12, List.of("꽃게탕", "해물탕", "간장게장")),
            new Restaurant(14, "카레 하우스", Set.of("일식", "밥"), 9500, 8, List.of("카레", "카레우동", "오므라이스")),
            new Restaurant(15, "샐러드 볼", Set.of("양식"), 12000, 11, List.of("샐러드", "포케", "샌드위치")),
            new Restaurant(16, "순두부 마을", Set.of("한식", "국물", "밥", "매운음식"), 9000, 6, List.of("순두부찌개", "제육덮밥", "콩나물국밥")),
            new Restaurant(17, "황태 해장국", Set.of("한식", "국물", "밥", "해산물"), 9500, 7, List.of("황태해장국", "북엇국", "김치전")),
            new Restaurant(18, "냉면 명가", Set.of("한식", "면", "국물"), 11000, 8, List.of("물냉면", "비빔냉면", "만두")),
            new Restaurant(19, "우동 이치", Set.of("일식", "면", "국물", "튀김"), 9000, 5, List.of("튀김우동", "자루우동", "오니기리")),
            new Restaurant(20, "방콕 키친", Set.of("아시안", "면", "밥"), 11000, 9, List.of("팟타이", "나시고렝", "카오팟")),
            new Restaurant(21, "딤섬 하우스", Set.of("중식", "밥", "튀김"), 12000, 8, List.of("샤오롱바오", "군만두", "볶음밥")),
            new Restaurant(22, "덮밥 식당", Set.of("일식", "밥", "고기"), 10000, 6, List.of("규동", "부타동", "오야코동")),
            new Restaurant(23, "쫄면 분식", Set.of("분식", "면", "매운음식"), 7000, 4, List.of("쫄면", "비빔국수", "찐만두")),
            new Restaurant(24, "생선구이 집", Set.of("한식", "해산물", "밥"), 11000, 9, List.of("갈치조림", "삼치구이", "된장찌개")),
            new Restaurant(25, "타코 하우스", Set.of("양식", "고기", "매운음식"), 12000, 8, List.of("타코", "부리또", "나초")));

    public static Restaurant byId(int id) {
        for (Restaurant r : ALL) if (r.id() == id) return r;
        return null;
    }

    private MockRestaurants() {
    }
}
