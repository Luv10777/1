package com.wuyao.growth.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class DashScopeVoiceProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DashScopeVoiceProvider provider = new DashScopeVoiceProvider(mapper, "test-key",
            "https://provider.test/clone", "wss://provider.test/tts", "model", "voice");
    private final WebSocket socket = mock(WebSocket.class);
    private final List<byte[]> chunks = new ArrayList<>();
    private DashScopeVoiceProvider.AudioListener listener;

    @BeforeEach void setup() { listener = provider.new AudioListener("task-1", chunks::add); }

    @Test void enrollmentUsesV35TargetAndRetainsReturnedIdForQueryAndDelete() throws Exception {
        var http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        String voiceId = "cosyvoice-v3.5-flash-wa-123";
        when(response.body()).thenReturn("{\"output\":{\"voice_id\":\"" + voiceId + "\"}}",
                "{\"output\":{\"status\":\"DEPLOYING\"}}", "{\"output\":{}}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var v35 = new DashScopeVoiceProvider(mapper, "test-key", "https://workspace.test/customization",
                "wss://workspace.test/inference", "cosyvoice-v3.5-flash", "", http);
        assertThat(v35.createVoice("https://storage.test/sample.wav", "wa")).isEqualTo(voiceId);
        assertThat(v35.voiceStatus(voiceId)).isEqualTo("DEPLOYING");
        v35.deleteVoice(voiceId);
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(3)).send(requests.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        var create = mapper.readTree(body(requests.getAllValues().get(0)));
        assertThat(create.path("model").asText()).isEqualTo("voice-enrollment");
        assertThat(create.at("/input/action").asText()).isEqualTo("create_voice");
        assertThat(create.at("/input/target_model").asText()).isEqualTo("cosyvoice-v3.5-flash");
        assertThat(create.at("/input/url").asText()).isEqualTo("https://storage.test/sample.wav");
        assertThat(mapper.readTree(body(requests.getAllValues().get(1))).at("/input/voice_id").asText()).isEqualTo(voiceId);
        assertThat(mapper.readTree(body(requests.getAllValues().get(2))).at("/input/action").asText()).isEqualTo("delete_voice");
        assertThat(v35.supportsVoice(voiceId)).isTrue();
        assertThat(v35.supportsVoice("cosyvoice-v3-flash-wa-123")).isFalse();
        assertThat(v35.supportsVoice(null)).isFalse();
        assertThat(v35.builtInVoices()).isEmpty();
    }

    private String body(HttpRequest request) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var done = new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            public void onError(Throwable error) { done.completeExceptionally(error); }
            public void onComplete() { done.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return done.get(1, TimeUnit.SECONDS);
    }

    @Test void requestFrameIncludesTaskCorrelationAndDuplexMode() throws Exception {
        var frame = mapper.readTree(provider.frame("task-1", "continue-task", Map.of("input", Map.of("text", "你好"))));
        assertThat(frame.at("/header/task_id").asText()).isEqualTo("task-1");
        assertThat(frame.at("/header/action").asText()).isEqualTo("continue-task");
        assertThat(frame.at("/header/streaming").asText()).isEqualTo("duplex");
        assertThat(frame.at("/payload/input/text").asText()).isEqualTo("你好");
    }

    @Test void fragmentedServerEventsAndBinaryFramesProduceOrderedPcm() {
        String start = event("task-started");
        listener.onOpen(socket);
        listener.onText(socket, start.substring(0, 15), false);
        assertThat(listener.started).isNotDone();
        listener.onText(socket, start.substring(15), true);
        assertThat(listener.started).isCompleted();
        listener.submittedAt = System.nanoTime();
        listener.onBinary(socket, ByteBuffer.wrap(new byte[]{1}), false);
        listener.onBinary(socket, ByteBuffer.wrap(new byte[]{2, 3, 4}), true);
        listener.onText(socket, event("task-finished"), true);

        assertThat(listener.finished.join()).containsExactly(1, 2, 3, 4);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).containsExactly(1);
        assertThat(chunks.get(1)).containsExactly(2, 3, 4);
        verify(socket, times(6)).request(1);
        verify(socket, never()).abort();
    }

    @Test void wordTimingIsTakenFromTheLatestEventAndDroppedWhenItCannotBeTrusted() {
        String timed = "{\"header\":{\"task_id\":\"task-1\",\"event\":\"result-generated\"},\"payload\":{\"output\":{\"type\":\"sentence-synthesis\","
                + "\"sentence\":{\"index\":%d,\"words\":[%s]}}}}";
        String first = "{\"text\":\"九点九\",\"begin_index\":0,\"end_index\":3,\"begin_time\":480,\"end_time\":960}";
        String second = "{\"text\":\"。\",\"begin_index\":3,\"end_index\":4,\"begin_time\":960,\"end_time\":1120}";
        listener.onText(socket, event("task-started"), true);
        assertThat(listener.words()).isEmpty();
        // Each event repeats everything so far; an event without words changes nothing.
        listener.onText(socket, timed.formatted(0, first), true);
        listener.onText(socket, timed.formatted(0, first + "," + second), true);
        listener.onText(socket, timed.formatted(0, ""), true);
        assertThat(listener.words()).containsExactly(new VoiceProvider.Word("九点九", 480, 960), new VoiceProvider.Word("。", 960, 1120));
        verify(socket, never()).abort();

        // A second sentence has a time base nobody has checked: no timing is better than a wrong one.
        listener.onText(socket, timed.formatted(1, first), true);
        assertThat(listener.words()).isEmpty();
        listener.onText(socket, timed.formatted(1, first + "," + second), true);
        assertThat(listener.words()).isEmpty();

        var broken = provider.new AudioListener("task-1", bytes -> { });
        broken.onText(socket, event("task-started"), true);
        broken.onText(socket, timed.formatted(0, "{\"text\":\"好\",\"begin_time\":500,\"end_time\":100}"), true);
        assertThat(broken.words()).isEmpty();
        assertThat(broken.finished).isNotDone();
    }

    @Test void firstAudioTimingStartsAtTextSubmissionAndIgnoresEmptyBinaryFrames() {
        listener.onText(socket, event("task-started"), true);
        listener.submittedAt = System.nanoTime() - 80_000_000;
        listener.onBinary(socket, ByteBuffer.allocate(0), true);
        assertThat(listener.firstAudioMillis).isEqualTo(-1);
        assertThat(chunks).isEmpty();
        listener.onBinary(socket, ByteBuffer.wrap(new byte[]{1, 2}), true);
        long first = listener.firstAudioMillis;
        assertThat(first).isGreaterThanOrEqualTo(80);
        listener.submittedAt = System.nanoTime() - 200_000_000;
        listener.onBinary(socket, ByteBuffer.wrap(new byte[]{3, 4}), true);
        assertThat(listener.firstAudioMillis).isEqualTo(first);
    }

    @Test void emptyOrIncompletePcmCannotFinishSuccessfully() {
        listener.onText(socket, event("task-started"), true);
        listener.onText(socket, event("task-finished"), true);
        assertThatThrownBy(() -> listener.finished.join()).isInstanceOf(CompletionException.class);

        var odd = provider.new AudioListener("task-1", bytes -> { });
        odd.onText(socket, event("task-started"), true);
        odd.onBinary(socket, ByteBuffer.wrap(new byte[]{1}), true);
        odd.onText(socket, event("task-finished"), true);
        assertThat(odd.finished).isCompletedExceptionally();
        verify(socket, times(2)).abort();
    }

    @Test void aRejectedCloneReportsTheProvidersErrorCodeButNothingElseFromItsReply() throws Exception {
        var http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn("{\"code\":\"Audio.DurationTooShort\",\"message\":\"audio shorter than 5s for key sk-secret\",\"request_id\":\"r-1\"}",
                "{\"code\":\"<script>\",\"message\":\"x\"}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var rejecting = new DashScopeVoiceProvider(mapper, "test-key", "https://workspace.test/customization",
                "wss://workspace.test/inference", "cosyvoice-v3.5-flash", "", http);

        assertThatThrownBy(() -> rejecting.createVoice("https://storage.test/sample.wav", "wa"))
                .hasMessageContaining("Audio.DurationTooShort").hasMessageNotContaining("sk-secret").hasMessageNotContaining("shorter");
        // A code that is not a plain identifier is not echoed to the user at all.
        assertThatThrownBy(() -> rejecting.voiceStatus("voice-1"))
                .hasMessageContaining("语音供应商请求未完成").hasMessageNotContaining("script");
    }

    @Test void deletingAVoiceThisAccountCannotFindSucceedsButOtherRefusalsAndOtherActionsStillFail() throws Exception {
        var http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn("{\"code\":\"BadRequest.ResourceNotExist\",\"message\":\"The Required resource not exist\"}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var other = new DashScopeVoiceProvider(mapper, "test-key", "https://workspace.test/customization",
                "wss://workspace.test/inference", "cosyvoice-v3.5-flash", "", http);

        assertThatCode(() -> other.deleteVoice("voice-from-another-account")).doesNotThrowAnyException();
        // The same answer to a status query is still an error: the voice cannot be used.
        assertThatThrownBy(() -> other.voiceStatus("voice-from-another-account")).hasMessageContaining("BadRequest.ResourceNotExist");
        when(response.body()).thenReturn("{\"code\":\"Throttling\",\"message\":\"slow down\"}");
        assertThatThrownBy(() -> other.deleteVoice("voice-1")).hasMessageContaining("Throttling");
    }

    @Test void aRejectedSynthesisCarriesTheProvidersErrorCodeToTheCaller() {
        listener.onText(socket, event("task-started"), true);
        listener.onText(socket, "{\"header\":{\"event\":\"task-failed\",\"task_id\":\"task-1\",\"error_code\":\"InvalidParameter\",\"error_message\":\"voice not found\"},\"payload\":{}}", true);
        assertThatThrownBy(() -> listener.finished.join())
                .hasCauseInstanceOf(com.wuyao.growth.common.web.BizException.class)
                .hasMessageContaining("InvalidParameter");
    }

    @Test void aRefusedConnectionSaysWhetherTheKeyTheWorkspaceOrTheAddressIsWrong() {
        @SuppressWarnings("unchecked") HttpResponse<Object> denied = mock(HttpResponse.class);
        when(denied.statusCode()).thenReturn(403);
        when(denied.body()).thenReturn("{\"code\":\"Endpoint.AccessDenied\",\"message\":\"internal detail\"}");
        assertThat(provider.handshakeFailure(new java.net.http.WebSocketHandshakeException(denied)))
                .hasMessageContaining("HTTP 403 Endpoint.AccessDenied").hasMessageContaining("同一个工作空间")
                .hasMessageNotContaining("internal detail");
        @SuppressWarnings("unchecked") HttpResponse<Object> expired = mock(HttpResponse.class);
        when(expired.statusCode()).thenReturn(401);
        when(expired.body()).thenReturn("<html>not json</html>");
        assertThat(provider.handshakeFailure(new java.net.http.WebSocketHandshakeException(expired)))
                .hasMessageContaining("HTTP 401").hasMessageContaining("无效或已过期");
    }

    @Test void requestsBegunTogetherAreLetThroughALittleApart() {
        var gate = new DashScopeVoiceProvider.StartGate(400);
        // Three replies and a narration segment all ready at the same moment.
        assertThat(List.of(gate.reserve(10_000), gate.reserve(10_000), gate.reserve(10_010), gate.reserve(10_020)))
                .containsExactly(0L, 400L, 790L, 1180L);
        // Once the rush is over nobody waits.
        assertThat(gate.reserve(20_000)).isZero();
        assertThat(gate.reserve(20_500)).isZero();
    }

    @Test void aRequestTurnedAwayForComingTooFastIsTriedAgainButNotForEver() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var waits = new ArrayList<Integer>();
        String result = DashScopeVoiceProvider.retryingThrottled(() -> {
            if (calls.incrementAndGet() < 3) throw new DashScopeVoiceProvider.Throttled("Throttling.RateQuota");
            return "audio";
        }, waits::add);
        assertThat(result).isEqualTo("audio");
        assertThat(waits).containsExactly(1, 2);

        calls.set(0);
        assertThatThrownBy(() -> DashScopeVoiceProvider.retryingThrottled(() -> {
            calls.incrementAndGet();
            throw new DashScopeVoiceProvider.Throttled("Throttling.RateQuota");
        }, attempt -> { })).hasMessageContaining("Throttling.RateQuota");
        assertThat(calls).hasValue(DashScopeVoiceProvider.THROTTLE_ATTEMPTS);
        // Any other failure is not retried: it would only be paid for twice.
        calls.set(0);
        assertThatThrownBy(() -> DashScopeVoiceProvider.retryingThrottled(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("other");
        }, attempt -> { })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }

    @Test void onlyARefusalBeforeAnyAudioCountsAsBeingAskedTooFast() {
        listener.onText(socket, event("task-started"), true);
        listener.onText(socket, "{\"header\":{\"task_id\":\"task-1\",\"event\":\"task-failed\",\"error_code\":\"Throttling.RateQuota\",\"error_message\":\"slow down\"}}", true);
        assertThat(listener.finished).isCompletedExceptionally();
        assertThatThrownBy(() -> listener.finished.join()).hasCauseInstanceOf(DashScopeVoiceProvider.Throttled.class);

        // Once sound has come back, a failure is a failure: trying again would speak the start twice.
        var midway = provider.new AudioListener("task-1", chunks::add);
        midway.onText(socket, event("task-started"), true);
        midway.onBinary(socket, ByteBuffer.wrap(new byte[]{1, 2}), true);
        midway.onText(socket, "{\"header\":{\"task_id\":\"task-1\",\"event\":\"task-failed\",\"error_code\":\"Throttling.RateQuota\",\"error_message\":\"slow down\"}}", true);
        assertThatThrownBy(() -> midway.finished.join()).hasCauseInstanceOf(com.wuyao.growth.common.web.BizException.class);
    }

    @Test void taskFailureRejectsBothWaitersAndIgnoresLaterAudio() {
        listener.onText(socket, event("task-failed"), true);
        listener.onBinary(socket, ByteBuffer.wrap(new byte[]{1, 2}), true);
        listener.onText(socket, event("task-started"), true);
        assertThat(listener.started).isCompletedExceptionally();
        assertThat(listener.finished).isCompletedExceptionally();
        assertThat(chunks).isEmpty();
        verify(socket).abort();
    }

    @Test void wrongTaskEventCannotStartOrFinishThisRequest() {
        listener.onText(socket, event("task-started").replace("task-1", "other-task"), true);
        assertThat(listener.started).isCompletedExceptionally();
        assertThat(listener.finished).isCompletedExceptionally();
        verify(socket).abort();
    }

    @Test void malformedOrOversizedTextFailsWithoutWaitingForTimeout() {
        listener.onText(socket, "not-json", true);
        assertThat(listener.finished).isCompletedExceptionally();
        var oversized = provider.new AudioListener("task-1", bytes -> { });
        oversized.onText(socket, "x".repeat(DashScopeVoiceProvider.MAX_EVENT_CHARS + 1), false);
        assertThat(oversized.started).isCompletedExceptionally();
        assertThat(oversized.finished).isCompletedExceptionally();
        verify(socket, times(2)).abort();
    }

    @Test void binaryBeforeStartedAndOversizedAudioAreRejected() {
        listener.onBinary(socket, ByteBuffer.wrap(new byte[]{1, 2}), true);
        assertThat(listener.finished).isCompletedExceptionally();
        assertThat(chunks).isEmpty();

        var oversized = provider.new AudioListener("task-1", chunks::add);
        oversized.onText(socket, event("task-started"), true);
        oversized.onBinary(socket, ByteBuffer.allocate(12 * 1024 * 1024 + 1), true);
        assertThat(oversized.finished).isCompletedExceptionally();
        assertThat(chunks).isEmpty();
    }

    @Test void closingBeforeFinishAndTransportErrorsFailPendingWork() {
        listener.onClose(socket, 1000, "closed");
        assertThat(listener.started).isCompletedExceptionally();
        assertThat(listener.finished).isCompletedExceptionally();
        var errored = provider.new AudioListener("task-1", chunks::add);
        errored.onError(socket, new IllegalStateException("transport"));
        assertThat(errored.started).isCompletedExceptionally();
        assertThat(errored.finished).isCompletedExceptionally();
    }

    @Test void successfulFinishIsStableAfterSocketCloseAndLateFrames() {
        listener.onText(socket, event("task-started"), true);
        listener.onBinary(socket, ByteBuffer.wrap(new byte[]{1, 2}), true);
        listener.onText(socket, event("task-finished"), true);
        listener.onClose(socket, 1000, "done");
        listener.onBinary(socket, ByteBuffer.wrap(new byte[]{3, 4}), true);
        assertThat(listener.finished.join()).containsExactly(1, 2);
        assertThat(chunks).hasSize(1);
    }

    @Test void configurationAndHostedCloneRejectLocalOnlySetupBeforeNetworkCalls() {
        var missing = new DashScopeVoiceProvider(mapper, "", "https://provider.test/clone", "wss://provider.test/tts", "model", "voice");
        assertThat(missing.configured()).isFalse();
        assertThatThrownBy(() -> missing.synthesize("你好", "voice", bytes -> { })).hasMessageContaining("配置");
        assertThatThrownBy(() -> provider.createVoice("http://localhost:9000/sample", "sample"))
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> provider.validateSampleUrl("https://localhost:9000/sample")).hasMessageContaining("公网");
        assertThatThrownBy(() -> provider.validateSampleUrl("https://127.0.0.1:9000/sample")).hasMessageContaining("公网");
        assertThatCode(() -> provider.validateSampleUrl("https://storage.example/sample?signature=test")).doesNotThrowAnyException();
        verify(socket, never()).request(anyLong());
    }

    private String event(String event) {
        return "{\"header\":{\"event\":\"" + event + "\",\"task_id\":\"task-1\"},\"payload\":{}}";
    }
}
