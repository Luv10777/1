package com.wuyao.growth.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 契约 4：认证一律走 Authorization: Bearer &lt;jwt&gt;。
 * 不存在 X-User-Id 这种头，也不接受任何"默认用户"。
 *
 * 注意 access 和 refresh 各有各的 TTL，两个 getter 都在。
 * （老后端只暴露了 access 的 getter，结果 refresh token 落库时用了 access 的
 *   过期时间，签发 30 天、库里 1 小时就失效——不要重蹈。）
 */
@Slf4j
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(@Value("${growth.jwt.secret}") String secret,
                      @Value("${growth.jwt.access-ttl}") Duration accessTtl,
                      @Value("${growth.jwt.refresh-ttl}") Duration refreshTtl) {
        if (secret == null || secret.isBlank()
                || secret.equals("dev-only-secret-change-me-0123456789abcdef")) {
            throw new IllegalStateException("必须配置随机 JWT_SECRET，禁止使用空值或旧默认密钥");
        }
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException("growth.jwt.secret 至少 32 字节，当前 " + raw.length);
        }
        this.key = Keys.hmacShaKeyFor(raw);
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public String issueAccessToken(Long userId, Long tenantId, String phone) {
        return build(userId, tenantId, phone, "access", accessTtl);
    }

    public String issueRefreshToken(Long userId, Long tenantId, String phone) {
        return build(userId, tenantId, phone, "refresh", refreshTtl);
    }

    private String build(Long userId, Long tenantId, String phone, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                // jti 必须有：iat/exp 只精确到秒，同一秒内签两次会得到完全相同的 token，
                // 落库时撞 token_hash 唯一索引。加了随机 jti 才能保证每个 token 唯一。
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("tid", tenantId)
                .claim("phone", phone)
                .claim("typ", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 解析并校验；失败返回 null，由调用方决定怎么处理。 */
    public AuthPrincipal parse(String token, String expectedType) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!expectedType.equals(c.get("typ", String.class))) {
                return null;
            }
            Number tid = c.get("tid", Number.class);
            return new AuthPrincipal(Long.valueOf(c.getSubject()), tid.longValue(), c.get("phone", String.class));
        } catch (Exception e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            return null;
        }
    }
}
