package com.wuyao.growth.live.player;

/** Platform comment sources implement this adapter; no unofficial signature service is required here. */
public interface LiveCommentProvider {
    String code();
    Comment receive(String id, String text);
    record Comment(String id, String text, String provider) {}
}
