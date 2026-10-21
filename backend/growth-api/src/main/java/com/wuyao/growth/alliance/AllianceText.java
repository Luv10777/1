package com.wuyao.growth.alliance;

import java.math.BigDecimal;

/** 通知里用的金额和折扣写法。 */
final class AllianceText {
    private AllianceText() {}

    static String yuan(long minor) {
        return "¥" + BigDecimal.valueOf(minor, 2).toPlainString();
    }

    static String discount(Integer bp) {
        if (bp == null) return "";
        return BigDecimal.valueOf(bp, 3).stripTrailingZeros().toPlainString() + " 折";
    }
}
