package com.wuyao.growth.alliance;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 供券商户这一侧的结算单、过期和申诉处理。都在供券商户的租户里执行，每个方法都可以重复执行。 */
@Service
@RequiredArgsConstructor
public class AllianceSettlementService {
    private final AllianceSettlementRepository settlements;
    private final AllianceOfferClaimRepository claims;
    private final AllianceOfferRepository offers;
    private final ProductService products;

    @Transactional(readOnly = true)
    public PageResult<AllianceDtos.SettlementView> mine(int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw BizException.of(ErrorCode.BAD_REQUEST, "page 必须非负，size 必须在 1 到 50 之间");
        Instant now = Instant.now();
        var result = settlements.findAllByTenantIdOrderByCreatedAtDescIdDesc(TenantContext.require(), PageRequest.of(page, size));
        return PageResult.of(result, result.getContent().stream().map(s -> AllianceDtos.SettlementView.of(s, now)).toList());
    }

    /**
     * 到期的领取记录改「已过期」，那 1 件库存还给商品。
     * @return 这张券在供券方已是「已过期」时返回领券商户，用来同步领券方；已核销等情况为空
     */
    @Transactional
    public Optional<Long> expire(UUID ref) {
        var claim = claims.findForUpdate(ref).orElse(null);
        if (claim == null) return Optional.empty();
        if ("CLAIMED".equals(claim.getStatus()) && !Instant.now().isBefore(claim.getExpiresAt())) {
            claim.setStatus("EXPIRED");
            claim.setUpdatedAt(Instant.now());
            offers.findById(claim.getOfferId()).ifPresent(o -> products.restoreInventory(o.getProductId(), 1));
        }
        return "EXPIRED".equals(claim.getStatus()) ? Optional.of(claim.getHolderTenantId()) : Optional.empty();
    }

    /** 当前租户里已到期、还没处理的领取记录。 */
    @Transactional(readOnly = true)
    public List<UUID> dueForExpiry(int limit) {
        return claims.dueForExpiry(Instant.now(), PageRequest.of(0, limit));
    }

    /** 领券方申诉：结算单冻结，平台处理前不能结算。 */
    @Transactional
    public void freeze(UUID ref) {
        settlements.findForUpdate(ref).filter(s -> "HOLDING".equals(s.getStatus())).ifPresent(s -> {
            s.setFrozen(true);
            s.setUpdatedAt(Instant.now());
        });
    }

    /**
     * 平台判定后更新供券方。核销有效：结算单解冻。核销无效：结算单作废，
     * 领取记录回到「已领取」可再次核销；券已过期则直接作废并把库存还给商品。
     */
    @Transactional
    public void resolveDispute(UUID ref, boolean valid) {
        var settlement = settlements.findForUpdate(ref).orElse(null);
        if (settlement == null || !"HOLDING".equals(settlement.getStatus())) return;
        Instant now = Instant.now();
        settlement.setUpdatedAt(now);
        if (valid) {
            settlement.setFrozen(false);
            return;
        }
        settlement.setStatus("VOID");
        settlement.setFrozen(false);
        claims.findForUpdate(ref).filter(c -> "REDEEMED".equals(c.getStatus())).ifPresent(c -> {
            c.setRedeemedStoreId(null);
            c.setRedeemedBy(null);
            c.setRedeemedAt(null);
            c.setUpdatedAt(now);
            if (now.isBefore(c.getExpiresAt())) {
                c.setStatus("CLAIMED");
            } else {
                c.setStatus("EXPIRED");
                offers.findById(c.getOfferId()).ifPresent(o -> products.restoreInventory(o.getProductId(), 1));
            }
        });
    }

    @Transactional(readOnly = true)
    public AllianceSettlement require(Long id) {
        return settlements.findById(id).filter(s -> TenantContext.require().equals(s.getTenantId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "结算单不存在"));
    }

    /** 平台线下打款后标记已结。申诉期内、冻结中和已作废的结算单不能标记。 */
    @Transactional
    public AllianceDtos.SettlementView markPaid(Long id, String reference, Long operatorId) {
        var settlement = settlements.findByIdForUpdate(id).filter(s -> TenantContext.require().equals(s.getTenantId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "结算单不存在"));
        Instant now = Instant.now();
        String state = AllianceDtos.SettlementView.state(settlement, now);
        if (!"PAYABLE".equals(state)) {
            throw BizException.of(ErrorCode.CONFLICT, switch (state) {
                case "IN_DISPUTE_WINDOW" -> "还在申诉期内，期满后才能结算";
                case "FROZEN" -> "领券商户正在申诉，处理完才能结算";
                case "PAID" -> "这张结算单已经结算过了";
                default -> "这张结算单已作废";
            });
        }
        settlement.setStatus("PAID");
        settlement.setPaidReference(reference.trim());
        settlement.setPaidBy(operatorId);
        settlement.setPaidAt(now);
        settlement.setUpdatedAt(now);
        return AllianceDtos.SettlementView.of(settlement, now);
    }

    @Transactional(readOnly = true)
    public List<AllianceSettlement> byStatus(String status) {
        return settlements.findAllByTenantIdAndStatusOrderByCreatedAtAsc(TenantContext.require(), status);
    }
}
