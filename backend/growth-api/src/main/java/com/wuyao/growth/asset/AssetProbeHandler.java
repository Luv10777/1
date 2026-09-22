package com.wuyao.growth.asset;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import com.wuyao.growth.common.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 示例任务处理器 —— 加异步任务就照这个写。
 *
 * 进到 handle 时租户上下文已经切好了，直接查自己的表即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetProbeHandler implements TaskHandler {

    public static final String TYPE = "ASSET_PROBE";

    private final AssetRepository repository;
    private final ObjectStorage storage;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> handle(Task task) {
        Long assetId = ((Number) task.getPayload().get("assetId")).longValue();
        Asset asset = repository.findById(assetId)
                .orElseThrow(() -> new IllegalStateException("素材不存在: " + assetId));

        storage.stat(asset.getStorageKey()).orElseThrow(() -> new IllegalStateException("素材文件不存在"));
        // 宽高、时长和 SHA-256 尚未解析，不能宣称 probed=true。
        log.info("素材文件存在，媒体元数据尚未解析: id={}", assetId);

        return Map.of("assetId", assetId, "objectVerified", true, "probed", false);
    }
}
