package com.wuyao.growth.billing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 直播模块的写请求要求会员生效中；浏览不受限。挂在哪些路径见 WebConfig。 */
@Component
@RequiredArgsConstructor
public class PlatformMembershipInterceptor implements HandlerInterceptor {
    private final BillingService billing;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        switch (request.getMethod()) {
            case "GET", "HEAD", "OPTIONS" -> { }
            default -> billing.requirePlatformMembership();
        }
        return true;
    }
}
