package com.quietconsensus.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * /topic/room/{id}  : 방 전체에 나가는 공개 상태
 * /user/queue/me    : 그 연결 한 사람에게만 가는 개인 상태
 * /app/room/{id}/.. : 클라이언트 → 서버 (RoomController)
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setHandshakeHandler(new AnonymousHandshakeHandler())
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        // 같은 연결로 나가는 메시지는 보낸 순서대로 도착하게 한다 (버전이 뒤집히지 않게)
        registry.setPreservePublishOrder(true);
    }

    /** 로그인 없이 연결마다 무작위 id를 붙인다. 이 id로 개인 채널(/user/queue/me)이 갈린다. */
    static class AnonymousHandshakeHandler extends DefaultHandshakeHandler {
        @Override
        protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                          Map<String, Object> attributes) {
            String id = UUID.randomUUID().toString();
            return () -> id;
        }
    }
}
