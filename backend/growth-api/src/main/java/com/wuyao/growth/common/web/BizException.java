package com.wuyao.growth.common.web;

import lombok.Getter;

/** 业务异常。抛它，别在 controller 里手工拼错误响应。 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static BizException of(ErrorCode code, String message) {
        return new BizException(code, message);
    }
}
