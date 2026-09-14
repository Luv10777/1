package com.wuyao.vimax.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.vimax.entity.Merchant;
import com.wuyao.vimax.entity.MerchantFactSnapshot;
import com.wuyao.vimax.repository.MerchantFactSnapshotRepository;
import com.wuyao.vimax.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 商家快照服务
 *
 * Phase 2.1: 创建和管理商家事实快照
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantSnapshotService {

    private final MerchantRepository merchantRepository;
    private final MerchantFactSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    /**
     * 创建商家快照
     *
     * @param merchantId 商家ID
     * @return 快照记录
     */
    @Transactional
    public MerchantFactSnapshot createSnapshot(Long merchantId) {
        log.info("创建商家快照: merchantId={}", merchantId);

        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new IllegalArgumentException("商家不存在: " + merchantId));

        MerchantFactSnapshot snapshot = new MerchantFactSnapshot();
        snapshot.setMerchantId(merchantId);
        snapshot.setSnapshotCode(generateSnapshotCode());
        snapshot.setSnapshotVersion("v1.0");
        snapshot.setMerchantName(merchant.getName());
        snapshot.setMerchantType(merchant.getType());
        snapshot.setIndustry(merchant.getIndustry());
        snapshot.setBusinessHours(merchant.getBusinessHours());
        snapshot.setAddress(merchant.getAddress());
        snapshot.setContactPhone(merchant.getContactPhone());
        snapshot.setDescription(merchant.getDescription());
        snapshot.setTags(merchant.getTags());

        // TODO: 从其他表聚合更多信息
        // - product_categories: 从 products 表聚合
        // - key_products: 从 products 表筛选
        // - selling_points: 从 merchant_profiles 提取
        // - target_audience: 从配置或分析得出
        // - brand_voice: 从历史文案分析
        // - competitors: 从市场分析
        // - marketing_goals: 从配置读取

        // 临时填充
        snapshot.setProductCategories("待聚合");
        snapshot.setKeyProducts("待聚合");
        snapshot.setSellingPoints("待聚合");
        snapshot.setTargetAudience("18-45岁");
        snapshot.setBrandVoice("亲切、专业");

        // 额外信息（JSONB）
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("created_for", "workflow");
        additionalInfo.put("source", "merchant_table");
        try {
            snapshot.setAdditionalInfo(objectMapper.writeValueAsString(additionalInfo));
        } catch (Exception e) {
            log.warn("序列化额外信息失败", e);
        }

        MerchantFactSnapshot saved = snapshotRepository.save(snapshot);
        log.info("商家快照已创建: snapshotId={}, code={}", saved.getId(), saved.getSnapshotCode());

        return saved;
    }

    /**
     * 获取商家最新快照
     */
    public MerchantFactSnapshot getLatestSnapshot(Long merchantId) {
        return snapshotRepository.findFirstByMerchantIdOrderByCreatedAtDesc(merchantId)
            .orElseThrow(() -> new IllegalArgumentException("商家无快照: " + merchantId));
    }

    /**
     * 根据快照代码查询
     */
    public MerchantFactSnapshot getSnapshotByCode(String snapshotCode) {
        return snapshotRepository.findBySnapshotCode(snapshotCode)
            .orElseThrow(() -> new IllegalArgumentException("快照不存在: " + snapshotCode));
    }

    /**
     * 生成快照代码
     */
    private String generateSnapshotCode() {
        return "SNAP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
