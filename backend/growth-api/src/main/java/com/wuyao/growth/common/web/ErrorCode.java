package com.wuyao.growth.common.web;

import org.springframework.http.HttpStatus;

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
    WECHAT_NOT_CONFIGURED(2010),
    WECHAT_LOGIN_FAILED(2011),
    WECHAT_TICKET_INVALID(2012),
    WECHAT_PASSWORD_REQUIRED(2013),

    // ---- asset ----
    ASSET_NOT_FOUND(3001),
    ASSET_UPLOAD_FAILED(3002),

    IMAGE_NOT_CONFIGURED(4001),
    IMAGE_PROVIDER_ERROR(4002),
    IMAGE_PLAN_INVALID(4003),
    IMAGE_QUALITY_UNSUPPORTED(4004),

    VIDEO_NOT_CONFIGURED(4101),
    VIDEO_PROVIDER_ERROR(4102),
    VIDEO_MODEL_UNSUPPORTED(4103),
    VIDEO_PARAMETER_INVALID(4104),
    VIDEO_TIMEOUT(4105);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus httpStatus() {
        return switch (this) {
            case UNAUTHORIZED, REFRESH_TOKEN_INVALID, PASSWORD_INVALID, CODE_INVALID, CODE_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND, ASSET_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED, SMS_TOO_FREQUENT, SMS_DAILY_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case STORAGE_UNAVAILABLE, SMS_NOT_CONFIGURED, WECHAT_NOT_CONFIGURED, IMAGE_NOT_CONFIGURED, VIDEO_NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case SMS_SEND_FAILED, IMAGE_PROVIDER_ERROR, VIDEO_PROVIDER_ERROR -> HttpStatus.BAD_GATEWAY;
            case VIDEO_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
