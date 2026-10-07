package com.wuyao.growth.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** DashScope CosyVoice duplex protocol. API secrets and voice samples stay on the server. */
@Slf4j
@Component
public class DashScopeVoiceProvider implements VoiceProvider {
    private static final int SAMPLE_RATE = 24000;
    private static final int MAX_AUDIO_BYTES = 12 * 1024 * 1024;
    /** Timing events repeat every word spoken so far, so they grow with the text: about 90 bytes a character. */
    static final int MAX_EVENT_CHARS = 1024 * 1024;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String key, cloneUrl, websocketUrl, model, builtin;

    @Autowired
    public DashScopeVoiceProvider(ObjectMapper mapper,
            @Value("${growth.voice.dashscope.api-key:}") String key,
            @Value("${growth.voice.dashscope.clone-url:}") String cloneUrl,
            @Value("${growth.voice.dashscope.websocket-url:}") String websocketUrl,
            @Value("${growth.voice.dashscope.model:cosyvoice-v3.5-flash}") String model,
            @Value("${growth.voice.dashscope.builtin:}") String builtin) {
        this(mapper, key, cloneUrl, websocketUrl, model, builtin, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }
    DashScopeVoiceProvider(ObjectMapper mapper, String key, String cloneUrl, String websocketUrl,
                           String model, String builtin, HttpClient http) {
        this.http = http;
        this.mapper = mapper; this.key = key; this.cloneUrl = cloneUrl;
        this.websocketUrl = websocketUrl; this.model = model; this.builtin = builtin;
    }
    public String code() { return "dashscope-cosyvoice"; }
    public String model() { return model; }
    // CosyVoice IDs include the enrollment target_model. Never use an old clone with a new model.
    public boolean supportsVoice(String voiceId) { return voiceId != null && voiceId.startsWith(model + "-"); }
    public boolean configured() { return !key.isBlank() && cloneUrl.startsWith("https://") && websocketUrl.startsWith("wss://"); }
    public List<String> builtInVoices() { return builtin.isBlank() ? List.of() : List.of(builtin); }
    public void validateSampleUrl(String sampleUrl) {
        URI sample = URI.create(sampleUrl);
        String host = sample.getHost();
        if (!"https".equalsIgnoreCase(sample.getScheme()) || host == null || host.equalsIgnoreCase("localhost")
                || host.endsWith(".localhost") || host.startsWith("127.") || host.equals("[::1]"))
            throw VoiceSampleService.invalid("样本已保存，但音频存储尚未配置公网 HTTPS 地址，阿里云无法读取。请配置可公网访问的音频存储地址后重试，无需重新录音。");
    }
    public String createVoice(String sampleUrl, String prefix) {
        validateSampleUrl(sampleUrl);
        String id = enrollment(Map.of("action", "create_voice", "target_model", model,
                "prefix", prefix, "url", sampleUrl)).path("voice_id").asText();
        if (id.isBlank()) throw failure();
        return id;
    }
    public String voiceStatus(String id) { return enrollment(Map.of("action", "query_voice", "voice_id", id)).path("status").asText(); }
    /**
     * A voice this account cannot find is already gone as far as deleting it is concerned: it was
     * removed earlier, or it was created under another account's key and is not ours to remove.
     * Refusing here would leave the sample impossible to delete after a change of account.
     */
    public void deleteVoice(String id) { enrollment(Map.of("action", "delete_voice", "voice_id", id), MISSING_VOICE); }
    private static final String MISSING_VOICE = "BadRequest.ResourceNotExist";

    private JsonNode enrollment(Map<String, String> input) { return enrollment(input, null); }

    /** @param tolerated a provider error code that counts as success for this action, or null */
    private JsonNode enrollment(Map<String, String> input, String tolerated) {
        requireConfigured();
        try {
            var request = HttpRequest.newBuilder(URI.create(cloneUrl)).timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("model", "voice-enrollment", "input", input)))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var body = mapper.readTree(response.body());
            if (tolerated != null && tolerated.equals(body.path("code").asText(""))) {
                log.info("声音复刻请求的目标已不存在，按已完成处理: action={} requestId={}", input.get("action"),
                        body.path("request_id").asText(""));
                return body.path("output");
            }
            if (response.statusCode() / 100 != 2 || body.hasNonNull("code")) {
                // The signed sample URL and the key are in the request, never in what is logged here.
                log.warn("声音复刻请求被拒绝: action={} http={} code={} message={} requestId={}", input.get("action"),
                        response.statusCode(), body.path("code").asText(""), clip(body.path("message").asText("")),
                        body.path("request_id").asText(""));
                throw failure(body.path("code").asText(""));
            }
            return body.path("output");
        } catch (BizException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw failure();
        } catch (Exception error) {
            log.warn("声音复刻请求未完成: action={} type={}", input.get("action"), error.getClass().getSimpleName());
            throw failure();
        }
    }

    /**
     * The provider refuses bursts, not load. Measured 2026-10-05: of four requests begun in the same
     * instant one was turned away, and of six, three; begun half a second apart, six ran side by side
     * without slowing each other. So requests are let through one at a time, a little apart, and one
     * that is still refused is tried again after a short wait rather than lost. A refusal comes
     * before any audio, so nothing is spoken twice.
     */
    static final long START_SPACING_MILLIS = 400;
    static final int THROTTLE_ATTEMPTS = 3;
    private final StartGate gate = new StartGate(START_SPACING_MILLIS);

    public Audio synthesize(String text, String voiceId, Consumer<byte[]> onChunk) {
        requireConfigured();
        return retryingThrottled(() -> {
            pause(gate.reserve(System.nanoTime() / 1_000_000));
            return synthesizeOnce(text, voiceId, onChunk);
        }, attempt -> pause(600L * attempt));
    }

    /** Runs the request, again if the provider says it is being asked too fast, up to {@link #THROTTLE_ATTEMPTS} times. */
    static <T> T retryingThrottled(java.util.function.Supplier<T> request, java.util.function.IntConsumer waitAfter) {
        for (int attempt = 1; ; attempt++) {
            try {
                return request.get();
            } catch (Throttled refused) {
                if (attempt >= THROTTLE_ATTEMPTS) throw failure(refused.code);
                log.info("语音合成请求被限流，稍后重试: code={} attempt={}", refused.code, attempt);
                waitAfter.accept(attempt);
            }
        }
    }

    private static void pause(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure();
        }
    }

    /** Hands out start times no closer together than the spacing, however many callers ask at once. */
    static final class StartGate {
        private final long spacingMillis;
        private long nextStartAt;

        StartGate(long spacingMillis) { this.spacingMillis = spacingMillis; }

        /** @return how long the caller should wait before starting its request */
        synchronized long reserve(long nowMillis) {
            long startAt = Math.max(nowMillis, nextStartAt);
            nextStartAt = startAt + spacingMillis;
            return startAt - nowMillis;
        }
    }

    /** The provider turned the request away for coming too fast; nothing was synthesised. */
    static final class Throttled extends RuntimeException {
        final String code;

        Throttled(String code) { super(code, null, false, false); this.code = code; }
    }

    private Audio synthesizeOnce(String text, String voiceId, Consumer<byte[]> onChunk) {
        String taskId = UUID.randomUUID().toString();
        var stream = new AudioListener(taskId, onChunk);
        WebSocket socket = null;
        try {
            socket = http.newWebSocketBuilder().header("Authorization", "Bearer " + key)
                    .connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(websocketUrl), stream).get(12, TimeUnit.SECONDS);
            socket.sendText(frame(taskId, "run-task", Map.of("task_group", "audio", "task", "tts", "function", "SpeechSynthesizer",
                    "model", model, "parameters", Map.of("text_type", "PlainText", "voice", voiceId,
                    // Per-character timing: it is what tells the caller where each sentence starts.
                    "format", "pcm", "sample_rate", SAMPLE_RATE, "word_timestamp_enabled", true),
                    "input", Map.of())), true).get(10, TimeUnit.SECONDS);
            stream.started.get(15, TimeUnit.SECONDS);
            stream.submittedAt = System.nanoTime();
            socket.sendText(frame(taskId, "continue-task", Map.of("input", Map.of("text", text))), true).get(10, TimeUnit.SECONDS);
            socket.sendText(frame(taskId, "finish-task", Map.of("input", Map.of())), true).get(10, TimeUnit.SECONDS);
            byte[] pcm = stream.finished.get(90, TimeUnit.SECONDS);
            if (pcm.length == 0 || pcm.length % 2 != 0) throw failure();
            return new Audio(pcm, SAMPLE_RATE, stream.firstAudioMillis, stream.words());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw failure();
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Throttled throttled) throw throttled;
            // The listener already turned a provider rejection into our own error; keep its reason.
            if (error.getCause() instanceof BizException rejected) throw rejected;
            if (error.getCause() instanceof WebSocketHandshakeException refused) throw handshakeFailure(refused);
            log.warn("语音合成未完成: type={}", error.getCause() == null ? "unknown" : error.getCause().getClass().getSimpleName());
            throw failure();
        } catch (BizException error) {
            throw error;
        } catch (Exception error) {
            log.warn("语音合成未完成: type={}", error.getClass().getSimpleName());
            throw failure();
        }
        finally { if (socket != null) socket.abort(); }
    }
    String frame(String taskId, String action, Map<String, Object> payload) throws Exception {
        return mapper.writeValueAsString(Map.of("header", Map.of("action", action, "task_id", taskId, "streaming", "duplex"), "payload", payload));
    }
    private void requireConfigured() { if (!configured()) throw VoiceSampleService.invalid("请先配置 DashScope API Key 与当前工作空间的 HTTPS / WSS 地址"); }
    /**
     * The connection was refused before any speech was requested: a key, workspace or address
     * problem, never the text or the voice. Saying so saves checking the wrong things.
     */
    RuntimeException handshakeFailure(WebSocketHandshakeException refused) {
        int status = refused.getResponse() == null ? 0 : refused.getResponse().statusCode();
        String code = "";
        try {
            if (refused.getResponse() != null && refused.getResponse().body() instanceof String body) {
                code = mapper.readTree(body).path("code").asText("");
            }
        } catch (Exception ignored) { /* not JSON: the status alone is reported */ }
        if (!code.matches("[A-Za-z0-9._-]{1,60}")) code = "";
        log.warn("语音合成连接被拒绝: status={} code={}", status, code);
        String detail = "HTTP " + status + (code.isEmpty() ? "" : " " + code);
        return VoiceSampleService.invalid(switch (status) {
            case 401 -> "语音服务拒绝了连接（" + detail + "）：API Key 无效或已过期";
            case 403 -> "语音服务拒绝了连接（" + detail + "）：这个 API Key 无权访问配置的工作空间地址，请确认 Key 与 DASHSCOPE_WEBSOCKET_URL 属于同一个工作空间";
            case 404 -> "语音服务拒绝了连接（" + detail + "）：DASHSCOPE_WEBSOCKET_URL 地址不存在";
            default -> "语音服务拒绝了连接（" + detail + "），请检查 API Key 与工作空间地址";
        });
    }
    private static RuntimeException failure() { return failure(""); }
    /** The provider's own error code is the one thing that tells a bad sample from a bad key or a wrong model. */
    private static RuntimeException failure(String code) {
        return VoiceSampleService.invalid(code != null && code.matches("[A-Za-z0-9._-]{1,60}")
                ? "语音供应商拒绝了请求（" + code + "），请检查账号、模型、音色与样本是否符合要求后重试"
                : "语音供应商请求未完成，请检查账号、模型与音色是否匹配后重试");
    }
    private static String clip(String value) { return value.length() <= 200 ? value : value.substring(0, 200); }

    final class AudioListener implements WebSocket.Listener {
        final CompletableFuture<Void> started = new CompletableFuture<>();
        final CompletableFuture<byte[]> finished = new CompletableFuture<>();
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private final StringBuilder fragments = new StringBuilder();
        private final String taskId;
        private final Consumer<byte[]> onChunk;
        volatile long submittedAt, firstAudioMillis = -1;
        private volatile List<Word> words = List.of();
        private int timedSentence = -1;
        private volatile boolean timingUsable = true;
        AudioListener(String taskId, Consumer<byte[]> onChunk) { this.taskId = taskId; this.onChunk = onChunk; }
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onText(WebSocket socket, CharSequence part, boolean last) {
            if (finished.isDone()) return null;
            try {
                if (fragments.length() + part.length() > MAX_EVENT_CHARS) throw failure();
                fragments.append(part);
                if (last) {
                    JsonNode root = mapper.readTree(fragments.toString());
                    JsonNode header = root.path("header");
                    fragments.setLength(0);
                    if (!taskId.equals(header.path("task_id").asText())) throw failure();
                    String event = header.path("event").asText();
                    if ("task-started".equals(event)) started.complete(null);
                    if ("result-generated".equals(event)) timing(root.path("payload").path("output").path("sentence"));
                    if ("task-finished".equals(event)) {
                        if (!started.isDone() || audio.size() == 0 || audio.size() % 2 != 0) throw failure();
                        finished.complete(audio.toByteArray());
                    }
                    if ("task-failed".equals(event)) {
                        String code = header.path("error_code").asText("");
                        // Asked too fast, before any audio: the caller tries again instead of giving up.
                        if (code.startsWith("Throttling") && audio.size() == 0) throw new Throttled(code);
                        log.warn("语音合成被拒绝: code={} message={}", code, clip(header.path("error_message").asText("")));
                        throw failure(code);
                    }
                }
            } catch (Exception error) { fail(error); socket.abort(); }
            socket.request(1); return null;
        }
        /**
         * Each timing event carries every word of its sentence so far, so the latest one replaces
         * what was kept. Text sent in one piece comes back as a single "sentence" whose times run
         * from the start of the audio. Should the provider ever split it into several, their time
         * base is not something observed here, so the timing is dropped rather than trusted.
         */
        private void timing(JsonNode sentence) {
            JsonNode list = sentence.path("words");
            if (!list.isArray() || list.isEmpty() || !timingUsable) return;
            int index = sentence.path("index").asInt(0);
            if (timedSentence >= 0 && timedSentence != index) { timingUsable = false; words = List.of(); return; }
            timedSentence = index;
            List<Word> timed = new ArrayList<>(list.size());
            for (JsonNode word : list) {
                String text = word.path("text").asText("");
                long begin = word.path("begin_time").asLong(-1), end = word.path("end_time").asLong(-1);
                if (text.isEmpty() || begin < 0 || end < begin) { timingUsable = false; words = List.of(); return; }
                timed.add(new Word(text, begin, end));
            }
            words = List.copyOf(timed);
        }
        List<Word> words() { return words; }
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer bytes, boolean last) {
            if (finished.isDone()) return null;
            try {
                if (!started.isDone()) throw failure();
                if (audio.size() + bytes.remaining() > MAX_AUDIO_BYTES) throw failure();
                byte[] chunk = new byte[bytes.remaining()]; bytes.get(chunk);
                if (chunk.length > 0 && firstAudioMillis < 0 && submittedAt > 0) firstAudioMillis = (System.nanoTime() - submittedAt) / 1_000_000;
                if (chunk.length > 0) { audio.writeBytes(chunk); onChunk.accept(chunk); }
            } catch (Exception error) { fail(error); socket.abort(); }
            socket.request(1); return null;
        }
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            if (!finished.isDone()) fail(failure()); return null;
        }
        public void onError(WebSocket socket, Throwable error) { fail(error); }
        private void fail(Throwable error) { started.completeExceptionally(error); finished.completeExceptionally(error); }
    }
}
