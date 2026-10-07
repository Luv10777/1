package com.wuyao.growth.live;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "live_sessions")
@Getter
@Setter
public class LiveSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "room_id", length = 200)
    private String roomId;

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";

    /** How the player page routes generated audio into the live phone. */
    @Column(name = "audio_route", nullable = false, length = 30)
    private String audioRoute = "loopback_adapter";

    /** Result of the merchant's manual sound check. */
    @Column(name = "audio_test_status", nullable = false, length = 20)
    private String audioTestStatus = "untested";

    /** True once a player page has successfully connected and sent a heartbeat. */
    @Column(name = "player_paired", nullable = false)
    private boolean playerPaired = false;

    @Column(name = "player_last_heartbeat_at")
    private Instant playerLastHeartbeatAt;

    @Column(name = "knowledge_version", nullable = false)
    private Integer knowledgeVersion = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private LiveDtos.Config config = new LiveDtos.Config(null, null, null, null, null, null, null);

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(nullable = false)
    private Long version = 0L;
}
