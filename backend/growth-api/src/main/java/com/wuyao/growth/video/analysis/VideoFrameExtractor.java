package com.wuyao.growth.video.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.task.NonRetryableTaskException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class VideoFrameExtractor {
    private final VideoAnalysisProperties config;
    private final ObjectMapper json;

    public Extracted extract(Path video, Path directory) {
        return extract(video, directory, false);
    }

    public Extracted extract(Path video, Path directory, boolean extractAudio) {
        try {
            Path metadataFile = directory.resolve("metadata.json");
            run(List.of(config.getFfprobePath(), "-v", "error", "-protocol_whitelist", "file",
                    "-show_entries", "stream=codec_type,width,height,duration:format=duration,format_name",
                    "-of", "json", video.toString()),
                    metadataFile, directory.resolve("probe.log"), 30);
            var root = json.readTree(Files.readAllBytes(metadataFile));
            var stream = json.createObjectNode();
            boolean hasAudio = false;
            for (var item : root.path("streams")) {
                if ("video".equals(item.path("codec_type").asText()) && stream.isEmpty()) stream.setAll((com.fasterxml.jackson.databind.node.ObjectNode) item);
                if ("audio".equals(item.path("codec_type").asText())) hasAudio = true;
            }
            String format = root.path("format").path("format_name").asText();
            if (!format.contains("mp4") && !format.contains("mov")) reject("只支持可解码的 MP4 或 MOV 视频");
            int width = stream.path("width").asInt();
            int height = stream.path("height").asInt();
            double seconds = root.path("format").path("duration").asDouble();
            if (seconds <= 0) seconds = stream.path("duration").asDouble();
            if (!Double.isFinite(seconds) || seconds <= 0 || width <= 0 || height <= 0) reject("视频没有有效画面或时长");
            if (seconds > config.getMaxDurationSeconds()) reject("视频最长支持 " + config.getMaxDurationSeconds() + " 秒");
            if ((long) width * height > 33_554_432L) reject("视频分辨率过高，请先压缩后上传");
            double fps = Math.max(1 / seconds, Math.min(config.getFps(), config.getMaxFrames() / seconds));
            String filter = "fps=" + String.format(Locale.ROOT, "%.8f", fps)
                    + ",scale='min(" + config.getFrameLongEdge() + ",iw)':'min(" + config.getFrameLongEdge() + ",ih)'"
                    + ":force_original_aspect_ratio=decrease";
            run(List.of(config.getFfmpegPath(), "-nostdin", "-v", "error", "-y", "-threads", "2",
                    "-protocol_whitelist", "file", "-i", video.toString(), "-map", "0:v:0", "-an", "-sn", "-dn",
                    "-vf", filter, "-frames:v", Integer.toString(config.getMaxFrames()),
                    "-q:v", "4", directory.resolve("frame-%03d.jpg").toString()),
                    directory.resolve("extract.log"), directory.resolve("extract-error.log"), 90);
            List<LocalFrame> frames = new ArrayList<>();
            try (var paths = Files.list(directory)) {
                List<Path> images = paths.filter(p -> p.getFileName().toString().matches("frame-\\d+\\.jpg"))
                        .sorted().toList();
                for (int i = 0; i < images.size(); i++) {
                    // fps assigns presentation times starting at zero; each image represents its surrounding interval.
                    frames.add(new LocalFrame(Math.min(i / fps, seconds), images.get(i)));
                }
            }
            if (frames.isEmpty()) reject("视频未能提取有效画面");
            Path audio = null;
            if (hasAudio && extractAudio) {
                audio = directory.resolve("audio.wav");
                try {
                run(List.of(config.getFfmpegPath(), "-nostdin", "-v", "error", "-y", "-threads", "2",
                        "-protocol_whitelist", "file", "-copyts", "-start_at_zero", "-i", video.toString(),
                        "-map", "0:a:0", "-vn", "-sn", "-dn", "-af", "aresample=16000:async=1:first_pts=0,apad",
                        "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-t", Double.toString(seconds), audio.toString()),
                        directory.resolve("audio.log"), directory.resolve("audio-error.log"), 60);
                long size = Files.size(audio);
                if (size <= 44 || size > config.getMaxDurationSeconds() * 32_000L + 4096) reject("提取的音频大小不合法");
                } catch (NonRetryableTaskException e) {
                    audio = null;
                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted()) throw e;
                    audio = null;
                }
            }
            return new Extracted(width, height, (int) Math.round(seconds * 1000), frames, hasAudio, audio);
        } catch (NonRetryableTaskException e) {
            throw e;
        } catch (IOException e) {
            throw new NonRetryableTaskException("VIDEO_ANALYSIS_MEDIA", "视频处理失败，请确认服务器已安装 FFmpeg 和 ffprobe", null);
        }
    }

    private void run(List<String> command, Path output, Path error, int timeout) throws IOException {
        Process process = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                reject("视频处理超时，请压缩后重试");
            }
            if (process.exitValue() != 0) reject("视频无法解码，请上传有效的 MP4 或 MOV 文件");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("视频处理被中断");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void reject(String message) {
        throw new NonRetryableTaskException("VIDEO_ANALYSIS_MEDIA", message, null);
    }
    public record LocalFrame(double seconds, Path path) {}
    public record Extracted(int width, int height, int durationMs, List<LocalFrame> frames, boolean hasAudio, Path audio) {}
}
