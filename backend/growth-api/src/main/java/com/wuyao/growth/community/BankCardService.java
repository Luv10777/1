package com.wuyao.growth.community;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * 本人收款银行卡。持卡人姓名、身份证号取自已通过的实名，加卡号和银行预留手机号做四要素核验，一致才保存；
 * 不做人工审核。卡号加密保存，只显示后 4 位，预留手机号不保存。
 */
@Service
@RequiredArgsConstructor
public class BankCardService {
    static final int MAX_DAILY_AUTO_REJECTIONS = 3;
    static final String AUDIT_TARGET = "BANK_CARD";
    private static final String CRYPTO_FIELD = "bank-card";

    private final BankCardRepository cards;
    private final RealNameApplicationRepository realNames;
    private final PlatformAccountDirectory accounts;
    private final RealNameCrypto crypto;
    private final PlatformManagementAuditRepository audits;
    private final PlatformAuditLog audit;
    private final IdentityVerifier verifier;

    @Transactional(readOnly = true)
    public BankCardDtos.CardView current(Long userId) {
        return mine(userId).map(BankCardService::view).orElse(null);
    }

    @Transactional
    public BankCardDtos.BindResult bind(Long userId, BankCardDtos.BindRequest request) {
        Long tenantId = TenantContext.require();
        String number = request.cardNumber().replace(" ", "");
        if (!number.matches("\\d{12,19}")) throw BizException.of(ErrorCode.BAD_REQUEST, "请输入正确的银行卡号");
        accounts.lockAccount(userId);
        var identity = realNames.findByTenantIdAndUserId(tenantId, userId).filter(a -> "VERIFIED".equals(a.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN, "完成实名核验后才能绑定银行卡"));
        // 每次核验都计费，也能被用来试探别人的卡，滚动 24 小时内不一致满 3 次就停到第二天。
        if (audits.countByTenantIdAndActorUserIdAndTargetTypeAndActionAndCreatedAtAfter(tenantId, userId,
                AUDIT_TARGET, "AUTO_REJECTED", Instant.now().minus(Duration.ofDays(1))) >= MAX_DAILY_AUTO_REJECTIONS) {
            throw BizException.of(ErrorCode.RATE_LIMITED, "今天银行卡核验不一致的次数已用完，请明天再试");
        }
        String name = crypto.decrypt(identity.getLegalNameCiphertext(), tenantId, userId, "name");
        String idNumber = crypto.decrypt(identity.getIdNumberCiphertext(), tenantId, userId, "number");
        var result = verifier.verifyBankCard(name, idNumber, number, request.mobile());
        if (result.outcome() == IdentityVerifier.Outcome.UNAVAILABLE) {
            throw BizException.of(ErrorCode.IDENTITY_VERIFICATION_UNAVAILABLE, "银行卡核验暂时不可用，请稍后再试");
        }
        String last4 = number.substring(number.length() - 4);
        if (result.outcome() == IdentityVerifier.Outcome.MISMATCH) {
            audit.record(userId, AUDIT_TARGET, userId, "AUTO_REJECTED",
                    "银行卡四要素核验不一致，尾号 " + last4 + "，核验请求号 " + result.requestId());
            return new BankCardDtos.BindResult(false, current(userId));
        }
        var card = cards.findById(userId).orElseGet(() -> {
            var created = new BankCard();
            created.setUserId(userId);
            created.setTenantId(tenantId);
            return created;
        });
        card.setBankName(request.bankName().trim());
        card.setCardNumberCiphertext(crypto.encrypt(number, tenantId, userId, CRYPTO_FIELD));
        card.setCardLast4(last4);
        card.setVerifiedAt(Instant.now());
        card.setUpdatedAt(Instant.now());
        var saved = cards.saveAndFlush(card);
        audit.record(userId, AUDIT_TARGET, userId, "AUTO_VERIFIED",
                "银行卡四要素核验一致，尾号 " + last4 + "，核验请求号 " + result.requestId());
        return new BankCardDtos.BindResult(true, view(saved));
    }

    /** 申请提现时调用：没有绑卡就不能提现。 */
    @Transactional(readOnly = true)
    public BankCardDtos.Payee payee(Long userId) {
        return mine(userId).map(c -> new BankCardDtos.Payee(c.getBankName(), c.getCardNumberCiphertext(), c.getCardLast4()))
                .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT, "请先绑定本人银行卡"));
    }

    /** 解开提现单上抄下的卡号，户名取本人实名。在推广人的商户里调用，审计由调用方记录。 */
    @Transactional(readOnly = true)
    public BankCardDtos.PayeeDetail reveal(Long userId, String bankName, byte[] cardNumberCiphertext) {
        Long tenantId = TenantContext.require();
        var identity = realNames.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "没有找到实名信息"));
        return new BankCardDtos.PayeeDetail(crypto.decrypt(identity.getLegalNameCiphertext(), tenantId, userId, "name"),
                bankName, crypto.decrypt(cardNumberCiphertext, tenantId, userId, CRYPTO_FIELD));
    }

    private java.util.Optional<BankCard> mine(Long userId) {
        return cards.findById(userId).filter(c -> TenantContext.require().equals(c.getTenantId()));
    }

    private static BankCardDtos.CardView view(BankCard card) {
        return new BankCardDtos.CardView(card.getBankName(), card.getCardLast4(), card.getVerifiedAt());
    }
}
