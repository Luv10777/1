package com.wuyao.growth.iam.repository;

import com.wuyao.growth.iam.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
}
