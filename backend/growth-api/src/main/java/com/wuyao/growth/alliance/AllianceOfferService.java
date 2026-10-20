package com.wuyao.growth.alliance;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.community.MerchantQualificationService;
import com.wuyao.growth.iam.service.AccountService;
import com.wuyao.growth.product.ProductService;
import com.wuyao.growth.store.StoreAccessService;
import com.wuyao.growth.store.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 供券商户这一侧：供券单的提交、撤回、审核，以及领券时在供券方扣减名额。都在供券商户的租户里执行。 */
@Service
@RequiredArgsConstructor
public class AllianceOfferService {
    static final Set<String> OPEN = Set.of("PENDING_REVIEW", "ACTIVE");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final AllianceOfferRepository offers;
    private final AllianceOfferClaimRepository claims;
    private final AllianceProperties config;
    private final AccountService accounts;
    private final StoreAccessService storeAccess;
    private final StoreService stores;
    private final ProductService products;
    private final MerchantQualificationService qualification;

    /** 领券第 2 步需要的信息：由领券方第 1 步生成，供券方只拿到券码的哈希。 */
    record ClaimTicket(UUID ref, Long supplierTenantId, Long offerId, Long holderTenantId, String holderName,
                       String codeHash, Instant expiresAt) {}

    @Transactional
    public AllianceDtos.OfferView create(AllianceDtos.OfferRequest request, Long userId) {
        accounts.requireOwner(userId);
        storeAccess.requireAccess(request.storeId(), userId);
        var merchant = qualification.approvedMerchant()
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN, "请先通过商家入驻审核，再提供联盟券"));
        var store = stores.profile(request.storeId())
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "门店不存在或已停用"));
        var product = products.lockStock(request.productId(), request.storeId());
        if (product.price() == null || product.price().signum() <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "商品售价为 0，不能作为联盟券");
        }
        int quantity = request.quantity();
        int max = product.inventory() == null ? config.getUnlimitedInventoryCap()
                : product.inventory() * config.getInventorySharePercent() / 100;
        if (quantity > max) {
            throw BizException.of(ErrorCode.BAD_REQUEST, product.inventory() == null
                    ? "库存不限的商品，一次最多提供 " + max + " 张券"
                    : "供券数量不能超过当前库存的 " + config.getInventorySharePercent() + "%，这件商品最多 " + max + " 张");
        }
        long face = product.price().setScale(2, java.math.RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        long monthTotal = monthFaceTotal() + face * quantity;
        if (monthTotal > config.getMonthlyFaceCapMinor()) {
            throw BizException.of(ErrorCode.CONFLICT, "本月供券总面值不能超过 " + yuan(config.getMonthlyFaceCapMinor()) + " 元");
        }
        products.takeStock(product.id(), quantity);

        var offer = new AllianceOffer();
        offer.setTenantId(TenantContext.require());
        offer.setStoreId(request.storeId());
        offer.setProductId(product.id());
        offer.setProductName(product.name());
        offer.setProductCategory(product.category());
        offer.setSaleUnit(product.saleUnit());
        offer.setCoverAssetId(product.coverAssetId());
        offer.setStoreName(store.name());
        offer.setStoreAddress(store.address());
        offer.setStorePhone(store.phone());
        offer.setStoreHours(store.businessHours());
        offer.setSupplierName(merchant.merchantName());
        offer.setSupplierCreditCode(merchant.creditCode());
        offer.setFaceValueMinor(face);
        offer.setSettleRateBp(config.getSettleRateBp());
        offer.setSettleAmountMinor(face * config.getSettleRateBp() / 10_000);
        offer.setTotalQty(quantity);
        offer.setCreatedBy(userId);
        return AllianceDtos.OfferView.of(offers.saveAndFlush(offer), 0);
    }

    @Transactional(readOnly = true)
    public List<AllianceDtos.OfferView> mine() {
        return offers.findAllByTenantIdOrderByCreatedAtDescIdDesc(TenantContext.require()).stream()
                .map(o -> AllianceDtos.OfferView.of(o, claims.countByOfferIdAndStatus(o.getId(), "REDEEMED"))).toList();
    }

    /** 撤回还没领出去的部分，库存还给商品。已领出的券照常有效。 */
    @Transactional
    public AllianceDtos.OfferView withdraw(Long id, Long userId) {
        accounts.requireOwner(userId);
        var offer = offers.findForUpdate(id).filter(o -> TenantContext.require().equals(o.getTenantId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "供券单不存在"));
        if (!OPEN.contains(offer.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "这张供券单已经结束，无需撤回");
        int unclaimed = offer.remaining();
        if (unclaimed > 0) products.restoreInventory(offer.getProductId(), unclaimed);
        offer.setStatus("WITHDRAWN");
        offer.setUpdatedAt(Instant.now());
        return AllianceDtos.OfferView.of(offer, claims.countByOfferIdAndStatus(offer.getId(), "REDEEMED"));
    }

    /** 平台审核。调用方已切到供券商户的租户。 */
    @Transactional
    public AllianceDtos.OfferView review(Long id, boolean approved, String note, Long operatorId) {
        var offer = offers.findForUpdate(id).filter(o -> TenantContext.require().equals(o.getTenantId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "供券单不存在"));
        if (!"PENDING_REVIEW".equals(offer.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "这张供券单已经审核过了");
        if (!approved && (note == null || note.isBlank())) throw BizException.of(ErrorCode.BAD_REQUEST, "驳回时请写明原因");
        offer.setStatus(approved ? "ACTIVE" : "REJECTED");
        offer.setReviewNote(note == null || note.isBlank() ? null : note.trim());
        offer.setReviewedBy(operatorId);
        offer.setReviewedAt(Instant.now());
        offer.setUpdatedAt(Instant.now());
        if (!approved) products.restoreInventory(offer.getProductId(), offer.getTotalQty());
        return AllianceDtos.OfferView.of(offer, 0);
    }

    @Transactional(readOnly = true)
    public List<AllianceOffer> pendingReview() {
        return offers.pendingReview();
    }

    /**
     * 领券第 2 步：在供券方扣一个名额并记下领取。同一个 voucher_ref 重复执行只记一次。
     * @return 名额是否到手；供券单已领完或已结束时为 false
     */
    @Transactional
    public boolean reserveClaim(ClaimTicket ticket) {
        // 先锁供券单再查领取记录：请求和收尾任务可能同时走到这里，拿到锁后才能看到对方已提交的领取。
        var offer = offers.findForUpdate(ticket.offerId()).orElse(null);
        if (claims.findByVoucherRef(ticket.ref()).isPresent()) return true;
        if (offer == null || !"ACTIVE".equals(offer.getStatus()) || offer.remaining() <= 0) return false;
        offer.setClaimedQty(offer.getClaimedQty() + 1);
        if (offer.remaining() == 0) offer.setStatus("SOLD_OUT");
        offer.setUpdatedAt(Instant.now());
        var claim = new AllianceOfferClaim();
        claim.setTenantId(offer.getTenantId());
        claim.setOfferId(offer.getId());
        claim.setVoucherRef(ticket.ref());
        claim.setHolderTenantId(ticket.holderTenantId());
        claim.setHolderName(ticket.holderName());
        claim.setCodeHash(ticket.codeHash());
        claim.setExpiresAt(ticket.expiresAt());
        claims.saveAndFlush(claim);
        return true;
    }

    /** 供券商户入驻被暂停：撤回还没领出去的部分并还库存。已经领出去的券仍需在门店照常核销。 */
    @org.springframework.context.event.EventListener
    @Transactional
    public void onQualificationChanged(com.wuyao.growth.community.MerchantQualificationChanged event) {
        if (event.approved()) return;
        offers.findAllByTenantIdAndStatusIn(event.tenantId(), OPEN).forEach(offer -> {
            int unclaimed = offer.remaining();
            if (unclaimed > 0) products.restoreInventory(offer.getProductId(), unclaimed);
            offer.setStatus("WITHDRAWN");
            offer.setUpdatedAt(Instant.now());
        });
    }

    @Transactional(readOnly = true)
    public boolean claimExists(UUID ref) {
        return claims.findByVoucherRef(ref).isPresent();
    }

    /** 本月已提交的供券面值：撤回的只算已领出的部分，驳回的不算。 */
    private long monthFaceTotal() {
        Instant monthStart = ZonedDateTime.now(BUSINESS_ZONE).withDayOfMonth(1).toLocalDate().atStartOfDay(BUSINESS_ZONE).toInstant();
        return offers.findAllByTenantIdAndCreatedAtGreaterThanEqual(TenantContext.require(), monthStart).stream()
                .filter(o -> !"REJECTED".equals(o.getStatus()))
                .mapToLong(o -> o.getFaceValueMinor() * ("WITHDRAWN".equals(o.getStatus()) ? o.getClaimedQty() : o.getTotalQty()))
                .sum();
    }

    private static String yuan(long minor) {
        return BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString();
    }
}
