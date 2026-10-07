package com.wuyao.growth.live.speech;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Synthesised clips live in object storage so the worker that produced one and the api instance that
 * serves it need not be the same process. Bytes travel over short-lived presigned URLs, which is the
 * only transfer the storage contract offers.
 */
@Slf4j
@Component
public class LiveSpeechAudioStore {
    static final int MAX_CLIP_BYTES = 12 * 1024 * 1024;
    private static final Duration URL_TTL = Duration.ofMinutes(5);
    private final ObjectStorage storage;
    private final HttpClient http;

    @Autowired
    public LiveSpeechAudioStore(ObjectStorage storage) {
        this(storage, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    LiveSpeechAudioStore(ObjectStorage storage, HttpClient http) {
        this.storage = storage;
        this.http = http;
    }

    /** Derived from the item so a retried task overwrites its own clip instead of leaking a second one. */
    public String key(Long tenantId, Long sessionId, Long itemId) {
        return "t%d/live-speech/%d/%d.wav".formatted(tenantId, sessionId, itemId);
    }

    public void put(String key, byte[] wav) {
        if (wav.length == 0 || wav.length > MAX_CLIP_BYTES) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "合成音频为空或超过单段大小上限，请缩短播报内容");
        }
        var request = HttpRequest.newBuilder(URI.create(storage.presignPut(key, URL_TTL)))
                .timeout(Duration.ofSeconds(60)).header("Content-Type", "audio/wav")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(wav)).build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status / 100 != 2) throw unavailable(key, "HTTP " + status, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(key, "interrupted", e);
        } catch (IOException e) {
            throw unavailable(key, "io", e);
        }
    }

    public byte[] get(String key) {
        var request = HttpRequest.newBuilder(URI.create(storage.presignGet(key, URL_TTL)))
                .timeout(Duration.ofSeconds(60)).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 404) {
                    throw BizException.of(ErrorCode.NOT_FOUND, "播报音频不存在或已过期，请重新生成");
                }
                if (response.statusCode() / 100 != 2) throw unavailable(key, "HTTP " + response.statusCode(), null);
                byte[] bytes = body.readNBytes(MAX_CLIP_BYTES + 1);
                if (bytes.length > MAX_CLIP_BYTES) throw unavailable(key, "oversized", null);
                return bytes;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(key, "interrupted", e);
        } catch (IOException e) {
            throw unavailable(key, "io", e);
        }
    }

    /** Best effort: an orphaned clip costs storage, never correctness. */
    public void delete(String key) {
        if (key == null) return;
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("播报音频删除失败: key={}", key, e);
        }
    }

    private static BizException unavailable(String key, String detail, Exception cause) {
        // The presigned URL carries a signature; log the key only.
        log.error("播报音频存取失败: key={} detail={}", key, detail, cause);
        return BizException.of(ErrorCode.STORAGE_UNAVAILABLE, "对象存储暂时不可用，请稍后重试");
    }
}
