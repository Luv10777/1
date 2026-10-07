package com.wuyao.growth.live.player;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class LivePlayerWebSocketHandler extends TextWebSocketHandler {
    private final LivePlayerService players;
    private final LivePlayerHub hub;
    private final ObjectMapper json;

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        socket.setTextMessageSizeLimit(4096);
        String token = socket.getUri() == null ? null : UriComponentsBuilder.fromUri(socket.getUri()).build()
                .getQueryParams().getFirst("token");
        try {
            LivePlayerDtos.Scope scope = players.authenticate(token);
            socket.getAttributes().put("playerScope", scope);
            socket.getAttributes().put("playerToken", token);
            hub.connect(scope, socket);
        } catch (RuntimeException invalid) {
            socket.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        LivePlayerDtos.Scope scope = (LivePlayerDtos.Scope) socket.getAttributes().get("playerScope");
        if (scope == null) { socket.close(CloseStatus.POLICY_VIOLATION); return; }
        try {
            players.authenticate((String) socket.getAttributes().get("playerToken"));
            JsonNode payload = json.readTree(message.getPayload());
            switch (payload.path("type").asText()) {
                case "heartbeat" -> {
                    if (hub.heartbeat(scope, socket, payload.path("deviceType").asText(null))) {
                        players.heartbeat(scope);
                    }
                }
                case "ack" -> {
                    String id = payload.path("id").asText();
                    if (!id.isBlank() && id.length() <= 100) players.acknowledge(scope, socket, id);
                }
                case "disconnect" -> socket.close(CloseStatus.NORMAL);
                default -> socket.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (Exception invalid) {
            socket.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        LivePlayerDtos.Scope scope = (LivePlayerDtos.Scope) socket.getAttributes().get("playerScope");
        if (scope != null && hub.disconnect(scope, socket)) players.disconnected(scope);
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable exception) throws Exception {
        socket.close(CloseStatus.SERVER_ERROR);
    }
}
