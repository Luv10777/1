package com.wuyao.growth.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PlatformManagementAuditRepository extends JpaRepository<PlatformManagementAudit, Long> {
    List<PlatformManagementAudit> findTop10ByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDescIdDesc(
            Long tenantId, String targetType, Long targetId);

    long countByTenantIdAndActorUserIdAndTargetTypeAndActionAndCreatedAtAfter(
            Long tenantId, Long actorUserId, String targetType, String action, Instant after);
}
