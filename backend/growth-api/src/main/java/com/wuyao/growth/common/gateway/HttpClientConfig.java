package com.wuyao.growth.common.gateway;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Apache HttpClient 5 连接池配置
 * 替换 HttpURLConnection,提供连接复用和并发控制
 */
@Configuration
public class HttpClientConfig {

    @Value("${growth.http.max-total:200}")
    private int maxTotal;

    @Value("${growth.http.max-per-route:50}")
    private int maxPerRoute;

    @Value("${growth.http.connection-timeout:15}")
    private int connectionTimeoutSeconds;

    @Value("${growth.http.socket-timeout:120}")
    private int socketTimeoutSeconds;

    @Bean
    public HttpClient httpClient() {
        // 连接池管理器
        PoolingHttpClientConnectionManager connectionManager =
            PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .build();

        // 请求配置
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                .setConnectTimeout(Timeout.ofSeconds(connectionTimeoutSeconds))
                .setResponseTimeout(Timeout.ofSeconds(socketTimeoutSeconds))
                .setExpectContinueEnabled(false)
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .evictIdleConnections(TimeValue.ofSeconds(60))
                .build();
    }
}
