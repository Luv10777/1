package com.wuyao.growth.asset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public final class AssetDtos {

    private AssetDtos() {
    }

    public record PresignRequest(
            @NotBlank(message = "名称不能为空") String name,
            @Pattern(regexp = "IMAGE|VIDEO|AUDIO|DOCUMENT", message = "类型不合法") String type,
            String mimeType) {
    }

    public record UploadTicket(Long assetId, String storageKey, String uploadUrl) {
    }

    public record ConfirmRequest(Long sizeBytes, String sha256) {
    }

    public record AssetView(Long id, String name, String type, String status,
                            String storageKey, Long sizeBytes, Instant createdAt) {

        public static AssetView of(Asset a) {
            return new AssetView(a.getId(), a.getName(), a.getType(), a.getStatus(),
                    a.getStorageKey(), a.getSizeBytes(), a.getCreatedAt());
        }
    }
}
