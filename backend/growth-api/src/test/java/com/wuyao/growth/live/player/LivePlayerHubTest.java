package com.wuyao.growth.live.player;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.live.speech.LiveSpeechItem;
import com.wuyao.growth.live.speech.LiveSpeechQueue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LivePlayerHubTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LiveSpeechQueue queue = mock(LiveSpeechQueue.class);
    private final LivePlayerHub hub = new LivePlayerHub(json, queue);
    private final LivePlayerDtos.Scope scope = new LivePlayerDtos.Scope(1L, 2L, 3L, "本场");

    @Test
    void reconnectSnapshotComesFromThePersistedQueueAndEachCommandIsPushedOnce() throws Exception {
        WebSocketSession socket = openSocket();
        when(queue.deliverable(2L)).thenReturn(List.of(item(11L, "first", LiveSpeechItem.MANUAL)));
        hub.connect(scope, socket);

        JsonNode snapshot = messages(socket).get(0);
        assertThat(snapshot.path("type").asText()).isEqualTo("snapshot");
        assertThat(snapshot.at("/commands/0/id").asText()).isEqualTo("first");
        assertThat(snapshot.at("/commands/0/audioUrl").asText()).isEqualTo("/api/player/audio/11");

        when(queue.deliverable(2L)).thenReturn(List.of(item(11L, "first", LiveSpeechItem.MANUAL),
                item(12L, "tone", LiveSpeechItem.TEST)));
        hub.deliver(1L, 2L);
        hub.deliver(1L, 2L);

        List<JsonNode> sent = messages(socket);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).path("type").asText()).isEqualTo("command");
        assertThat(sent.get(1).at("/command/id").asText()).isEqualTo("tone");
        // The sound check keeps its fixed fixture; it is never served from clip storage.
        assertThat(sent.get(1).at("/command/audioUrl").asText()).isEqualTo("/api/player/test-audio.wav");
    }

    @Test
    void deliveryWithoutALocalSocketDoesNotTouchTheQueue() {
        hub.deliver(1L, 2L);
        verifyNoInteractions(queue);
    }

    @Test
    void onlyTheSocketBoundToTheSessionMayAcknowledgeAndANewConnectionReplacesTheOld() throws Exception {
        WebSocketSession first = openSocket();
        WebSocketSession second = openSocket();
        when(queue.deliverable(2L)).thenReturn(List.of());
        when(queue.acknowledge(2L, "first")).thenReturn(Optional.of(item(11L, "first", LiveSpeechItem.MANUAL)));
        hub.connect(scope, first);
        hub.connect(scope, second);

        verify(first).close(CloseStatus.NORMAL);
        assertThat(hub.acknowledge(scope, first, "first")).isEmpty();
        verify(queue, never()).acknowledge(any(), any());
        assertThat(hub.acknowledge(scope, second, "first")).isPresent();
        // A late close event from the replaced socket must not unbind its successor.
        assertThat(hub.disconnect(scope, first)).isFalse();
        assertThat(hub.disconnect(scope, second)).isTrue();
    }

    @Test
    void revokeClosesTheSocketAsAPolicyViolation() throws Exception {
        WebSocketSession socket = openSocket();
        when(queue.deliverable(2L)).thenReturn(List.of());
        hub.connect(scope, socket);
        hub.revoke(1L, 2L);
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(hub.deviceType(1L, 2L)).isNull();
    }

    private static WebSocketSession openSocket() {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        return socket;
    }

    private List<JsonNode> messages(WebSocketSession socket) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, atLeastOnce()).sendMessage(captor.capture());
        List<JsonNode> messages = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) messages.add(json.readTree(message.getPayload()));
        return messages;
    }

    private static LiveSpeechItem item(Long id, String commandId, String kind) {
        LiveSpeechItem item = new LiveSpeechItem();
        item.setId(id);
        item.setCommandId(commandId);
        item.setKind(kind);
        item.setMode("APPEND");
        item.setStatus(LiveSpeechItem.READY);
        item.setText("内容");
        item.setDurationMillis(1000L);
        item.setPauseOffsets(List.of());
        return item;
    }
}
