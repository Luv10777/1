package com.wuyao.growth.video.plan;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 视频工作台「帮我出方案」用的看图模型。密钥只放服务端配置。 */
@Component
@ConfigurationProperties("growth.video-plan")
@Getter
@Setter
public class VideoPlanProperties {
    private String baseUrl = "https://api.onlyrouter.ai/v1";
    private String apiKey = "";
    private String model = "gpt-6-luna";
    private int timeoutSeconds = 120;
    private int maxTokens = 8000;
    /** 送给模型的参考图边长上限，缩小后再编码，控制请求体积。 */
    private int imageLongEdge = 1024;

    public boolean configured() {
        return !apiKey.isBlank() && !baseUrl.isBlank() && !model.isBlank();
    }

    @PostConstruct
    void validate() {
        if (timeoutSeconds < 1 || timeoutSeconds > 600 || maxTokens < 500 || maxTokens > 32000
                || imageLongEdge < 256 || imageLongEdge > 2048) {
            throw new IllegalStateException("growth.video-plan 配置超出范围");
        }
    }
}
