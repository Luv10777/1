package com.wuyao.growth.alliance;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.community.MerchantQualificationService;
import com.wuyao.growth.community.PlatformAuditLog;
import com.wuyao.growth.community.RealNameCrypto;
import com.wuyao.growth.iam.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 供券商户的收款账户。都在供券商户的租户里执行。
 * 银行账号用 AES-256-GCM 加密（附加数据带商户号），列表只给后 4 位；平台查看完整账号要写用途并留审计。
 */
@Service
@RequiredArgsConstructor
public class AllianceAccountService {
    /** 加密附加数据里的「用户」位：账户属于商户而不是某个人，固定为 0。 */
    private static final long NO_USER = 0L;
    static final String CRYPTO_FIELD = "alliance-settlement-account";
    static final String AUDIT_TARGET = "SETTLEMENT_ACCOUNT";

    private final AllianceSettlementAccountRepository accounts;
    private final AccountService users;
    private final RealNameCrypto crypto;
    private final PlatformAuditLog audit;
    private final MerchantQualificationService qualification;

    @Transactional(readOnly = true)
    public Optional<AllianceDtos.AccountView> mine() {
        return accounts.findByTenantId(TenantContext.require()).map(AllianceDtos.AccountView::of);
    }

    /** 填写或修改。保存后都要平台重新核对；修改时账号留空表示沿用原来的。 */
    @Transactional
    public AllianceDtos.AccountView save(AllianceDtos.AccountRequest request, Long userId) {
        users.requireOwner(userId);
        Long tenantId = TenantContext.require();
        var account = accounts.findForUpdate(tenantId).orElseGet(() -> {
            var created = new AllianceSettlementAccount();
            created.setTenantId(tenantId);
            return created;
        });
        String number = request.accountNumber() == null ? "" : request.accountNumber().replace(" ", "");
        if (number.isEmpty() && account.getAccountNumberCiphertext() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请填写银行账号");
        }
        if (!number.isEmpty()) {
            account.setAccountNumberCiphertext(crypto.encrypt(number, tenantId, NO_USER, CRYPTO_FIELD));
            account.setAccountNumberLast4(number.substring(number.length() - 4));
        }
        account.setAccountType(request.accountType());
        account.setAccountName(request.accountName().trim());
        account.setBankName(request.bankName().trim());
        account.setBranchName(request.branchName().trim());
        account.setFinanceContactName(request.financeContactName().trim());
        account.setFinanceContactPhone(request.financeContactPhone().trim());
        account.setStatus("PENDING_VERIFY");
        account.setReviewNote(null);
        account.setReviewedBy(null);
        account.setReviewedAt(null);
        account.setUpdatedBy(userId);
        account.setUpdatedAt(Instant.now());
        return AllianceDtos.AccountView.of(accounts.saveAndFlush(account));
    }

    /** 供券前必须先填好收款账户；平台审核供券单时一并核对。 */
    @Transactional(readOnly = true)
    public void requirePresent() {
        if (accounts.findByTenantId(TenantContext.require()).isEmpty()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "请先在「供券管理 → 结算账户」填写收款账户，再提供联盟券");
        }
    }

    /** 确认对账单时用：账户必须已核对。 */
    @Transactional(readOnly = true)
    public AllianceSettlementAccount requireVerified() {
        return accounts.findByTenantId(TenantContext.require()).filter(a -> "VERIFIED".equals(a.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT, "收款账户还没通过平台核对，核对通过后才能确认对账单"));
    }

    @Transactional(readOnly = true)
    public Optional<String> status() {
        return accounts.findByTenantId(TenantContext.require()).map(AllianceSettlementAccount::getStatus);
    }

    /** 当前租户的账户和入驻时登记的主体名称，平台核对户名时对照。 */
    @Transactional(readOnly = true)
    public Optional<AccountForReview> forReview() {
        String company = qualification.approvedMerchant().map(MerchantQualificationService.ApprovedMerchant::companyName).orElse(null);
        return accounts.findByTenantId(TenantContext.require())
                .map(a -> new AccountForReview(AllianceDtos.AccountView.of(a), company));
    }

    record AccountForReview(AllianceDtos.AccountView account, String registeredCompanyName) {}

    /** 平台核对。调用方已切到供券商户的租户并校验运营身份。 */
    @Transactional
    public AllianceDtos.AccountView review(boolean approved, String note, Long operatorId) {
        var account = accounts.findForUpdate(TenantContext.require())
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "这个商户还没有填写收款账户"));
        if (!"PENDING_VERIFY".equals(account.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "收款账户已经核对过了，商户修改后会重新进入待核对");
        if (!approved && (note == null || note.isBlank())) throw BizException.of(ErrorCode.BAD_REQUEST, "驳回时请写明原因");
        account.setStatus(approved ? "VERIFIED" : "REJECTED");
        account.setReviewNote(note == null || note.isBlank() ? null : note.trim());
        account.setReviewedBy(operatorId);
        account.setReviewedAt(Instant.now());
        return AllianceDtos.AccountView.of(account);
    }

    /** 平台查看完整账号（核对或打款时用），写明用途并留审计。 */
    @Transactional
    public AllianceDtos.RevealView reveal(String purpose, Long operatorId) {
        Long tenantId = TenantContext.require();
        var account = accounts.findByTenantId(tenantId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "这个商户还没有填写收款账户"));
        audit.record(operatorId, AUDIT_TARGET, account.getId(), "REVEAL", purpose);
        return new AllianceDtos.RevealView(account.getAccountName(), account.getBankName(), account.getBranchName(),
                crypto.decrypt(account.getAccountNumberCiphertext(), tenantId, NO_USER, CRYPTO_FIELD));
    }

    /** 解开对账单上抄下的账号。审计由调用方记录。 */
    String decrypt(byte[] ciphertext) {
        return crypto.decrypt(ciphertext, TenantContext.require(), NO_USER, CRYPTO_FIELD);
    }
}
