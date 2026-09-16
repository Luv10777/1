package com.wuyao.vimax.service.gateway;

import com.wuyao.vimax.entity.ProviderConfig;
import com.wuyao.vimax.repository.ProviderConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Provider 配置服务
 *
 * 管理 AI 供应商的 API Key 和配置
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProviderConfigService {

    private final ProviderConfigRepository providerConfigRepository;

    // 用于加密 API Key 的密钥（生产环境应从配置文件读取）
    private static final String ENCRYPTION_KEY = "WuYaoViMax2026!!";

    /**
     * 获取激活的供应商配置
     */
    public ProviderConfig getActiveConfig(String providerType) {
        return providerConfigRepository.findFirstByProviderTypeAndIsActiveTrueOrderByPriorityDesc(providerType)
                .orElseThrow(() -> new IllegalStateException("未找到激活的供应商配置: " + providerType));
    }

    /**
     * 解密 API Key
     */
    public String decryptApiKey(String encryptedKey) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(
                    ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8),
                    "AES"
            );

            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);

            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedKey));
            return new String(decrypted, StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("解密 API Key 失败", e);
            throw new RuntimeException("解密失败: " + e.getMessage());
        }
    }

    /**
     * 加密 API Key
     */
    public String encryptApiKey(String apiKey) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(
                    ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8),
                    "AES"
            );

            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);

            byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);

        } catch (Exception e) {
            log.error("加密 API Key 失败", e);
            throw new RuntimeException("加密失败: " + e.getMessage());
        }
    }

    /**
     * 获取解密后的 API Key
     */
    public String getApiKey(String providerType) {
        ProviderConfig config = getActiveConfig(providerType);
        return decryptApiKey(config.getApiKeyEncrypted());
    }

    /**
     * 检查限流配置
     */
    public boolean checkRateLimit(String providerType) {
        ProviderConfig config = getActiveConfig(providerType);
        // TODO: 调用 RateLimitService 检查
        return true;
    }
}
