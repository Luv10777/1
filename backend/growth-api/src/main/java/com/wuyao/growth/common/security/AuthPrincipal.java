package com.wuyao.growth.common.security;

/** 当前登录者。从 JWT 解出来，controller 里用 @AuthenticationPrincipal 拿。 */
public record AuthPrincipal(Long userId, Long tenantId, String phone) {
}
