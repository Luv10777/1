package com.wuyao.growth.live.player;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class LivePlayerWebSocketConfig implements WebSocketConfigurer {
    private final LivePlayerWebSocketHandler handler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // The player is usable from a LAN phone. No cookie/session authentication is accepted;
        // a signed, expiring, session-scoped token is mandatory before any data is delivered.
        registry.addHandler(handler, "/api/player/ws").setAllowedOriginPatterns("*");
    }
}
