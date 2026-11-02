package com.quietconsensus.ws;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * /topic/room/{id}  : 방 전체에 나가는 공개 상태
 * /user/queue/me    : 그 사람 한 명에게만 가는 개인 상태
 * /app/room/{id}/.. : 클라이언트 → 서버 (RoomController)
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private TaskScheduler heartbeatScheduler;

    @Autowired
    void setHeartbeatScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler) {
        this.heartbeatScheduler = scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setHandshakeHandler(new AnonymousHandshakeHandler())
                .setAllowedOriginPatterns("*");
        // 구독 → join 순서를 지킨다. 뒤집히면 재접속 복구 메시지를 구독 전에 보내 버린다
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 하트비트: 폰이 말없이 사라져도(와이파이 끊김) 서버가 끊김을 알아챈다
        registry.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(heartbeatScheduler)
                .setHeartbeatValue(new long[]{10000, 10000});
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        // 같은 연결로 나가는 메시지는 보낸 순서대로 도착하게 한다 (버전이 뒤집히지 않게)
        registry.setPreservePublishOrder(true);
    }

    /**
     * 로그인 없이 사람을 구분한다. 탭이 만든 임의 id(?pid=)를 그대로 Principal로 써서
     * 새로고침해도 같은 사람으로 알아본다. 이 id로 개인 채널(/user/queue/me)이 갈리므로
     * 추측할 수 없을 만큼 긴 값만 받고, 없거나 이상하면 예전처럼 연결마다 무작위 id를 붙인다.
     */
    static class AnonymousHandshakeHandler extends DefaultHandshakeHandler {
        private static final Pattern PERSON_ID = Pattern.compile("[A-Za-z0-9-]{32,64}");

        @Override
        protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                          Map<String, Object> attributes) {
            String pid = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("pid");
            String id = pid != null && PERSON_ID.matcher(pid).matches() ? pid : UUID.randomUUID().toString();
            return () -> id;
        }
    }
}
