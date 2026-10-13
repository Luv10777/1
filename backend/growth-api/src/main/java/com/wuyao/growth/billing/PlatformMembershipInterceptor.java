package com.wuyao.growth.billing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Membership is enforced on business APIs as well as page navigation. */
@Component
@RequiredArgsConstructor
public class PlatformMembershipInterceptor implements HandlerInterceptor {
    private final BillingService billing;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        billing.requirePlatformMembership();
        return true;
    }
}
