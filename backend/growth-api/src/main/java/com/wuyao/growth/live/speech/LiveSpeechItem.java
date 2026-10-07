package com.wuyao.growth.live.speech;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/** One utterance, from submission to playback. The row is the queue; no process keeps its own copy. */
@Entity
@Table(name = "live_speech_items")
@Getter
@Setter
public class LiveSpeechItem {
    public static final String GENERATING = "GENERATING";
    public static final String PENDING = "PENDING";
    public static final String READY = "READY";
    public static final String PLAYED = "PLAYED";
    public static final String FAILED = "FAILED";
    public static final String DISCARDED = "DISCARDED";

    public static final String MANUAL = "MANUAL";
    public static final String SCRIPT = "SCRIPT";
    public static final String REPLY = "REPLY";
    public static final String TEST = "TEST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "command_id", nullable = false, length = 100)
    private String commandId;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 20)
    private String mode;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, columnDefinition = "text")
    private String text = "";

    @Column(length = 140)
    private String voice;

    private Integer seq;

    @Column(name = "product_id")
    private Long productId;

    @Column(length = 40)
    private String beat;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "audio_key", length = 500)
    private String audioKey;

    @Column(name = "duration_millis")
    private Long durationMillis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pause_offsets", columnDefinition = "jsonb")
    private List<Long> pauseOffsets;

    /** A closing line spoken after the text only when there is narration to return to. */
    @Column(name = "outro_text", length = 100)
    private String outroText;

    /** Where that closing line starts within the clip. */
    @Column(name = "outro_offset_millis")
    private Long outroOffsetMillis;

    @Column(name = "first_audio_millis")
    private Long firstAudioMillis;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** When it last began waiting for a worker: on creation, and again once script text awaits synthesis. */
    @Column(name = "queued_at")
    private Instant queuedAt;

    /** When a worker last picked it up; null while it is waiting. */
    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
