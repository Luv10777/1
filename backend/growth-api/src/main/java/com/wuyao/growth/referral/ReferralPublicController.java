package com.wuyao.growth.referral;

import com.wuyao.growth.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 注册前还没登录，核对邀请码只能走公开接口。8 位码约 8.5×10¹¹ 种组合，逐个试探不现实。 */
@RestController
@RequiredArgsConstructor
public class ReferralPublicController {
    private final ReferralService service;

    @GetMapping("/api/public/referral-codes/{code}")
    public ApiResponse<Map<String, Boolean>> check(@PathVariable String code) {
        return ApiResponse.ok(Map.of("valid", service.usableCode(code)));
    }
}
