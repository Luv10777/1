package com.wuyao.growth.video.analysis;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("growth.video-analysis.audio")
@Getter
@Setter
public class VideoAnalysisAudioProperties {
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    private int timeoutSeconds = 120;
    private int maxTokens = 4000;
    private InputEncoding inputEncoding = InputEncoding.DATA_URL;

    public enum InputEncoding { DATA_URL, BASE64 }

    public boolean configured() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    @PostConstruct
    void validate() {
        if (timeoutSeconds < 1 || timeoutSeconds > 600 || maxTokens < 500 || maxTokens > 16000 || inputEncoding == null) {
            throw new IllegalArgumentException("视频音频模型配置无效");
        }
    }
}
