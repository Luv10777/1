package com.wuyao.growth.live.reply;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One viewer comment and what became of it. The row is also what the console's comment feed shows. */
@Entity
@Table(name = "live_comments")
@Getter
@Setter
public class LiveComment {
    public static final String ANSWERING = "ANSWERING";
    public static final String ANSWERED = "ANSWERED";
    public static final String UNANSWERED = "UNANSWERED";
    public static final String FAILED = "FAILED";
    /** Nothing to respond to: praise, chatter, or something that has nothing to do with the session. */
    public static final String SKIPPED = "SKIPPED";
    /** Not for a model to answer, and not to be missed either: a complaint, a refund, "are you real". */
    public static final String ATTENTION = "ATTENTION";
    /** The answer was worded by the text model rather than taken verbatim from a saved Q&A. */
    public static final String SOURCE_AI = "AI";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @Column(nullable = false, length = 500)
    private String text;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "text")
    private String answer;

    @Column(length = 30)
    private String source;

    @Column(name = "model_used", nullable = false)
    private boolean modelUsed;

    @Column(length = 500)
    private String note;

    /** A real question the session's material could not answer: something for the merchant to add. */
    @Column(name = "knowledge_gap", nullable = false)
    private boolean knowledgeGap;

    @Column(nullable = false, length = 140)
    private String voice;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "answered_at")
    private Instant answeredAt;

    /**
     * The speech command a reply to this comment is queued under; stable so a retry cannot speak twice.
     * Two sources may use the same id for different comments, so the source is part of it. Simulated
     * comments keep the form they have always been queued under.
     */
    public String commandId() {
        return "MOCK".equals(provider) ? "comment:" + externalId : "comment:" + provider + ":" + externalId;
    }

    public void settle(String status, String answer, String source, String note) {
        this.status = status;
        this.answer = answer;
        this.source = source;
        this.note = note == null || note.length() <= 500 ? note : note.substring(0, 500);
        this.answeredAt = Instant.now();
    }
}
