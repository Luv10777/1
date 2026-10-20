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
        registry.addInterceptor(membership).addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/billing/**", "/api/team/**",
                        "/api/public/**", "/api/information-publications/**", "/api/platform/**", "/api/identity/**",
                        "/api/player/ws", "/api/player/test-audio.wav", "/api/player/audio/**",
                        // 已经领出的联盟券必须能在门店核销，不能因为供券商户会员过期而失效。
                        "/api/stores/*/alliance-redemptions/**",
                        // 会员过期也要能撤回供券单、对已核销的券申诉。
                        "/api/alliance/offers/*/withdraw", "/api/alliance/vouchers/*/dispute");
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
