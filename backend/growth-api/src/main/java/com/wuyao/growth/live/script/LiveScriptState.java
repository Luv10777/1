package com.wuyao.growth.live.script;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Runtime state of automatic narration, kept apart from the session's draft configuration. */
@Entity
@Table(name = "live_script_states")
@Getter
@Setter
public class LiveScriptState {
    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "next_seq", nullable = false)
    private int nextSeq;

    @Column(nullable = false)
    private int failures;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "enabled_by")
    private Long enabledBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
