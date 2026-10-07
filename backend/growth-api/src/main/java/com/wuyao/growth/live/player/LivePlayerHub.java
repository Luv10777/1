package com.wuyao.growth.live.player;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.live.speech.LiveSpeechDelivery;
import com.wuyao.growth.live.speech.LiveSpeechItem;
import com.wuyao.growth.live.speech.LiveSpeechQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player sockets connected to this api instance. Nothing here is the queue: what to play is
 * read from {@link LiveSpeechQueue}, so a restart or a second instance loses no audio.
 */
@Component
public class LivePlayerHub implements LiveSpeechDelivery {
    private static final int SENT_HISTORY = 4096;
    private final ObjectMapper json;
    private final LiveSpeechQueue queue;
    private final Clock clock;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    @Autowired
    public LivePlayerHub(ObjectMapper json, LiveSpeechQueue queue) { this(json, queue, Clock.systemUTC()); }

    LivePlayerHub(ObjectMapper json, LiveSpeechQueue queue, Clock clock) {
        this.json = json; this.queue = queue; this.clock = clock;
    }

    private static String key(Long tenant, Long session) { return tenant + ":" + session; }

    public void connect(LivePlayerDtos.Scope scope, WebSocketSession socket) throws IOException {
        Channel channel = new Channel(socket);
        synchronized (channel) {
            Channel previous = channels.put(key(scope.tenantId(), scope.sessionId()), channel);
            if (previous != null) close(previous.socket, CloseStatus.NORMAL);
            // A reconnect starts from whatever is still unplayed, wherever it was synthesised.
            List<LivePlayerDtos.Command> commands = commands(scope.tenantId(), scope.sessionId());
            commands.forEach(command -> channel.remember(command.id()));
            send(socket, Map.of("type", "snapshot", "sessionId", scope.sessionId(),
                    "sessionName", scope.sessionName(), "commands", commands,
                    "serverTime", clock.instant().toString()));
        }
    }

    public boolean heartbeat(LivePlayerDtos.Scope scope, WebSocketSession socket, String deviceType) throws IOException {
        Channel channel = channels.get(key(scope.tenantId(), scope.sessionId()));
        if (channel == null || channel.socket != socket) return false;
        synchronized (channel) {
            Instant now = clock.instant();
            if (deviceType != null && deviceType.length() <= 80) channel.deviceType = deviceType;
            send(socket, Map.of("type", "heartbeat", "serverTime", now.toString()));
        }
        // Picks up anything a lost notification left behind.
        deliver(scope.tenantId(), scope.sessionId());
        return true;
    }

    public boolean disconnect(LivePlayerDtos.Scope scope, WebSocketSession socket) {
        String key = key(scope.tenantId(), scope.sessionId());
        Channel channel = channels.get(key);
        return channel != null && channel.socket == socket && channels.remove(key, channel);
    }

    public void revoke(Long tenant, Long session) {
        Channel channel = channels.remove(key(tenant, session));
        if (channel != null) close(channel.socket, CloseStatus.POLICY_VIOLATION);
    }

    @Override
    public void deliver(Long tenant, Long session) {
        Channel channel = channels.get(key(tenant, session));
        if (channel == null || !channel.socket.isOpen()) return;
        synchronized (channel) {
            for (LivePlayerDtos.Command command : commands(tenant, session)) {
                if (!channel.remember(command.id())) continue;
                try {
                    send(channel.socket, Map.of("type", "command", "command", command));
                } catch (IOException e) {
                    // The snapshot on reconnect resends it.
                    channel.sent.remove(command.id());
                    return;
                }
            }
        }
    }

    /** Only the socket currently bound to the session may consume its queue. */
    public Optional<LiveSpeechItem> acknowledge(LivePlayerDtos.Scope scope, WebSocketSession socket, String id) {
        Channel channel = channels.get(key(scope.tenantId(), scope.sessionId()));
        if (channel == null || channel.socket != socket) return Optional.empty();
        return TenantContext.runAs(scope.tenantId(), () -> queue.acknowledge(scope.sessionId(), id));
    }

    /** Known only on the instance holding the socket; other instances report null. */
    public String deviceType(Long tenant, Long session) {
        Channel channel = channels.get(key(tenant, session));
        return channel == null ? null : channel.deviceType;
    }

    private List<LivePlayerDtos.Command> commands(Long tenant, Long session) {
        return TenantContext.runAs(tenant, () -> queue.deliverable(session)).stream().map(LivePlayerHub::command).toList();
    }

    private static LivePlayerDtos.Command command(LiveSpeechItem item) {
        String url = LiveSpeechItem.TEST.equals(item.getKind()) ? PlayerTestAudio.URL : "/api/player/audio/" + item.getId();
        return new LivePlayerDtos.Command(item.getCommandId(), item.getMode(), item.getText(), url,
                item.getDurationMillis() == null ? 0 : item.getDurationMillis(),
                item.getPauseOffsets() == null ? List.of() : item.getPauseOffsets(), item.getOutroOffsetMillis());
    }

    private void send(WebSocketSession socket, Object value) throws IOException {
        socket.sendMessage(new TextMessage(json.writeValueAsString(value)));
    }

    private static void close(WebSocketSession socket, CloseStatus status) {
        if (!socket.isOpen()) return;
        try { socket.close(status); }
        catch (IOException ignored) { }
    }

    private static final class Channel {
        private final WebSocketSession socket;
        private String deviceType = "网页播报端";
        /** Commands already written to this socket, so a repeated delivery pass sends each once. */
        private final LinkedHashSet<String> sent = new LinkedHashSet<>();

        private Channel(WebSocketSession socket) { this.socket = socket; }

        private boolean remember(String id) {
            if (!sent.add(id)) return false;
            while (sent.size() > SENT_HISTORY) sent.remove(sent.iterator().next());
            return true;
        }
    }
}
