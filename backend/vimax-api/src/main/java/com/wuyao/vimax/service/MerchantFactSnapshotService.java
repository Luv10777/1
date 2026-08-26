package com.wuyao.vimax.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.vimax.entity.MerchantFact;
import com.wuyao.vimax.entity.MerchantFactSnapshot;
import com.wuyao.vimax.repository.MerchantFactRepository;
import com.wuyao.vimax.repository.MerchantFactSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商家事实快照服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantFactSnapshotService {

    private final MerchantFactRepository factRepository;
    private final MerchantFactSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    /**
     * 生成商家事实快照
     */
    @Transactional
    public MerchantFactSnapshot createSnapshot(Long merchantId, Long createdBy) {
        log.info("开始生成商家 {} 的事实快照", merchantId);

        // 1. 查询当前生效的事实
        List<MerchantFact> effectiveFacts = factRepository.findEffectiveFacts(
                merchantId, LocalDateTime.now());

        if (effectiveFacts.isEmpty()) {
            throw new IllegalStateException("商家没有生效的事实，无法生成快照");
        }

        // 2. 按类型分组
        Map<String, List<Map<String, Object>>> groupedFacts = groupFactsByType(effectiveFacts);

        // 3. 序列化为 JSON
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(groupedFacts);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("快照序列化失败", e);
        }

        // 4. 计算 SHA-256 哈希
        String hash = calculateSha256(snapshotJson);

        // 5. 检查是否已存在相同快照（去重）
        if (snapshotRepository.existsBySnapshotHash(hash)) {
            log.info("商家 {} 的快照已存在（哈希: {}），返回现有快照", merchantId, hash);
            return snapshotRepository.findBySnapshotHash(hash)
                    .orElseThrow(() -> new RuntimeException("快照查询失败"));
        }

        // 6. 创建新快照
        MerchantFactSnapshot snapshot = new MerchantFactSnapshot();
        snapshot.setMerchantId(merchantId);
        snapshot.setSnapshotHash(hash);
        snapshot.setFactsSummary(snapshotJson);
        snapshot.setIsComplete(true);

        MerchantFactSnapshot saved = snapshotRepository.save(snapshot);
        log.info("商家 {} 快照创建成功，ID: {}, 哈希: {}", merchantId, saved.getId(), hash);

        // TODO: 写入 Outbox 事件（异步通知）

        return saved;
    }

    /**
     * 按类型分组事实
     */
    private Map<String, List<Map<String, Object>>> groupFactsByType(List<MerchantFact> facts) {
        return facts.stream()
                .collect(Collectors.groupingBy(
                        MerchantFact::getFactType,
                        Collectors.mapping(this::toFactMap, Collectors.toList())
                ));
    }

    /**
     * 将事实转换为 Map
     */
    private Map<String, Object> toFactMap(MerchantFact fact) {
        Map<String, Object> map = new HashMap<>();
        map.put("key", fact.getFactKey());
        map.put("value", fact.getFactValue());
        map.put("source", fact.getSource());
        map.put("isCritical", fact.getIsCritical());
        return map;
    }

    /**
     * 计算 SHA-256 哈希
     */
    private String calculateSha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 查询商家的所有快照
     */
    public List<MerchantFactSnapshot> getSnapshotsByMerchant(Long merchantId) {
        return snapshotRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    /**
     * 根据 ID 查询快照
     */
    public MerchantFactSnapshot getSnapshotById(Long snapshotId) {
        return snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("快照不存在: " + snapshotId));
    }
}
