package com.wuyao.growth.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Uses ffprobe when available so an uploaded video is not trusted by metadata alone. */
@Component
@RequiredArgsConstructor
public class VideoMediaProbe {
    private final ObjectStorage storage;
    private final ObjectMapper json;

    @Value("${growth.video.ffprobe-path:ffprobe}") private String ffprobePath;

    public Metadata probe(String storageKey, String contentType) {
        String url = storage.presignGet(storageKey, Duration.ofMinutes(5));
        try {
            Process process = new ProcessBuilder(ffprobePath, "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=codec_type,width,height,duration:format=duration,format_name",
                    "-of", "json", url).redirectErrorStream(true).start();
            byte[] output;
            try (var input = process.getInputStream()) {
                output = input.readNBytes(1024 * 1024 + 1);
            }
            if (output.length > 1024 * 1024) {
                process.destroyForcibly();
                throw new IllegalStateException("视频探测响应过大");
            }
            if (!process.waitFor(35, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("视频探测超时");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("视频文件无法解析");
            }
            JsonNode root = json.readTree(output);
            JsonNode stream = root.path("streams").path(0);
            if (!"video".equalsIgnoreCase(stream.path("codec_type").asText())) {
                throw new IllegalStateException("文件不包含视频轨道");
            }
            int width = stream.path("width").asInt(0);
            int height = stream.path("height").asInt(0);
            double seconds = positiveDouble(stream.path("duration"))
                    .orElseGet(() -> positiveDouble(root.path("format").path("duration")).orElse(0D));
            if (width < 1 || height < 1 || seconds <= 0) throw new IllegalStateException("视频元数据不完整");
            long durationMs = Math.round(seconds * 1000D);
            if (durationMs < 1 || durationMs > Integer.MAX_VALUE) throw new IllegalStateException("视频时长无效");
            String detectedMime = normalizeMime(contentType, root.path("format").path("format_name").asText());
            return new Metadata(width, height, (int) durationMs, detectedMime);
        } catch (IOException e) {
            throw new IllegalStateException("服务器未安装可用的 ffprobe，无法验证视频", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("视频探测被中断", e);
        }
    }

    private java.util.OptionalDouble positiveDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return java.util.OptionalDouble.empty();
        try {
            double value = Double.parseDouble(node.asText());
            return value > 0 && Double.isFinite(value) ? java.util.OptionalDouble.of(value) : java.util.OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return java.util.OptionalDouble.empty();
        }
    }

    private String normalizeMime(String supplied, String format) {
        if (supplied != null && supplied.toLowerCase(Locale.ROOT).startsWith("video/")) return supplied;
        String lower = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if (lower.contains("webm") || lower.contains("matroska")) return "video/webm";
        if (lower.contains("mpegts")) return "video/mp2t";
        return "video/mp4";
    }

    public record Metadata(int width, int height, int durationMs, String mimeType) {}
}
