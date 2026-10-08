package com.wuyao.growth.video.analysis;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

@Component
@ConfigurationProperties("growth.video-analysis")
@Getter
@Setter
public class VideoAnalysisProperties {
    private String baseUrl = "https://api.onlyrouter.ai/v1";
    private String apiKey = "";
    private String model = "gpt-6-luna";
    private int timeoutSeconds = 180;
    private int maxTokens = 6000;
    private long maxBytes = 100L * 1024 * 1024;
    private int maxDurationSeconds = 60;
    private int maxFrames = 48;
    private int frameLongEdge = 1280;
    private double fps = 2;
    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";

    public boolean configured() {
        return !apiKey.isBlank() && !baseUrl.isBlank() && !model.isBlank();
    }

    @PostConstruct
    void validate() {
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE || maxDurationSeconds < 1 || maxDurationSeconds > 600
                || maxFrames < 1 || maxFrames > 48 || !Double.isFinite(fps) || fps <= 0 || fps > 10
                || frameLongEdge < 64 || frameLongEdge > 1920 || timeoutSeconds < 1 || timeoutSeconds > 600
                || maxTokens < 500 || maxTokens > 16000 || ffmpegPath.isBlank() || ffprobePath.isBlank()) {
            throw new IllegalArgumentException("视频反推的媒体限制或模型时限配置无效");
        }
    }
}
