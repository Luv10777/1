package com.wuyao.growth.common.web;

/**
 * 契约 3：错误码号段。
 *
 *   1000-1999  通用 / 框架        （common，不要占用）
 *   2000-2999  身份与租户 iam
 *   3000-3999  资产中心 asset
 *   4000-4999  内容工具 content
 *   5000-5999  智能客服 cs
 *   6000-6999  全网发布 publish
 *   7000-7999  运营分析 analytics
 *   8000-8999  GEO 增长 geo
 *   9000-9999  套餐计费 billing
 *
 * 加模块时在自己的号段里加，不要动别人的。
 */
public enum ErrorCode {

    // ---- 通用 ----
    BAD_REQUEST(1400),
    UNAUTHORIZED(1401),
    FORBIDDEN(1403),
    NOT_FOUND(1404),
    CONFLICT(1409),
    RATE_LIMITED(1429),
    INTERNAL_ERROR(1500),
    STORAGE_UNAVAILABLE(1503),

    // ---- iam ----
    SMS_TOO_FREQUENT(2001),
    SMS_DAILY_LIMIT(2002),
    CODE_INVALID(2003),
    CODE_EXPIRED(2004),
    REFRESH_TOKEN_INVALID(2005),
    PHONE_INVALID(2006),
    SMS_NOT_CONFIGURED(2007),
    SMS_SEND_FAILED(2008),
    PASSWORD_INVALID(2009),

    // ---- asset ----
    ASSET_NOT_FOUND(3001),
    ASSET_UPLOAD_FAILED(3002);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
