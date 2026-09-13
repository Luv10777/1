package com.wuyao.vimax.config;

import io.minio.MinioClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 配置
 */
@Configuration
@ConfigurationProperties(prefix = "minio")
@Data
@Slf4j
public class MinioConfig {

    private String endpoint = "http://localhost:9000";
    private String accessKey = "wuyao_minio_admin";
    private String secretKey = "wuyao_minio_2026";
    private String bucketAssets = "vimax-assets";
    private String bucketVideos = "vimax-videos";
    private String bucketTemp = "vimax-temp";

    @Bean
    public MinioClient minioClient() {
        log.info("初始化 MinIO 客户端: endpoint={}", endpoint);
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Bean
    public String minioBucket() {
        return bucketAssets;
    }
}
