package com.wuyao.growth.live.speech;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/**
 * Tells every api instance that a session has something new to play. It goes through PostgreSQL
 * NOTIFY, so api and worker still only meet in the database, and the signal is sent on commit
 * together with the row it announces.
 */
@Component
public class LiveSpeechNotifier {
    public static final String CHANNEL = "live_speech_ready";

    @PersistenceContext
    private EntityManager entityManager;

    /** Must run inside the transaction that makes the item READY. */
    public void ready(Long tenantId, Long sessionId) {
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (var statement = connection.prepareStatement("select pg_notify(?, ?)")) {
                statement.setString(1, CHANNEL);
                statement.setString(2, tenantId + ":" + sessionId);
                statement.execute();
            }
        });
    }
}
