package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.ProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Provider 配置 Repository
 */
@Repository
public interface ProviderConfigRepository extends JpaRepository<ProviderConfig, Long> {

    /**
     * 查找激活的供应商配置（按优先级降序）
     */
    Optional<ProviderConfig> findFirstByProviderTypeAndIsActiveTrueOrderByPriorityDesc(String providerType);

    /**
     * 查找所有激活的配置
     */
    List<ProviderConfig> findByIsActiveTrue();

    /**
     * 按供应商类型查找
     */
    List<ProviderConfig> findByProviderType(String providerType);
}
