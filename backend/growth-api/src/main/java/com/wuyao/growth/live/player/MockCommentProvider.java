package com.wuyao.growth.live.player;

import org.springframework.stereotype.Component;

@Component
public class MockCommentProvider implements LiveCommentProvider {
    @Override public String code() { return "MOCK"; }
    @Override public Comment receive(String id, String text) { return new Comment(id, text.trim(), code()); }
}
