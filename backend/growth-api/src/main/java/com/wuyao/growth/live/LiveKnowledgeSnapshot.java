package com.wuyao.growth.live;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "live_session_knowledge_snapshots")
@Getter
@Setter
public class LiveKnowledgeSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(nullable = false, length = 1000)
    private String question;

    @Column(nullable = false, columnDefinition = "text")
    private String answer;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
