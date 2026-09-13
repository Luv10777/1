package com.wuyao.growth.asset;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 示例模块。上传的完整链路长这样，其他模块照抄：
 *   1. presignUpload  发预签名 URL，落一条 PENDING 记录
 *   2. 前端拿 URL 直接 PUT 到对象存储（不经过后端）
 *   3. confirmUpload  确认落库，并提交一个异步任务去补元数据
 */
@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetRepository repository;
    private final ObjectStorage storage;
    private final TaskService taskService;

    @Value("${growth.storage.presign-ttl:15m}")
    private Duration presignTtl;

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
        Asset asset = repository.findById(assetId)
                .orElseThrow(() -> BizException.of(ErrorCode.ASSET_NOT_FOUND, "素材不存在"));
        asset.setStatus("READY");
        asset.setSizeBytes(req.sizeBytes());
        asset.setSha256(req.sha256());

        // 交给 worker 去补宽高/时长这类要读文件才知道的元数据
        taskService.submit(AssetProbeHandler.TYPE, "DEFAULT",
                Map.of("assetId", assetId), "asset-probe-" + assetId, userId);

        return AssetDtos.AssetView.of(asset);
    }

    @Transactional(readOnly = true)
    public PageResult<AssetDtos.AssetView> list(int page, int size) {
        var result = repository.findAllByOrderByIdDesc(PageRequest.of(page, size));
        return PageResult.of(result, result.getContent().stream().map(AssetDtos.AssetView::of).toList());
    }
}
