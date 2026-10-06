package com.wuyao.growth.asset;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.iam.repository.TenantRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * 示例模块。上传的完整链路长这样，其他模块照抄：
 *   1. presignUpload  发预签名 URL，落一条 PENDING 记录
 *   2. 前端拿 URL 直接 PUT 到对象存储（不经过后端）
 *   3. confirmUpload  确认落库，并提交一个异步任务去补元数据
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssetService {

    private final AssetRepository repository;
    private final ObjectStorage storage;
    private final TaskService taskService;
    private final TenantRepository tenantRepository;
    private final TransactionTemplate transactions;

    @Value("${growth.storage.presign-ttl:15m}")
    private Duration presignTtl;
    @Value("${growth.image.upload-max-pixels:50000000}")
    private long maxImagePixels;
    @Value("${growth.video.reference-max-bytes:524288000}")
    private long maxVideoReferenceBytes;
    @Value("${growth.video.reference-max-duration-seconds:300}")
    private int maxVideoReferenceDurationSeconds;
    @Value("${growth.video.reference-max-width:4096}")
    private int maxVideoReferenceWidth;
    @Value("${growth.video.reference-max-height:4096}")
    private int maxVideoReferenceHeight;
    @Value("${growth.video.reference-presign-ttl-seconds:21600}")
    private long referencePresignTtlSeconds = 21600;
    @Value("${growth.video.max-duration-seconds:7200}")
    private long videoMaxDurationSeconds = 7200;
    @Value("${growth.video.reference-url-safety-seconds:300}")
    private long referenceUrlSafetySeconds = 300;
    @Value("${growth.video.provider.timeout-seconds:60}")
    private long videoProviderTimeoutSeconds = 60;

    @PostConstruct
    void validateReferencePresignLifetime() {
        referencePresignLifetime();
    }

    private Duration referencePresignLifetime() {
        // SigV4/MinIO permits at most seven days. Do not silently truncate a longer workflow.
        long maxSeconds = 604800;
        if (referencePresignTtlSeconds < 1 || referencePresignTtlSeconds > maxSeconds
                || videoMaxDurationSeconds < 1 || videoMaxDurationSeconds > maxSeconds
                || referenceUrlSafetySeconds < 1 || referenceUrlSafetySeconds > maxSeconds
                || videoProviderTimeoutSeconds < 1 || videoProviderTimeoutSeconds > maxSeconds) {
            throw new IllegalArgumentException("视频素材签名和时限配置必须在 1 到 604800 秒之间");
        }
        // Include connection-pool wait (10s), connect timeout (15s), and provider response timeout.
        long safetySeconds = Math.max(referenceUrlSafetySeconds, videoProviderTimeoutSeconds + 25);
        long ttlSeconds = Math.max(referencePresignTtlSeconds, videoMaxDurationSeconds + safetySeconds);
        if (ttlSeconds > maxSeconds) {
            throw new IllegalArgumentException("视频处理时限加提交安全余量不能超过对象存储签名的 7 天上限");
        }
        return Duration.ofSeconds(ttlSeconds);
    }

    @Transactional(readOnly = true)
    public ImageReference imageReference(Long id) {
        Asset a = repository.findById(id).orElseThrow(() -> BizException.of(ErrorCode.ASSET_NOT_FOUND, "素材不存在"));
        if (!"READY".equals(a.getStatus()) || !"IMAGE".equals(a.getType())
                || a.getSizeBytes() == null || a.getSizeBytes() > 20 * 1024 * 1024) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请使用已上传完成且小于 20 MB 的图片");
        }
        return new ImageReference(a.getId(), a.getStorageKey(), a.getName());
    }

    /** Reference validation for video workflows; metadata probing happens asynchronously. */
    @Transactional(readOnly = true)
    public MediaReference mediaReference(Long id, String expectedType) {
        Asset asset = repository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCode.ASSET_NOT_FOUND, "素材不存在"));
        if (!"READY".equals(asset.getStatus()) || !expectedType.equals(asset.getType())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请使用已上传完成的" + expectedType + "素材");
        }
        if ("VIDEO".equals(expectedType)) {
            if (asset.getSizeBytes() == null || asset.getSizeBytes() <= 0 || asset.getSizeBytes() > maxVideoReferenceBytes
                    || asset.getDurationMs() == null || asset.getDurationMs() <= 0
                    || asset.getDurationMs() > maxVideoReferenceDurationSeconds * 1000L
                    || asset.getWidth() == null || asset.getWidth() < 1 || asset.getWidth() > maxVideoReferenceWidth
                    || asset.getHeight() == null || asset.getHeight() < 1 || asset.getHeight() > maxVideoReferenceHeight
                    || asset.getMimeType() == null || !asset.getMimeType().toLowerCase(java.util.Locale.ROOT).startsWith("video/")) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "参考视频尚未完成媒体校验，或超过支持范围");
            }
        }
        return new MediaReference(asset.getId(), asset.getStorageKey(), asset.getMimeType(), asset.getDurationMs());
    }

    public String presignedReference(Long id, String expectedType) {
        MediaReference reference = mediaReference(id, expectedType);
        return storage.presignGet(reference.storageKey(), referencePresignLifetime());
    }

    public record MediaReference(Long id, String storageKey, String mimeType, Integer durationMs) {}

    public record ImageReference(Long id, String storageKey, String name) {}

    @Transactional
    public AssetDtos.UploadTicket presignUpload(AssetDtos.PresignRequest req, Long userId) {
        Long tenantId = TenantContext.require();
        String key = "t%d/%s/%s".formatted(tenantId, req.type().toLowerCase(), UUID.randomUUID());

        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setName(req.name());
        asset.setType(req.type());
        asset.setStorageKey(key);
        asset.setMimeType(req.mimeType());
        asset.setCreatedBy(userId);
        repository.save(asset);

        return new AssetDtos.UploadTicket(asset.getId(), key, storage.presignPut(key, presignTtl));
    }

    @Transactional
    public AssetDtos.AssetView confirmUpload(Long assetId, AssetDtos.ConfirmRequest req, Long userId) {
        Asset asset = repository.findForUpdate(assetId)
                .orElseThrow(() -> BizException.of(ErrorCode.ASSET_NOT_FOUND, "素材不存在"));
        if ("READY".equals(asset.getStatus()) && !("VIDEO".equals(asset.getType())
                && (asset.getDurationMs() == null || asset.getWidth() == null || asset.getHeight() == null))) {
            return view(asset);
        }
        var stored = storage.stat(asset.getStorageKey())
                .orElseThrow(() -> BizException.of(ErrorCode.ASSET_UPLOAD_FAILED, "文件尚未上传完成"));
        if (req.sizeBytes() != null && req.sizeBytes() != stored.sizeBytes()) {
            throw BizException.of(ErrorCode.ASSET_UPLOAD_FAILED, "文件大小与上传声明不一致");
        }
        asset.setStatus("VIDEO".equals(asset.getType()) ? "VERIFYING" : "READY");
        asset.setSizeBytes(stored.sizeBytes());
        if ("IMAGE".equals(asset.getType())) {
            byte[] bytes = storage.read(asset.getStorageKey(), 20 * 1024 * 1024);
            BufferedImage image;
            try {
                image = ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (Exception e) {
                throw BizException.of(ErrorCode.ASSET_UPLOAD_FAILED, "图片文件无法解析");
            }
            if (image == null) throw BizException.of(ErrorCode.ASSET_UPLOAD_FAILED, "只支持有效的 PNG 或 JPEG 图片");
            long pixels = (long) image.getWidth() * image.getHeight();
            if (pixels > maxImagePixels) {
                throw BizException.of(ErrorCode.ASSET_UPLOAD_FAILED, "图片像素过高，请压缩后重试");
            }
            asset.setWidth(image.getWidth());
            asset.setHeight(image.getHeight());
            asset.setMimeType(stored.contentType());
            asset.setSha256(sha256(bytes));
        } else if ("VIDEO".equals(asset.getType())) {
            if (stored.sizeBytes() <= 0 || stored.sizeBytes() > maxVideoReferenceBytes) {
                throw BizException.of(ErrorCode.ASSET_UPLOAD_FAILED, "参考视频超过大小限制");
            }
            String type = stored.contentType() == null ? "" : stored.contentType().toLowerCase(java.util.Locale.ROOT);
            if (!type.isBlank() && !type.startsWith("video/") && !"application/octet-stream".equals(type)) {
                throw BizException.of(ErrorCode.ASSET_UPLOAD_FAILED, "参考视频的媒体类型无效");
            }
            asset.setMimeType(stored.contentType());
        } else {
            asset.setMimeType(stored.contentType());
        }
        asset.setUpdatedAt(java.time.Instant.now());

        // 交给 worker 去补宽高/时长这类要读文件才知道的元数据
        taskService.submit(AssetProbeHandler.TYPE, "DEFAULT",
                Map.of("assetId", assetId), "asset-probe-" + assetId + "-" + asset.getUpdatedAt().toEpochMilli(), userId);

        return view(asset);
    }

    @Transactional(readOnly = true)
    public PageResult<AssetDtos.AssetView> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "page 必须非负，size 必须在 1 到 100 之间");
        }
        var result = repository.findAllByOrderByIdDesc(PageRequest.of(page, size));
        return PageResult.of(result, result.getContent().stream().map(this::view).toList());
    }

    private AssetDtos.AssetView view(Asset asset) {
        String previewUrl = "READY".equals(asset.getStatus()) && "IMAGE".equals(asset.getType())
                ? storage.presignGet(asset.getStorageKey(), Duration.ofMinutes(30)) : null;
        return AssetDtos.AssetView.of(asset, previewUrl);
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append("%02x".formatted(value & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算图片校验值失败", e);
        }
    }

    /** Remove abandoned direct-upload objects that never reached confirm. */
    @Scheduled(fixedDelayString = "${growth.storage.cleanup-interval-ms:3600000}")
    public void cleanupAbandonedUploads() {
        var cutoff = java.time.Instant.now().minus(Duration.ofHours(2));
        Long after = 0L;
        while (true) {
            var tenantIds = tenantRepository.idsAfter(after, PageRequest.of(0, 100));
            if (tenantIds.isEmpty()) return;
            for (Long tenantId : tenantIds) {
                // Set the context BEFORE opening Hibernate's transaction/session.
                TenantContext.runAs(tenantId, () -> {
                    var ids = transactions.execute(status ->
                            repository.abandonedUploadIds(cutoff, PageRequest.of(0, 100)));
                    for (Long id : ids) {
                        try {
                            transactions.executeWithoutResult(status -> {
                                // Serialize with confirmUpload and recheck status under the lock.
                                var asset = repository.findForUpdate(id).orElse(null);
                                if (asset == null || !"PENDING".equals(asset.getStatus())
                                        || !asset.getCreatedAt().isBefore(cutoff)) return;
                                storage.delete(asset.getStorageKey());
                                repository.delete(asset);
                            });
                        } catch (RuntimeException e) {
                            log.warn("清理未确认上传失败，保留记录等待重试: assetId={}", id, e);
                        }
                    }
                    return null;
                });
            }
            after = tenantIds.getLast();
        }
    }
}
