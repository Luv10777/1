package com.wuyao.vimax.controller;

import com.wuyao.vimax.dto.ApiResponse;
import com.wuyao.vimax.dto.MerchantFactSnapshotResponse;
import com.wuyao.vimax.entity.MerchantFactSnapshot;
import com.wuyao.vimax.service.MerchantFactSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 商家事实快照 Controller
 */
@RestController
@RequestMapping("/merchants/{merchantId}/snapshots")
@RequiredArgsConstructor
@Slf4j
public class MerchantFactSnapshotController {

    private final MerchantFactSnapshotService snapshotService;

    /**
     * 生成商家事实快照
     */
    @PostMapping
    public ApiResponse<MerchantFactSnapshotResponse> createSnapshot(
            @PathVariable Long merchantId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {

        log.info("收到创建快照请求：merchantId={}, userId={}", merchantId, userId);

        MerchantFactSnapshot snapshot = snapshotService.createSnapshot(merchantId, userId);
        return ApiResponse.success("快照创建成功", toResponse(snapshot));
    }

    /**
     * 获取商家的所有快照
     */
    @GetMapping
    public ApiResponse<List<MerchantFactSnapshotResponse>> getSnapshots(
            @PathVariable Long merchantId) {

        log.info("查询商家快照列表：merchantId={}", merchantId);

        List<MerchantFactSnapshot> snapshots = snapshotService.getSnapshotsByMerchant(merchantId);
        List<MerchantFactSnapshotResponse> responses = snapshots.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return ApiResponse.success(responses);
    }

    /**
     * 获取快照详情
     */
    @GetMapping("/{snapshotId}")
    public ApiResponse<MerchantFactSnapshotResponse> getSnapshot(
            @PathVariable Long merchantId,
            @PathVariable Long snapshotId) {

        log.info("查询快照详情：merchantId={}, snapshotId={}", merchantId, snapshotId);

        MerchantFactSnapshot snapshot = snapshotService.getSnapshotById(snapshotId);

        if (!snapshot.getMerchantId().equals(merchantId)) {
            return ApiResponse.error(403, "无权访问该快照");
        }

        return ApiResponse.success(toResponse(snapshot));
    }

    /**
     * 实体转 DTO
     */
    private MerchantFactSnapshotResponse toResponse(MerchantFactSnapshot snapshot) {
        MerchantFactSnapshotResponse response = new MerchantFactSnapshotResponse();
        response.setId(snapshot.getId());
        response.setTenantId(snapshot.getTenantId());
        response.setMerchantId(snapshot.getMerchantId());
        response.setSnapshotVersion(snapshot.getSnapshotVersion());
        response.setSnapshotCode(snapshot.getSnapshotCode());
        response.setCreatedAt(snapshot.getCreatedAt());
        return response;
    }
}
