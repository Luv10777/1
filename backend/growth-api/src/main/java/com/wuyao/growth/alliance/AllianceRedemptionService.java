package com.wuyao.growth.alliance;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.store.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 门店核销，在供券商户的租户里执行。核销会不会重复只靠这一侧保证（领取记录行锁 + 状态检查），
 * 领券方的「已核销」由同一事务里登记的任务随后同步。
 */
@Service
@RequiredArgsConstructor
public class AllianceRedemptionService {
    private final AllianceOfferClaimRepository claims;
    private final AllianceOfferRepository offers;
    private final AllianceSettlementRepository settlements;
    private final AllianceRedeemAttemptRepository attempts;
    private final AllianceRedeemAttemptLog attemptLog;
    private final AllianceProperties config;
    private final StoreAccessService storeAccess;
    private final TaskService tasks;

    /** 只核对，不改任何状态。店员看过商品和领券商户后再确认核销。 */
    @Transactional(readOnly = true)
    public AllianceDtos.RedemptionPreview preview(Long storeId, String code, Long userId) {
        storeAccess.requireAccess(storeId, userId);
        requireNotLocked(userId);
        var claim = claims.findByCodeHash(AllianceVoucherService.hash(code)).orElse(null);
        var offer = usable(claim, storeId, userId);
        return new AllianceDtos.RedemptionPreview(offer.getProductName(), offer.getSaleUnit(), claim.getHolderName(),
                offer.getFaceValueMinor(), claim.getExpiresAt());
    }

    @Transactional
    public AllianceDtos.RedemptionView redeem(Long storeId, String code, Long userId) {
        storeAccess.requireAccess(storeId, userId);
        requireNotLocked(userId);
        var claim = claims.findByCodeHashForUpdate(AllianceVoucherService.hash(code)).orElse(null);
        var offer = usable(claim, storeId, userId);
        Instant now = Instant.now();
        claim.setStatus("REDEEMED");
        claim.setRedeemedStoreId(storeId);
        claim.setRedeemedBy(userId);
        claim.setRedeemedAt(now);
        claim.setUpdatedAt(now);

        // 申诉判定核销无效后，这张券可以再核销一次：沿用作废的那张结算单，重新进入申诉期。
        var settlement = settlements.findForUpdate(claim.getVoucherRef()).orElseGet(AllianceSettlement::new);
        settlement.setStatus("HOLDING");
        settlement.setFrozen(false);
        settlement.setUpdatedAt(now);
        settlement.setTenantId(claim.getTenantId());
        settlement.setClaimId(claim.getId());
        settlement.setOfferId(offer.getId());
        settlement.setVoucherRef(claim.getVoucherRef());
        settlement.setHolderTenantId(claim.getHolderTenantId());
        settlement.setProductName(offer.getProductName());
        settlement.setFaceValueMinor(offer.getFaceValueMinor());
        settlement.setSettleAmountMinor(offer.getSettleAmountMinor());
        settlement.setPayableAfter(now.plus(Duration.ofDays(config.getDisputeDays())));
        settlements.save(settlement);

        tasks.submit(AllianceSyncHandler.TYPE, null,
                Map.of("action", AllianceSyncHandler.REDEEMED, "ref", claim.getVoucherRef().toString(),
                        "holderTenantId", claim.getHolderTenantId(), "redeemedAt", now.toString()),
                "alliance-redeemed:" + claim.getVoucherRef() + ":" + now.toEpochMilli(), userId);
        attemptLog.record(storeId, userId, true);
        return new AllianceDtos.RedemptionView(offer.getProductName(), claim.getHolderName(), offer.getFaceValueMinor(), now, userId);
    }

    @Transactional(readOnly = true)
    public List<AllianceDtos.RedemptionView> records(Long storeId, Long userId) {
        storeAccess.requireAccess(storeId, userId);
        var redeemed = claims.findAllByRedeemedStoreIdAndStatusOrderByRedeemedAtDesc(storeId, "REDEEMED", PageRequest.of(0, 100));
        // One query for all offers on the page instead of one per record.
        Map<Long, AllianceOffer> byId = offers.findAllById(redeemed.stream().map(AllianceOfferClaim::getOfferId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(AllianceOffer::getId, o -> o));
        return redeemed.stream()
                .map(c -> {
                    var offer = java.util.Optional.ofNullable(byId.get(c.getOfferId())).orElseThrow();
                    return new AllianceDtos.RedemptionView(offer.getProductName(), c.getHolderName(),
                            offer.getFaceValueMinor(), c.getRedeemedAt(), c.getRedeemedBy());
                }).toList();
    }

    /** 券码能不能在这家门店用。不能用时记一次失败并给出原因；别家门店的券按「券码无效」处理。 */
    private AllianceOffer usable(AllianceOfferClaim claim, Long storeId, Long userId) {
        var offer = claim == null ? null : offers.findById(claim.getOfferId()).orElse(null);
        String problem;
        if (offer == null || !storeId.equals(offer.getStoreId())) problem = "券码无效，请核对后重新输入";
        else if ("REDEEMED".equals(claim.getStatus())) problem = "这张券已经核销过了";
        else if ("EXPIRED".equals(claim.getStatus()) || !Instant.now().isBefore(claim.getExpiresAt())) problem = "这张券已过期";
        else return offer;
        attemptLog.record(storeId, userId, false);
        throw BizException.of(ErrorCode.CONFLICT, problem);
    }

    /** 最近一段时间里，从最后一次成功之后连续失败达到上限，就暂停核销。 */
    private void requireNotLocked(Long userId) {
        Instant since = Instant.now().minus(Duration.ofMinutes(config.getRedeemLockMinutes()));
        var recent = attempts.recent(userId, since, PageRequest.of(0, config.getRedeemAttemptLimit()));
        if (recent.size() >= config.getRedeemAttemptLimit() && recent.stream().noneMatch(AllianceRedeemAttempt::isSucceeded)) {
            throw BizException.of(ErrorCode.RATE_LIMITED,
                    "连续输错次数过多，请 " + config.getRedeemLockMinutes() + " 分钟后再试");
        }
    }
}
