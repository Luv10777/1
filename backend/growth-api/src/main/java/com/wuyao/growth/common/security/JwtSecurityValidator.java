package com.wuyao.growth.common.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/**
 * JWT 安全配置校验器
 * 确保生产环境必须配置有效的 JWT Secret
 */
@Slf4j
@Component
public class JwtSecurityValidator {

    @Value("${growth.jwt.secret}")
    private String jwtSecret;

    @PostConstruct
    public void validate() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "JWT_SECRET 环境变量必须设置。生产环境禁止使用空密钥。"
            );
        }

        int secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < 32) {
            throw new IllegalStateException(
                "JWT_SECRET 长度必须至少 32 字节。当前: " + secretBytes + " 字节。"
            );
        }

        log.info("JWT Secret 校验通过: {} 字节", secretBytes);
    }
}
