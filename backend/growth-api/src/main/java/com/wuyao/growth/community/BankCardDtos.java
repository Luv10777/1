package com.wuyao.growth.community;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class BankCardDtos {
    private BankCardDtos() {}

    /** 卡号允许带空格；持卡人姓名和身份证号取自本人已通过的实名，不需要填写。 */
    public record BindRequest(
            @NotBlank @Size(min = 2, max = 40, message = "请填写开户银行") String bankName,
            @NotBlank @Pattern(regexp = "[0-9 ]{12,25}", message = "请输入正确的银行卡号") String cardNumber,
            @NotBlank @Pattern(regexp = "1\\d{10}", message = "请输入银行预留的 11 位手机号") String mobile,
            @NotNull @AssertTrue(message = "请确认这是本人名下的银行卡") Boolean declarationAccepted) {}

    public record CardView(String bankName, String cardLast4, Instant verifiedAt) {}

    /** matched=false 表示这次核验不一致，card 仍是原来的卡（可能为空）。 */
    public record BindResult(boolean matched, CardView card) {}

    /** 申请提现时抄到提现单上的收款卡。 */
    public record Payee(String bankName, byte[] cardNumberCiphertext, String cardLast4) {}

    /** 平台打款时查看的完整收款信息：户名就是本人实名。 */
    public record PayeeDetail(String holderName, String bankName, String cardNumber) {}
}
