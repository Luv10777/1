package com.wuyao.growth.common.config;

import com.wuyao.growth.common.security.JwtAuthFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** 默认全部需要登录。放行清单在这里，加接口前想清楚要不要放行。 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /** Prometheus 抓取口令。为空时 /actuator/prometheus 一律拒绝，业务 JWT 也读不到。 */
    @Value("${growth.metrics.token:}")
    private String metricsToken;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/send-code",
                                "/api/auth/login",
                                "/api/auth/password-login",
                                "/api/auth/password-reset",
                                "/api/auth/wechat/start",
                                "/api/auth/wechat/callback",
                                "/api/auth/wechat/complete",
                                "/api/auth/refresh",
                                "/api/billing/wechat/notify",
                                "/api/billing/alipay/notify",
                                "/api/player/ws",
                                "/api/player/test-audio.wav",
                                "/api/player/audio/**",
                                "/api/public/**",
                                "/actuator/health").permitAll()
                        .requestMatchers("/actuator/prometheus").access(metricsScrape())
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.setCharacterEncoding("UTF-8");
                    res.getWriter().write("{\"code\":1401,\"message\":\"未登录或登录已过期\"}");
                }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private AuthorizationManager<RequestAuthorizationContext> metricsScrape() {
        byte[] expected = ("Bearer " + metricsToken).getBytes(StandardCharsets.UTF_8);
        return (authentication, context) -> {
            String header = context.getRequest().getHeader("Authorization");
            boolean granted = !metricsToken.isBlank() && header != null
                    && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8));
            return new AuthorizationDecision(granted);
        };
    }
}
