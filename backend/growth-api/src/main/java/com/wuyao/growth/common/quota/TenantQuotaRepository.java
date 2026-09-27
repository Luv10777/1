package com.wuyao.growth.common.quota;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantQuotaRepository extends JpaRepository<TenantQuota, Long> {
}
