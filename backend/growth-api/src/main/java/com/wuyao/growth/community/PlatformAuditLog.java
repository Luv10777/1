package com.wuyao.growth.community;

import com.wuyao.growth.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 供其他模块往平台管理审计表里记一笔（例如平台查看完整收款账号）。在被操作商户的租户里调用。 */
@Service
@RequiredArgsConstructor
public class PlatformAuditLog {
    private final PlatformManagementAuditRepository audits;

    @Transactional
    public void record(Long actorUserId, String targetType, Long targetId, String action, String note) {
        var event = new PlatformManagementAudit();
        event.setTenantId(TenantContext.require());
        event.setActorUserId(actorUserId);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setAction(action);
        event.setNote(note.trim());
        audits.saveAndFlush(event);
    }
}
