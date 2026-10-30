package com.quietconsensus.room;

/** 방송 방법을 숨기는 인터페이스. 실제는 STOMP, 테스트는 메모리에 모으는 가짜를 끼운다. */
public interface Publisher {

    void toRoom(String roomId, Views.PublicView view);

    void toPerson(String personKey, Views.PrivateView view);
}
