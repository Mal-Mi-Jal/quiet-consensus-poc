package com.quietconsensus.ws;

import com.quietconsensus.room.Publisher;
import com.quietconsensus.room.Views;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class StompPublisher implements Publisher {

    private final SimpMessagingTemplate template;

    public StompPublisher(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void toRoom(String roomId, Views.PublicView view) {
        template.convertAndSend("/topic/room/" + roomId, view);
    }

    @Override
    public void toPerson(String personKey, Views.PrivateView view) {
        template.convertAndSendToUser(personKey, "/queue/me", view);
    }

    public void parsed(String personKey, Views.Parsed parsed) {
        template.convertAndSendToUser(personKey, "/queue/parsed", parsed);
    }
}
