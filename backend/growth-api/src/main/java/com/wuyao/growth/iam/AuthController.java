package com.wuyao.growth.iam;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.security.ClientIpResolver;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.iam.dto.AuthDtos;
import com.wuyao.growth.iam.service.AuthService;
import com.wuyao.growth.iam.service.WechatLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private static final String WECHAT_STATE_COOKIE = "wechat_login_state";

    private final AuthService authService;
    private final WechatLoginService wechatLoginService;
    private final ClientIpResolver clientIps;

    @PostMapping("/send-code")
    public ApiResponse<AuthDtos.SendCodeResult> sendCode(@Valid @RequestBody AuthDtos.SendCodeRequest req,
                                      HttpServletRequest http) {
        return ApiResponse.ok(authService.sendCode(req.phone(), clientIps.resolve(http)));
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.TokenPair> login(@Valid @RequestBody AuthDtos.LoginRequest req,
                                                 HttpServletRequest http) {
        return ApiResponse.ok(authService.login(
                req.phone(), req.code(), clientIps.resolve(http),
                http.getHeader("User-Agent"), http.getHeader("X-Device-Id")));
    }

    @PostMapping("/password-login")
    public ApiResponse<AuthDtos.TokenPair> passwordLogin(@Valid @RequestBody AuthDtos.PasswordLoginRequest req,
                                                        HttpServletRequest http) {
        return ApiResponse.ok(authService.loginWithPassword(req.account(), req.password(), clientIps.resolve(http),
                http.getHeader("User-Agent"), http.getHeader("X-Device-Id")));
    }

    @GetMapping("/wechat/start")
    public void wechatStart(@RequestParam(required = false) String redirect,
                            HttpServletRequest http,
                            HttpServletResponse response) throws java.io.IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String canonical = wechatLoginService.canonicalStartUrl(http.getServerName(), redirect);
        if (canonical != null) {
            response.sendRedirect(canonical);
            return;
        }
        var authorization = wechatLoginService.start(redirect);
        setWechatCookie(response, authorization.state(), 600);
        response.sendRedirect(authorization.url());
    }

    @GetMapping("/wechat/callback")
    public void wechatCallback(@RequestParam(required = false) String code,
                               @RequestParam(required = false) String state,
                               @CookieValue(name = WECHAT_STATE_COOKIE, required = false) String browserState,
                               HttpServletResponse response) throws java.io.IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        setWechatCookie(response, "", 0);
        try {
            response.sendRedirect(wechatLoginService.callback(code, state, browserState));
        } catch (BizException e) {
            response.sendRedirect(wechatLoginService.errorRedirect(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("微信授权回调处理失败", e);
            response.sendRedirect(wechatLoginService.errorRedirect("微信登录暂时不可用，请稍后重试"));
        }
    }

    private void setWechatCookie(HttpServletResponse response, String value, long maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(WECHAT_STATE_COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/api/auth/wechat").maxAge(maxAge).build().toString());
    }

    @PostMapping("/wechat/complete")
    public ApiResponse<AuthDtos.WechatSession> wechatComplete(
            @Valid @RequestBody AuthDtos.WechatCompleteRequest req,
            HttpServletRequest http) {
        return ApiResponse.ok(wechatLoginService.complete(req, clientIps.resolve(http),
                http.getHeader("User-Agent"), http.getHeader("X-Device-Id")));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthDtos.TokenPair> refresh(@Valid @RequestBody AuthDtos.RefreshRequest req,
                                                   HttpServletRequest http) {
        return ApiResponse.ok(authService.refresh(
                req.refreshToken(), clientIps.resolve(http),
                http.getHeader("User-Agent"), http.getHeader("X-Device-Id")));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthPrincipal me) {
        authService.logout(me.userId());
        return ApiResponse.ok();
    }

    /** 当前登录者。注意 tenantId 从 token 来，前端不需要也不允许传。 */
    @GetMapping("/me")
    public ApiResponse<AuthDtos.UserInfo> me(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(authService.currentUser(me.userId()));
    }

}
