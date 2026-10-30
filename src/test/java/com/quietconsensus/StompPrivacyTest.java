package com.quietconsensus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 진짜 WebSocket 연결 3개로, 개인 정보가 본인 연결로만 가는지 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "qc.round-ms=0")
class StompPrivacyTest {

    @Value("${local.server.port}")
    int port;

    static class Peer {
        final StompSession session;
        final List<String> publics = new CopyOnWriteArrayList<>();
        final List<String> privates = new CopyOnWriteArrayList<>();

        Peer(StompSession session) {
            this.session = session;
        }

        void send(String dest, String json) {
            StompHeaders h = new StompHeaders();
            h.setDestination(dest);
            h.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
            session.send(h, json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private Peer connect(String room) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new SimpleMessageConverter()); // byte[] 그대로 주고받기
        StompSession s = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
        }).get(5, TimeUnit.SECONDS);
        Peer p = new Peer(s);
        s.subscribe("/topic/room/" + room, collector(p.publics));
        s.subscribe("/user/queue/me", collector(p.privates));
        Thread.sleep(150); // 구독이 서버에 등록될 시간
        return p;
    }

    private static StompFrameHandler collector(List<String> sink) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                sink.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        };
    }

    @Test
    void 개인_조건은_본인_연결로만_가고_공개_채널에는_조건이_없다() throws Exception {
        String room = "privacy";
        Peer a = connect(room), b = connect(room), c = connect(room);
        for (Peer p : List.of(a, b, c)) p.send("/app/room/" + room + "/join", "{}");
        Thread.sleep(300);
        a.send("/app/room/" + room + "/submit", "{\"allergy\":[\"갑각류\"],\"mustNot\":[],\"preferNot\":[],\"want\":[\"국물\"],\"maxPrice\":null}");
        b.send("/app/room/" + room + "/submit", "{\"allergy\":[],\"mustNot\":[\"튀김\"],\"preferNot\":[],\"want\":[],\"maxPrice\":12000}");
        c.send("/app/room/" + room + "/submit", "{\"allergy\":[],\"mustNot\":[],\"preferNot\":[\"매운음식\"],\"want\":[],\"maxPrice\":null}");
        Thread.sleep(800);

        // 세 명 모두 공개 상태를 받았고, 전원 제출로 조율 단계가 열렸다
        for (Peer p : List.of(a, b, c)) {
            assertThat(p.publics).isNotEmpty();
            assertThat(p.publics.get(p.publics.size() - 1)).contains("\"phase\":\"NEGOTIATE\"");
        }
        // 공개 메시지에는 조건 칸이 아예 없다
        for (Peer p : List.of(a, b, c)) {
            assertThat(String.join("\n", p.publics)).doesNotContain("myConditions", "mustNot", "preferNot", "allergy");
        }
        // 각자 받은 개인 메시지에는 자기 조건만 있다
        String aPriv = String.join("\n", a.privates), bPriv = String.join("\n", b.privates), cPriv = String.join("\n", c.privates);
        assertThat(aPriv).contains("\"allergy\":[\"갑각류\"]").doesNotContain("12000", "\"preferNot\":[\"매운음식\"]");
        assertThat(bPriv).contains("\"maxPrice\":12000").doesNotContain("갑각류", "\"preferNot\":[\"매운음식\"]");
        assertThat(cPriv).contains("\"preferNot\":[\"매운음식\"]").doesNotContain("갑각류", "12000");
        System.out.printf("%n[실제 WebSocket] 3명 연결 → 공개 메시지 %d/%d/%d개(조건 없음 ✅), 개인 메시지는 본인 조건만 ✅%n",
                a.publics.size(), b.publics.size(), c.publics.size());
    }
}
