package com.wuyao.growth.notice;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.iam.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 站内通知。其他模块在要通知的那个商户的租户里调用 {@link #notifyOwners}，与业务写入同一事务：
 * 业务回滚，通知也不会留下；同一事件用相同的 dedupeKey，重复调用只记一条。
 * 以后接短信时在这里加发送渠道，调用方不用改。
 */
@Service
@RequiredArgsConstructor
public class NoticeService {
    private final NoticeRepository notices;
    private final AccountService accounts;

    @Transactional
    public void notifyOwners(String category, String title, String body, String link, String dedupeKey) {
        Long tenantId = TenantContext.require();
        for (Long owner : accounts.activeOwnerIds()) {
            notices.insertIfAbsent(tenantId, owner, category, title, body, link, dedupeKey);
        }
    }

    @Transactional(readOnly = true)
    public PageResult<NoticeDtos.NoticeView> mine(Long userId, int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw BizException.of(ErrorCode.BAD_REQUEST, "page 必须非负，size 必须在 1 到 50 之间");
        var result = notices.findAllByTenantIdAndUserIdOrderByCreatedAtDescIdDesc(TenantContext.require(), userId, PageRequest.of(page, size));
        return PageResult.of(result, result.getContent().stream().map(NoticeDtos.NoticeView::of).toList());
    }

    @Transactional(readOnly = true)
    public NoticeDtos.UnreadView unread(Long userId) {
        return new NoticeDtos.UnreadView(notices.countByTenantIdAndUserIdAndReadAtIsNull(TenantContext.require(), userId));
    }

    @Transactional
    public void markRead(Long id, Long userId) {
        var notice = notices.findById(id)
                .filter(n -> TenantContext.require().equals(n.getTenantId()) && userId.equals(n.getUserId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "通知不存在"));
        if (notice.getReadAt() == null) notice.setReadAt(Instant.now());
    }

    @Transactional
    public void markAllRead(Long userId) {
        notices.markAllRead(TenantContext.require(), userId, Instant.now());
    }
}
