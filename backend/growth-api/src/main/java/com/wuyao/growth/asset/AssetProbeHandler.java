package com.wuyao.growth.asset;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Verifies uploaded media before it can be used as a video reference. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetProbeHandler implements TaskHandler {

    public static final String TYPE = "ASSET_PROBE";

    private final AssetRepository repository;
    private final ObjectStorage storage;
    private final VideoMediaProbe videoProbe;

    @Value("${growth.video.reference-max-bytes:524288000}")
    private long maxVideoBytes;
    @Value("${growth.video.reference-max-duration-seconds:300}")
    private int maxVideoDurationSeconds;
    @Value("${growth.video.reference-max-width:4096}")
    private int maxVideoWidth;
    @Value("${growth.video.reference-max-height:4096}")
    private int maxVideoHeight;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    @Transactional
    public Map<String, Object> handle(Task task) {
        Long assetId = ((Number) task.getPayload().get("assetId")).longValue();
        Asset asset = repository.findById(assetId)
                .orElseThrow(() -> new IllegalStateException("素材不存在: " + assetId));
        var stored = storage.stat(asset.getStorageKey()).orElseThrow(() -> new IllegalStateException("素材文件不存在"));
        if (!"VIDEO".equals(asset.getType())) {
            log.info("素材对象存在，图片元数据已在确认阶段校验: id={}", assetId);
            return Map.of("assetId", assetId, "objectVerified", true, "probed", true);
        }

        try {
            if (stored.sizeBytes() <= 0 || stored.sizeBytes() > maxVideoBytes) {
                throw new IllegalStateException("参考视频超过大小限制");
            }
            var metadata = videoProbe.probe(asset.getStorageKey(), stored.contentType());
            if (metadata.durationMs() > maxVideoDurationSeconds * 1000L
                    || metadata.width() > maxVideoWidth || metadata.height() > maxVideoHeight) {
                throw new IllegalStateException("参考视频的时长或分辨率超过限制");
            }
            asset.setWidth(metadata.width());
            asset.setHeight(metadata.height());
            asset.setDurationMs(metadata.durationMs());
            asset.setMimeType(metadata.mimeType());
            asset.setSizeBytes(stored.sizeBytes());
            asset.setStatus("READY");
            repository.saveAndFlush(asset);
            log.info("视频素材探测完成: id={} width={} height={} durationMs={}", assetId,
                    metadata.width(), metadata.height(), metadata.durationMs());
            return Map.of("assetId", assetId, "objectVerified", true, "probed", true,
                    "width", metadata.width(), "height", metadata.height(), "durationMs", metadata.durationMs());
        } catch (IllegalStateException e) {
            asset.setStatus("INVALID");
            repository.saveAndFlush(asset);
            log.warn("视频素材校验失败，标记为无效: id={} reason={}", assetId, e.getMessage());
            return Map.of("assetId", assetId, "objectVerified", true, "probed", false, "status", "INVALID");
        }
    }
}
