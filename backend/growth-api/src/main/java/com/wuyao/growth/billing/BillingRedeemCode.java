package com.wuyao.growth.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** 平台级兑换码，不属于任何商户；兑换后记下兑换商户。 */
@Entity @Table(name="billing_redeem_codes") @Getter @Setter
public class BillingRedeemCode {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(length=16) private String code;
    @Column(length=80) private String batchKey;
    @Column(length=12) private String kind;
    private long credits;
    /** 充值码的实收金额（分），按它记邀请返利；赠送码为 0。 */
    private long amountMinor;
    /** 激活码（MEMBERSHIP）开通的套餐版本；充值码、赠送码为空。 */
    private Long planId;
    /** 服务商生成的激活码记下服务商，兑换后把商户归到它名下；平台生成的为空。 */
    private Long partnerId;
    /** 站内购买的激活码：来自哪笔订单、哪个商户买的；其他码为空。 */
    @Column(length=36) private String orderId;
    private Long purchasedTenantId;
    @Column(length=200) private String note;
    private Instant expiresAt;
    private Long createdBy;
    private Instant createdAt = Instant.now();
    private Long voidedBy;
    private Instant voidedAt;
    private Long redeemedTenantId;
    private Long redeemedBy;
    private Instant redeemedAt;

    /** UNUSED、REDEEMED、VOIDED 或 EXPIRED。 */
    public String status(Instant now) {
        if (voidedAt != null) return "VOIDED";
        if (redeemedAt != null) return "REDEEMED";
        if (expiresAt != null && !expiresAt.isAfter(now)) return "EXPIRED";
        return "UNUSED";
    }
}
