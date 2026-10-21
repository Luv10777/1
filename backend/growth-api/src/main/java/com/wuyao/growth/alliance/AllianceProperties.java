package com.wuyao.growth.alliance;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 联盟券的业务参数。默认值是 docs/alliance-vouchers.md 里的占位数字，上线前由业务确认。 */
@Component
@ConfigurationProperties(prefix = "growth.alliance")
@Getter
@Setter
public class AllianceProperties {
    /** 同一供券商户的券，每个领券商户每期最多领几张。 */
    private int perSupplierLimit = 2;
    /** 每个供券商户每月供券总面值上限（分）。 */
    private long monthlyFaceCapMinor = 200_000;
    /** 单个商品供券数量不超过库存的百分比。 */
    private int inventorySharePercent = 50;
    /** 库存不限的商品，单次供券数量上限。 */
    private int unlimitedInventoryCap = 20;
    /** 领取后多少天过期。 */
    private int voucherValidDays = 30;
    /** 核销后的申诉期（天），期内结算冻结。 */
    private int disputeDays = 7;
    /** 同一店员连续输错多少次后暂停核销。 */
    private int redeemAttemptLimit = 10;
    /** 暂停核销多少分钟。 */
    private int redeemLockMinutes = 15;
    /** 对账单生成后，商户多少天不处理视为确认。 */
    private int statementConfirmDays = 5;
}
