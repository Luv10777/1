package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Merchant Repository
 */
@Repository
public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    /**
     * 根据租户ID查询商家列表
     */
    List<Merchant> findByTenantId(Long tenantId);

    /**
     * 根据状态查询商家
     */
    List<Merchant> findByStatus(String status);
}
