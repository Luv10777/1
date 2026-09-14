package com.wuyao.growth.common.web;

/**
 * 契约 1：所有接口统一返回这个结构，前端只认这一种。
 * 成功 code = 200；失败 code 见 {@link ErrorCode}。
 * 定下来就不要改——前端已经在按 code === 200 判断了。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(200, "ok", null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode code, String message) {
        return new ApiResponse<>(code.getCode(), message, null);
    }
}
