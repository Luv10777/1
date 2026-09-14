package com.wuyao.growth.asset;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
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

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> handle(Task task) {
        Long assetId = ((Number) task.getPayload().get("assetId")).longValue();
        Asset asset = repository.findById(assetId)
                .orElseThrow(() -> new IllegalStateException("素材不存在: " + assetId));

        // TODO 真实实现：从对象存储读文件头，解析图片宽高 / 视频时长，回写字段。
        log.info("补素材元数据: id={} key={}", asset.getId(), asset.getStorageKey());

        return Map.of("assetId", assetId, "probed", true);
    }
}
