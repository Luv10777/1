package com.wuyao.growth.common.security;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

/**
 * 每个请求解一次 JWT，同时把租户号塞进 {@link TenantContext}。
 * 请求结束必须 clear——线程是复用的，不清会串租户。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                AuthPrincipal principal = jwtService.parse(header.substring(7), "access");
                if (principal != null && isUsable(principal)) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    TenantContext.set(principal.tenantId());
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isUsable(AuthPrincipal principal) {
        try {
            return userRepository.findById(principal.userId())
                    .filter(user -> "ACTIVE".equals(user.getStatus()))
                    .filter(user -> principal.tenantId().equals(user.getTenantId()))
                    .filter(user -> principal.tokenVersion() == user.getTokenVersion())
                    .isPresent();
        } catch (RuntimeException e) {
            // Fail closed if account state cannot be checked.
            log.error("认证状态检查失败，拒绝当前请求", e);
            return false;
        }
    }
}
