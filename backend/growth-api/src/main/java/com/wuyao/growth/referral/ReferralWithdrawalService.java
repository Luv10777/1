package com.wuyao.growth.referral;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.community.BankCardDtos;
import com.wuyao.growth.community.BankCardService;
import com.wuyao.growth.community.PlatformAuditLog;
import com.wuyao.growth.community.RealNameService;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 返利提现。推广人一次提走全部可提现返利，减去待扣回的欠款，满 100 元才能申请；平台审核后由财务从公司网银转到本人银行卡，
 * 再登记代扣税额和转账流水。都在推广人所在商户里执行，平台操作由调用方切到该商户并核对运营身份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralWithdrawalService {
    static final long MINIMUM_MINOR = 10_000;
    static final String AUDIT_TARGET = "REFERRAL_WITHDRAWAL";
    static final String COMMISSION_AUDIT_TARGET = "REFERRAL_COMMISSION";
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    private final ReferralWithdrawalRepository withdrawals;
    private final ReferralCommissionRepository commissions;
    private final BankCardService bankCards;
    private final RealNameService realNames;
    private final PlatformAccountDirectory accounts;
    private final PlatformAuditLog audit;

    @Transactional
    public ReferralDtos.Withdrawal apply(Long userId) {
        Long tenantId = TenantContext.require();
        accounts.lockAccount(userId);
        var identity = realNames.current(userId);
        if (identity == null || !"VERIFIED".equals(identity.status())) throw BizException.of(ErrorCode.FORBIDDEN, "完成实名核验后才能提现");
        var payee = bankCards.payee(userId);
        if (withdrawals.existsByTenantIdAndUserIdAndStatus(tenantId, userId, "PENDING")) {
            throw BizException.of(ErrorCode.CONFLICT, "已有一笔提现在审核中，处理完再申请");
        }
        var available = commissions.lockAvailable(tenantId, userId, Instant.now());
        var owed = commissions.lockOwed(tenantId, userId);
        long commission = sum(available);
        long clawback = sum(owed);
        if (commission - clawback < MINIMUM_MINOR) {
            throw BizException.of(ErrorCode.CONFLICT, "可提现金额满 " + yuan(MINIMUM_MINOR) + " 才能申请提现"
                    + (clawback > 0 ? "（已扣除待扣回的 " + yuan(clawback) + "）" : ""));
        }
        var row = new ReferralWithdrawal();
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setCommissionMinor(commission);
        row.setClawbackMinor(clawback);
        row.setAmountMinor(commission - clawback);
        row.setBankName(payee.bankName());
        row.setCardNumberCiphertext(payee.cardNumberCiphertext());
        row.setCardLast4(payee.cardLast4());
        row.setAgreementVersion(ReferralService.AGREEMENT_VERSION);
        var saved = withdrawals.saveAndFlush(row);
        available.forEach(c -> c.setWithdrawalId(saved.getId()));
        owed.forEach(c -> c.setClawbackWithdrawalId(saved.getId()));
        audit.record(userId, AUDIT_TARGET, saved.getId(), "SUBMITTED",
                "本人申请提现 " + yuan(saved.getAmountMinor()) + "，收款卡尾号 " + saved.getCardLast4());
        log.info("返利提现申请: withdrawal={} user={} amount={}", saved.getId(), userId, saved.getAmountMinor());
        return view(saved);
    }

    @Transactional(readOnly = true)
    public List<ReferralDtos.Withdrawal> mine(Long userId) {
        return withdrawals.findTop20ByTenantIdAndUserIdOrderByCreatedAtDesc(TenantContext.require(), userId).stream()
                .map(ReferralWithdrawalService::view).toList();
    }

    /** 打款前查看完整卡号和户名，写明用途并留审计。 */
    @Transactional
    public BankCardDtos.PayeeDetail reveal(Long id, String purpose, Long operatorId) {
        var row = withdrawals.findForUpdate(id, TenantContext.require()).orElseThrow(ReferralWithdrawalService::missing);
        audit.record(operatorId, AUDIT_TARGET, id, "REVEAL", purpose);
        return bankCards.reveal(row.getUserId(), row.getBankName(), row.getCardNumberCiphertext());
    }

    /** 驳回后带走的返利回到可提现，扣回的欠款也回到待扣回。 */
    @Transactional
    public ReferralDtos.Withdrawal reject(Long id, String note, Long operatorId) {
        Long tenantId = TenantContext.require();
        var row = pending(id);
        row.setStatus("REJECTED");
        row.setReviewNote(note.trim());
        row.setReviewedBy(operatorId);
        row.setReviewedAt(Instant.now());
        withdrawals.saveAndFlush(row);
        commissions.releaseWithdrawal(tenantId, id);
        commissions.releaseClawback(tenantId, id);
        audit.record(operatorId, AUDIT_TARGET, id, "REJECTED", note);
        return view(row);
    }

    /** 财务转账成功后登记。实发 = 申请金额 - 代扣税额。 */
    @Transactional
    public ReferralDtos.Withdrawal markPaid(Long id, ReferralDtos.PayWithdrawal request, Long operatorId) {
        Long tenantId = TenantContext.require();
        var row = pending(id);
        if (request.taxMinor() > row.getAmountMinor()) throw BizException.of(ErrorCode.BAD_REQUEST, "代扣税额不能超过提现金额");
        if (request.paidDate().isAfter(LocalDate.now(CHINA))) throw BizException.of(ErrorCode.BAD_REQUEST, "打款日期不能晚于今天");
        Instant now = Instant.now();
        row.setStatus("PAID");
        row.setTaxMinor(request.taxMinor());
        row.setNetMinor(row.getAmountMinor() - request.taxMinor());
        row.setPaidReference(request.reference().trim());
        row.setPaidDate(request.paidDate());
        row.setReviewedBy(operatorId);
        row.setReviewedAt(now);
        withdrawals.saveAndFlush(row);
        commissions.markWithdrawn(tenantId, id, now);
        audit.record(operatorId, AUDIT_TARGET, id, "PAID", "已打款 " + yuan(row.getAmountMinor()) + "，代扣税 " + yuan(row.getTaxMinor())
                + "，实发 " + yuan(row.getNetMinor()) + "，流水号 " + row.getPaidReference());
        return view(row);
    }

    /**
     * 作废一笔返利（例如被邀请商户退款）。还没提现的直接作废；已打款的作废后记为欠款，从下一次提现里扣回；
     * 正在待审核提现单里的，要先驳回那张单。
     */
    @Transactional
    public void voidCommission(Long id, String note, Long operatorId) {
        var row = commissions.findForUpdate(id, TenantContext.require())
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "返利不存在"));
        if ("VOIDED".equals(row.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "这笔返利已经作废了");
        if (row.getWithdrawalId() != null && row.getWithdrawnAt() == null) {
            throw BizException.of(ErrorCode.CONFLICT, "这笔返利在待审核的提现单里，请先驳回那张提现单再作废");
        }
        row.setStatus("VOIDED");
        row.setVoidNote(note.trim());
        row.setVoidedBy(operatorId);
        row.setVoidedAt(Instant.now());
        commissions.saveAndFlush(row);
        audit.record(operatorId, COMMISSION_AUDIT_TARGET, id, "VOID",
                (row.getWithdrawnAt() != null ? "已提现返利作废，下次提现扣回：" : "返利作废：") + note.trim());
    }

    private ReferralWithdrawal pending(Long id) {
        var row = withdrawals.findForUpdate(id, TenantContext.require()).orElseThrow(ReferralWithdrawalService::missing);
        if (!"PENDING".equals(row.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "这张提现单已经处理过了");
        return row;
    }

    static ReferralDtos.Withdrawal view(ReferralWithdrawal w) {
        return new ReferralDtos.Withdrawal(w.getId(), w.getCommissionMinor(), w.getClawbackMinor(), w.getAmountMinor(), w.getStatus(),
                w.getBankName(), w.getCardLast4(), w.getReviewNote(), w.getTaxMinor(), w.getNetMinor(), w.getPaidDate(),
                w.getCreatedAt(), w.getReviewedAt());
    }

    private static long sum(List<ReferralCommission> rows) {
        return rows.stream().mapToLong(ReferralCommission::getAmountMinor).sum();
    }

    static String yuan(long minor) {
        return String.format("¥%d.%02d", minor / 100, minor % 100);
    }

    private static BizException missing() {
        return BizException.of(ErrorCode.NOT_FOUND, "提现单不存在");
    }
}
