package com.wuyao.growth.iam;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.security.ClientIpResolver;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.iam.dto.AuthDtos;
import com.wuyao.growth.iam.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
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
