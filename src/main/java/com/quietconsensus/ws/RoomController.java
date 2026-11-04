package com.quietconsensus.ws;

import com.quietconsensus.engine.Conditions;
import com.quietconsensus.llm.ConditionParser;
import com.quietconsensus.room.RoomService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

/** STOMP 메시지 진입점. 누가 보냈는지는 메시지 내용이 아니라 연결(Principal)로 판단한다. */
@Controller
public class RoomController {

    public record TextMsg(String text) {
    }

    public record PlaceMsg(int restaurantId, boolean up) {
    }

    public record RelaxMsg(String tag) {
    }

    private final RoomService rooms;
    private final ConditionParser parser;
    private final StompPublisher publisher;

    public RoomController(RoomService rooms, ConditionParser parser, StompPublisher publisher) {
        this.rooms = rooms;
        this.parser = parser;
        this.publisher = publisher;
    }

    @MessageMapping("/room/{roomId}/join")
    public void join(@DestinationVariable String roomId, Principal who, @Header("simpSessionId") String sessionId) {
        rooms.join(roomId, who.getName(), sessionId);
    }

    /** 새로고침, 화면 꺼짐, 와이파이 끊김 모두 여기로 온다. 같은 연결로 두 번 올 수도 있다. */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        if (event.getUser() != null) rooms.disconnected(event.getUser().getName(), event.getSessionId());
    }

    /** 평문을 조건으로 바꿔 본인에게만 돌려준다. 원문은 저장하지도 로그에 남기지도 않는다. */
    @MessageMapping("/room/{roomId}/parse")
    public void parse(@DestinationVariable String roomId, TextMsg msg, Principal who) {
        String key = who.getName();
        parser.parseAsync(msg.text()).thenAccept(parsed -> publisher.parsed(key, parsed));
    }

    @MessageMapping("/room/{roomId}/submit")
    public void submit(@DestinationVariable String roomId, Conditions conditions, Principal who) {
        rooms.submit(roomId, who.getName(), conditions);
    }

    @MessageMapping("/room/{roomId}/start")
    public void start(@DestinationVariable String roomId) {
        rooms.start(roomId);
    }

    @MessageMapping("/room/{roomId}/react")
    public void react(@DestinationVariable String roomId, PlaceMsg msg, Principal who) {
        rooms.react(roomId, who.getName(), msg.restaurantId(), msg.up());
    }

    @MessageMapping("/room/{roomId}/veto")
    public void veto(@DestinationVariable String roomId, PlaceMsg msg, Principal who) {
        rooms.veto(roomId, who.getName(), msg.restaurantId());
    }

    @MessageMapping("/room/{roomId}/relax")
    public void relax(@DestinationVariable String roomId, RelaxMsg msg, Principal who) {
        rooms.relax(roomId, who.getName(), msg.tag());
    }

    /** AI 중재 질문에 대한 답 (버튼 또는 자유 답변). 자유 답변 원문은 AI에 한 번 보내고 저장하지 않는다 */
    @MessageMapping("/room/{roomId}/mediate")
    public void mediate(@DestinationVariable String roomId, RoomService.MediateMsg msg, Principal who) {
        rooms.mediate(roomId, who.getName(), msg);
    }

    @MessageMapping("/room/{roomId}/allergy-check")
    public void allergyCheck(@DestinationVariable String roomId, Principal who) {
        rooms.requestAllergyCheck(roomId, who.getName());
    }

    @MessageMapping("/room/{roomId}/finish")
    public void finish(@DestinationVariable String roomId) {
        rooms.finish(roomId);
    }
}
