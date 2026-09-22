package com.wuyao.growth.asset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AssetDtos {

    private AssetDtos() {
    }

    public record PresignRequest(
            @NotBlank(message = "名称不能为空") @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "IMAGE|VIDEO|AUDIO|DOCUMENT", message = "类型不合法") String type,
            @Size(max = 120) String mimeType) {
    }

    public record UploadTicket(Long assetId, String storageKey, String uploadUrl) {
    }

    // sha256 兼容旧请求；服务端尚未计算校验值，不能把客户端声明存为可信哈希。
    public record ConfirmRequest(@PositiveOrZero Long sizeBytes,
                                 @Pattern(regexp = "[a-fA-F0-9]{64}") String sha256) {
    }

    public record AssetView(Long id, String name, String type, String status,
                            String storageKey, Long sizeBytes, Instant createdAt) {

        public static AssetView of(Asset a) {
            return new AssetView(a.getId(), a.getName(), a.getType(), a.getStatus(),
                    a.getStorageKey(), a.getSizeBytes(), a.getCreatedAt());
        }
    }
}
