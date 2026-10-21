package com.wuyao.growth.alliance;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.community.MerchantQualificationService;
import com.wuyao.growth.iam.service.AccountService;
import com.wuyao.growth.iam.service.TenantDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 领券商户这一侧：扣额度、生成券码、我的联盟券、申诉。都在领券商户的租户里执行。 */
@Service
@RequiredArgsConstructor
public class AllianceVoucherService {
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 同一供券商户限领张数时，不算领取失败的券。 */
    private static final Set<String> COUNTED = Set.of("CLAIMING", "ACTIVE", "REDEEMED", "DISPUTED", "EXPIRED");

    private final AllianceVoucherRepository vouchers;
    private final AllianceQuotaGrantRepository grants;
    private final AllianceOfferRepository offers;
    private final AllianceProperties config;
    private final AccountService accounts;
    private final TenantDirectory tenants;
    private final MerchantQualificationService qualification;
    private final TaskService tasks;
    private final com.wuyao.growth.notice.NoticeService notices;

    /**
     * 领券第 1 步：校验、扣本期额度、写一张「领取中」的券并生成券码，同时登记一个收尾任务。
     * 之后的第 2、3 步即使在请求里没走完，任务也会把这张券补成「可使用」或「领取失败」。
     */
    @Transactional
    public AllianceOfferService.ClaimTicket startClaim(Long offerId, Long userId) {
        accounts.requireOwner(userId);
        Long tenantId = TenantContext.require();
        var offer = offers.findById(offerId)
                .filter(o -> "ACTIVE".equals(o.getStatus()) && o.remaining() > 0)
                .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT, "这张券已被领完或已下架"));
        if (tenantId.equals(offer.getTenantId())) throw BizException.of(ErrorCode.BAD_REQUEST, "不能领取自己商户的券");
        if (qualification.creditCodes().contains(offer.getSupplierCreditCode())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "不能领取同一经营主体的券");
        }
        Instant now = Instant.now();
        var grant = grants.currentForUpdate(tenantId, now).stream().findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.MEMBERSHIP_REQUIRED, "本期没有联盟券额度，开通或续费会员后可领取"));
        if (grant.remainingMinor() < offer.getFaceValueMinor()) {
            throw BizException.of(ErrorCode.CONFLICT, "本期剩余额度不够领这张券");
        }
        long sameSupplier = vouchers.countByQuotaGrantIdAndSupplierTenantIdAndStatusIn(grant.getId(), offer.getTenantId(), COUNTED);
        if (sameSupplier >= config.getPerSupplierLimit()) {
            throw BizException.of(ErrorCode.CONFLICT, "同一商户的券每期最多领 " + config.getPerSupplierLimit() + " 张");
        }
        grant.setUsedMinor(grant.getUsedMinor() + offer.getFaceValueMinor());

        String code = "%08d".formatted(RANDOM.nextInt(100_000_000));
        var voucher = new AllianceVoucher();
        voucher.setVoucherRef(UUID.randomUUID());
        voucher.setTenantId(tenantId);
        voucher.setQuotaGrantId(grant.getId());
        voucher.setOfferId(offer.getId());
        voucher.setSupplierTenantId(offer.getTenantId());
        voucher.setStoreId(offer.getStoreId());
        voucher.setStoreName(offer.getStoreName());
        voucher.setStoreAddress(offer.getStoreAddress());
        voucher.setStorePhone(offer.getStorePhone());
        voucher.setStoreHours(offer.getStoreHours());
        voucher.setProductName(offer.getProductName());
        voucher.setSaleUnit(offer.getSaleUnit());
        voucher.setCoverAssetId(offer.getCoverAssetId());
        voucher.setFaceValueMinor(offer.getFaceValueMinor());
        voucher.setCode(code);
        voucher.setClaimedBy(userId);
        voucher.setClaimedAt(now);
        voucher.setExpiresAt(now.plus(Duration.ofDays(config.getVoucherValidDays())));
        vouchers.save(voucher);
        tasks.submit(AllianceSyncHandler.TYPE, null,
                Map.of("action", AllianceSyncHandler.CLAIM, "ref", voucher.getVoucherRef().toString()),
                "alliance-claim:" + voucher.getVoucherRef(), userId);
        String holderName = tenants.name(tenantId).orElse("联盟商户");
        return new AllianceOfferService.ClaimTicket(voucher.getVoucherRef(), offer.getTenantId(), offer.getId(), tenantId,
                holderName, hash(code), voucher.getExpiresAt());
    }

    /** 券库：别家商户已上架且有剩余的券，排除自己和同一经营主体。 */
    @Transactional(readOnly = true)
    public PageResult<AllianceDtos.PoolItem> pool(String keyword, String city, int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw BizException.of(ErrorCode.BAD_REQUEST, "page 必须非负，size 必须在 1 到 50 之间");
        Set<String> codes = qualification.creditCodes();
        var result = offers.pool(TenantContext.require(), codes.isEmpty() ? Set.of("-") : codes,
                blankToNull(keyword), blankToNull(city), PageRequest.of(page, size));
        return PageResult.of(result, result.getContent().stream().map(AllianceDtos.PoolItem::of).toList());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 收尾任务重新拿到第 2 步需要的信息；券已经不在「领取中」时返回 null。 */
    @Transactional(readOnly = true)
    public AllianceOfferService.ClaimTicket pendingTicket(UUID ref) {
        var voucher = vouchers.findById(ref).filter(v -> "CLAIMING".equals(v.getStatus())).orElse(null);
        if (voucher == null) return null;
        return new AllianceOfferService.ClaimTicket(ref, voucher.getSupplierTenantId(), voucher.getOfferId(),
                voucher.getTenantId(), tenants.name(voucher.getTenantId()).orElse("联盟商户"), hash(voucher.getCode()),
                voucher.getExpiresAt());
    }

    /** 领券第 3 步：名额到手则「可使用」，否则「领取失败」并退回额度。只处理「领取中」的券，可重复执行。 */
    @Transactional
    public AllianceDtos.VoucherView finishClaim(UUID ref, boolean reserved) {
        var voucher = vouchers.findForUpdate(ref).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "联盟券不存在"));
        if ("CLAIMING".equals(voucher.getStatus())) {
            if (reserved) {
                voucher.setStatus("ACTIVE");
            } else {
                voucher.setStatus("CLAIM_FAILED");
                var grant = grants.findForUpdate(voucher.getQuotaGrantId()).orElseThrow();
                grant.setUsedMinor(grant.getUsedMinor() - voucher.getFaceValueMinor());
            }
            voucher.setUpdatedAt(Instant.now());
        }
        return AllianceDtos.VoucherView.of(voucher, Instant.now(), config.getDisputeDays(), false);
    }

    /** 第 2 步出错时，先把「领取中」的券原样返回，由收尾任务补完。 */
    @Transactional(readOnly = true)
    public AllianceDtos.VoucherView finishClaimLater(UUID ref) {
        return AllianceDtos.VoucherView.of(require(ref), Instant.now(), config.getDisputeDays(), false);
    }

    @Transactional(readOnly = true)
    public PageResult<AllianceDtos.VoucherView> mine(int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw BizException.of(ErrorCode.BAD_REQUEST, "page 必须非负，size 必须在 1 到 50 之间");
        Instant now = Instant.now();
        var result = vouchers.findAllByTenantIdOrderByClaimedAtDesc(TenantContext.require(), PageRequest.of(page, size));
        return PageResult.of(result, result.getContent().stream()
                .map(v -> AllianceDtos.VoucherView.of(v, now, config.getDisputeDays(), false)).toList());
    }

    /** 单张券的详情，可使用时带券码。 */
    @Transactional(readOnly = true)
    public AllianceDtos.VoucherView detail(UUID ref) {
        var voucher = vouchers.findById(ref).filter(v -> TenantContext.require().equals(v.getTenantId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "联盟券不存在"));
        return AllianceDtos.VoucherView.of(voucher, Instant.now(), config.getDisputeDays(), true);
    }

    /** 供券方核销后同步过来。只把「可使用」的券改成「已核销」，可重复执行。 */
    @Transactional
    public void markRedeemed(UUID ref, Instant redeemedAt) {
        vouchers.findForUpdate(ref).filter(v -> "ACTIVE".equals(v.getStatus())).ifPresent(v -> {
            v.setStatus("REDEEMED");
            v.setRedeemedAt(redeemedAt);
            // 申诉判定无效后又核销了一次：上一轮申诉已了结，这次核销可以重新申诉。
            v.setDisputeReason(null);
            v.setDisputedAt(null);
            v.setDisputeResult(null);
            v.setDisputeNote(null);
            v.setDisputeResolvedAt(null);
            v.setUpdatedAt(Instant.now());
            notices.notifyOwners("ALLIANCE", "联盟券已核销",
                    "「" + v.getProductName() + "」在「" + v.getStoreName() + "」核销了。如果没拿到商品，可以在 "
                            + config.getDisputeDays() + " 天内到「我的联盟券」申诉。",
                    "/alliance/vouchers", "voucher-redeemed:" + ref + ":" + redeemedAt.toEpochMilli());
        });
    }

    /** 供券方确认过期后同步过来。只处理「可使用」的券，可重复执行。 */
    @Transactional
    public void markExpired(UUID ref) {
        vouchers.findForUpdate(ref).filter(v -> "ACTIVE".equals(v.getStatus())).ifPresent(v -> {
            v.setStatus("EXPIRED");
            v.setUpdatedAt(Instant.now());
        });
    }

    /** 核销后申诉期内，领券商户说「没拿到商品」。结算单由任务去供券方冻结。 */
    @Transactional
    public AllianceDtos.VoucherView dispute(UUID ref, String reason, Long userId) {
        accounts.requireOwner(userId);
        var voucher = vouchers.findForUpdate(ref).filter(v -> TenantContext.require().equals(v.getTenantId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "联盟券不存在"));
        var view = AllianceDtos.VoucherView.of(voucher, Instant.now(), config.getDisputeDays(), false);
        if (!view.canDispute()) {
            throw BizException.of(ErrorCode.CONFLICT, "ACTIVE".equals(voucher.getStatus())
                    ? "核销记录还在同步，请稍后再试" : "只有核销后 " + config.getDisputeDays() + " 天内的券可以申诉");
        }
        voucher.setStatus("DISPUTED");
        voucher.setDisputeReason(reason.trim());
        voucher.setDisputedAt(Instant.now());
        voucher.setUpdatedAt(Instant.now());
        tasks.submit(AllianceSyncHandler.TYPE, null,
                Map.of("action", AllianceSyncHandler.FREEZE, "ref", ref.toString(),
                        "supplierTenantId", voucher.getSupplierTenantId()),
                "alliance-freeze:" + ref + ":" + voucher.getDisputedAt().toEpochMilli(), userId);
        return AllianceDtos.VoucherView.of(voucher, Instant.now(), config.getDisputeDays(), false);
    }

    /** 平台判定后更新领券方：有效则回到「已核销」，无效则恢复可用（已过期则作废）。只处理「申诉中」的券。 */
    @Transactional
    public void resolveDispute(UUID ref, boolean valid, String note) {
        vouchers.findForUpdate(ref).filter(v -> "DISPUTED".equals(v.getStatus())).ifPresent(v -> {
            Instant now = Instant.now();
            if (valid) {
                v.setStatus("REDEEMED");
            } else {
                v.setStatus(now.isBefore(v.getExpiresAt()) ? "ACTIVE" : "EXPIRED");
                v.setRedeemedAt(null);
            }
            v.setDisputeResult(valid ? "VALID" : "INVALID");
            v.setDisputeNote(note == null || note.isBlank() ? null : note.trim());
            v.setDisputeResolvedAt(now);
            v.setUpdatedAt(now);
            String noteText = v.getDisputeNote() == null ? "" : "平台说明：" + v.getDisputeNote() + "。";
            notices.notifyOwners("ALLIANCE", "申诉已处理",
                    valid ? "平台核实「" + v.getProductName() + "」这次核销有效。" + noteText
                          : "平台判定「" + v.getProductName() + "」这次核销无效，" + ("ACTIVE".equals(v.getStatus()) ? "券已恢复可用。" : "券已过期作废。") + noteText,
                    "/alliance/vouchers", "voucher-dispute-resolved:" + ref + ":" + now.toEpochMilli());
        });
    }

    /** 当前租户里还在申诉的券（平台逐个商户读取）。 */
    @Transactional(readOnly = true)
    public List<AllianceVoucher> openDisputes() {
        return vouchers.findAllByTenantIdAndStatusOrderByDisputedAtAsc(TenantContext.require(), "DISPUTED");
    }

    @Transactional(readOnly = true)
    public AllianceVoucher require(UUID ref) {
        return vouchers.findById(ref).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "联盟券不存在"));
    }

    /**
     * 供券方只存券码的哈希，按哈希查找。这只是为了让明文不出现在供券方的表、接口和日志里；
     * 能直接读数据库的人可以穷举 8 位数字，这不是密码学保护。
     */
    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
