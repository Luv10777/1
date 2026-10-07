package com.wuyao.growth.live.speech;

import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.function.BiConsumer;

/**
 * Receives {@link LiveSpeechNotifier} signals on a dedicated connection. A missed signal only delays
 * delivery: the player's heartbeat re-reads the queue, so this is a latency optimisation, not the
 * source of truth.
 */
@Slf4j
@Component
public class LiveSpeechListener implements SmartLifecycle {
    private final String url;
    private final String username;
    private final String password;
    private final BiConsumer<Long, Long> onReady;
    private volatile boolean running;
    private volatile Connection connection;
    private Thread thread;

    public LiveSpeechListener(@Value("${spring.datasource.url}") String url,
                              @Value("${spring.datasource.username}") String username,
                              @Value("${spring.datasource.password}") String password,
                              LiveSpeechDelivery delivery) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.onReady = delivery::deliver;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::listen, "live-speech-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        Connection current = connection;
        // Closing the socket is what unblocks the pending read.
        if (current != null) try { current.close(); } catch (SQLException ignored) { }
        if (thread != null) thread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listen() {
        long backoff = 1000;
        while (running) {
            Properties properties = new Properties();
            properties.setProperty("user", username);
            properties.setProperty("password", password);
            properties.setProperty("tcpKeepAlive", "true");
            properties.setProperty("ApplicationName", "growth-live-listener");
            try (Connection opened = DriverManager.getConnection(url, properties)) {
                connection = opened;
                try (var statement = opened.createStatement()) {
                    statement.execute("LISTEN " + LiveSpeechNotifier.CHANNEL);
                }
                PGConnection postgres = opened.unwrap(PGConnection.class);
                backoff = 1000;
                while (running) {
                    PGNotification[] notifications = postgres.getNotifications(10_000);
                    if (notifications == null) continue;
                    for (PGNotification notification : notifications) dispatch(notification.getParameter());
                }
            } catch (Exception e) {
                if (!running) return;
                log.warn("直播播报通知连接中断，{} 毫秒后重连；期间由播报端心跳补发: {}", backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, 30_000);
            } finally {
                connection = null;
            }
        }
    }

    private void dispatch(String payload) {
        try {
            int split = payload.indexOf(':');
            onReady.accept(Long.valueOf(payload.substring(0, split)), Long.valueOf(payload.substring(split + 1)));
        } catch (RuntimeException e) {
            log.warn("直播播报通知处理失败: {}", e.getMessage());
        }
    }
}
