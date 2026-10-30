package com.wuyao.growth.community;

/** 姓名与居民身份证号码的权威核验。无法核验时不能当作一致，请用户稍后再试。 */
public interface IdentityVerifier {
    Result verify(String legalName, String idNumber);

    enum Outcome { MATCH, MISMATCH, UNAVAILABLE }

    record Result(Outcome outcome, String requestId) {
        static Result unavailable() { return new Result(Outcome.UNAVAILABLE, null); }
    }
}
