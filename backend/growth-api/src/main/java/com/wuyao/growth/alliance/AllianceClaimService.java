package com.wuyao.growth.alliance;

import com.wuyao.growth.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 领券编排。一个事务只能落在一家商户（连接取出时写入租户号），所以拆成三步、各自提交：
 * 领券方扣额度 → 供券方扣名额 → 领券方定结果。这里不能加 @Transactional。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AllianceClaimService {
    private final AllianceVoucherService vouchers;
    private final AllianceOfferService offers;

    public AllianceDtos.VoucherView claim(Long offerId, Long userId) {
        var ticket = vouchers.startClaim(offerId, userId);
        boolean reserved;
        try {
            reserved = reserve(ticket);
        } catch (RuntimeException e) {
            // 券保持「领取中」，第 1 步登记的收尾任务会补完。
            log.warn("联盟券领取第 2 步失败，交给收尾任务: ref={}", ticket.ref(), e);
            return vouchers.finishClaimLater(ticket.ref());
        }
        return vouchers.finishClaim(ticket.ref(), reserved);
    }

    /** 在供券方扣名额。券码哈希极小概率撞上供券方已有的券码，按领取失败处理、额度退回，可以再领一次。 */
    boolean reserve(AllianceOfferService.ClaimTicket ticket) {
        try {
            return TenantContext.runAs(ticket.supplierTenantId(), () -> offers.reserveClaim(ticket));
        } catch (DataIntegrityViolationException collision) {
            return TenantContext.runAs(ticket.supplierTenantId(), () -> offers.claimExists(ticket.ref()));
        }
    }
}
