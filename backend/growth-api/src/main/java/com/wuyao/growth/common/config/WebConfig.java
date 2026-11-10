package com.wuyao.growth.common.config;

import com.wuyao.growth.billing.PlatformMembershipInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final PlatformMembershipInterceptor membership;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 账号未激活（会员未生效）时，AI 创作站的功能只能查看：写请求一律拦下，计费入口另有同样的检查。
        // 门店、品牌、商品、员工、素材上传、商户联盟和信息发布主域名也在用，不拦；套餐与权益、账号设置、实名、邀请返利也不拦。
        registry.addInterceptor(membership)
                .addPathPatterns("/api/image-creations/**", "/api/image/**", "/api/video/**", "/api/copy/**",
                        "/api/live-sessions/**", "/api/stores/*/live-sessions",
                        "/api/knowledge-sets/**", "/api/knowledge-entries/**", "/api/knowledge-documents/**", "/api/knowledge-suggestions/**",
                        "/api/stores/*/knowledge-sets", "/api/stores/*/knowledge-documents",
                        "/api/brands/*/knowledge-sets", "/api/brands/*/knowledge-documents",
                        "/api/voice-samples/**", "/api/stores/*/voice-samples/**", "/api/stores/*/voice/**")
                // 浏览器加载出图片后上报一次，只记耗时，不算使用功能。
                .excludePathPatterns("/api/image-creations/*/items/*/preview-loaded");
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> serverProtocol(
            @Value("${growth.http.server-protocol:}") String protocol) {
        return factory -> {
            if (!protocol.isBlank()) factory.setProtocol(protocol);
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${growth.cors.allowed-origins:http://localhost:4173,http://127.0.0.1:4173}") List<String> origins) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
