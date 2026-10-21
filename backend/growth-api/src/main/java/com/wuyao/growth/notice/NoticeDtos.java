package com.wuyao.growth.notice;

import java.time.Instant;

public final class NoticeDtos {
    private NoticeDtos() {}

    public record NoticeView(Long id, String category, String title, String body, String link, boolean read, Instant createdAt) {
        static NoticeView of(Notice n) {
            return new NoticeView(n.getId(), n.getCategory(), n.getTitle(), n.getBody(), n.getLink(), n.getReadAt() != null, n.getCreatedAt());
        }
    }

    public record UnreadView(long unread) {}
}
