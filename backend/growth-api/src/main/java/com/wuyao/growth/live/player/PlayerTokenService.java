package com.wuyao.growth.live.player;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.security.SecureRandom;
import java.util.Base64;

/** Signs player tokens and keeps their database hash separate from the raw URL token. */
@Component
public class PlayerTokenService {
    private final SecretKey key;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public PlayerTokenService(@Value("${growth.jwt.secret}") String secret,
                              @Value("${growth.live.player-token-ttl:12h}") Duration ttl) {
        byte[] base = secret.getBytes(StandardCharsets.UTF_8);
        byte[] domain = "wuyao-live-player-v1:".getBytes(StandardCharsets.UTF_8);
        byte[] derived = new byte[base.length + domain.length];
        System.arraycopy(base, 0, derived, 0, base.length);
        System.arraycopy(domain, 0, derived, base.length, domain.length);
        this.key = Keys.hmacShaKeyFor(digest(derived));
        this.ttl = ttl;
    }

    public Issued issue(Long tenantId, Long sessionId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String value = Jwts.builder().id(Base64.getUrlEncoder().withoutPadding().encodeToString(nonce))
                .subject(String.valueOf(sessionId)).claim("tid", tenantId).claim("typ", "live-player")
                .issuedAt(Date.from(now)).expiration(Date.from(expiresAt)).signWith(key).compact();
        return new Issued(value, sha256(value), expiresAt);
    }

    public Claims parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!"live-player".equals(claims.get("typ", String.class))) return null;
            if (claims.get("tid", Number.class) == null) return null;
            return claims;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Issued(String value, String hash, Instant expiresAt) {}
}
