package com.wuyao.growth.live.player;

import org.springframework.stereotype.Component;

/**
 * Comments the merchant's desktop app read from their own Douyin live room and passed on. This is not
 * the platform's open API: nothing here talks to Douyin, the server only records what the app sends.
 */
@Component
public class LiveRoomCommentProvider implements LiveCommentProvider {
    public static final String CODE = "DOUYIN_WEB";

    @Override public String code() { return CODE; }
    @Override public Comment receive(String id, String text) { return new Comment(id, text.trim(), code()); }
}
