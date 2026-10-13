package com.wuyao.growth.billing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Everyone can browse business pages; changing data and starting work require membership. */
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
