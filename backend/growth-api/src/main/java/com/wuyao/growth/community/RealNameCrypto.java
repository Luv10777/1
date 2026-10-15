package com.wuyao.growth.community;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** AES-256-GCM storage for identity fields; key material remains server-side. */
@Component
public class RealNameCrypto {
    private static final byte FORMAT_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public RealNameCrypto(@Value("${growth.identity.encryption-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            key = null;
            return;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encodedKey.trim());
            if (raw.length != 32) throw new IllegalArgumentException("expected 32 bytes");
            key = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("REAL_NAME_ENCRYPTION_KEY must be base64-encoded 32-byte AES-256 key", e);
        }
    }

    public byte[] encrypt(String value, Long tenantId, Long userId, String field) {
        requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, userId, field));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + NONCE_BYTES + encrypted.length)
                    .put(FORMAT_VERSION).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("实名信息加密失败", e);
        }
    }

    public String decrypt(byte[] value, Long tenantId, Long userId, String field) {
        requireKey();
        if (value == null || value.length <= 1 + NONCE_BYTES || value[0] != FORMAT_VERSION) {
            throw new IllegalStateException("实名密文格式无效");
        }
        byte[] nonce = Arrays.copyOfRange(value, 1, 1 + NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(value, 1 + NONCE_BYTES, value.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, userId, field));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("实名信息无法解密，请核对服务端密钥配置", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw BizException.of(ErrorCode.IDENTITY_ENCRYPTION_NOT_CONFIGURED, "平台尚未配置实名信息加密密钥，资料没有提交或展示");
        }
    }

    private static byte[] aad(Long tenantId, Long userId, String field) {
        return ("wuyao-real-name:v1:" + tenantId + ":" + userId + ":" + field).getBytes(StandardCharsets.UTF_8);
    }
}
