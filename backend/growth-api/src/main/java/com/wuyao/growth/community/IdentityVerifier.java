package com.wuyao.growth.community;

/** 姓名与居民身份证号码、本人银行卡的权威核验。无法核验时不能当作一致，请用户稍后再试。 */
public interface IdentityVerifier {
    Result verify(String legalName, String idNumber);

    /** 银行卡四要素：卡号、持卡人姓名、身份证号、银行预留手机号。 */
    Result verifyBankCard(String legalName, String idNumber, String cardNumber, String mobile);

    enum Outcome { MATCH, MISMATCH, UNAVAILABLE }

    record Result(Outcome outcome, String requestId) {
        static Result unavailable() { return new Result(Outcome.UNAVAILABLE, null); }
    }
}
