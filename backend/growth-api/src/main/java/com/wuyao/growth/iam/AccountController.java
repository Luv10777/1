package com.wuyao.growth.iam;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.security.ClientIpResolver;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.iam.dto.AccountDtos;
import com.wuyao.growth.iam.dto.AuthDtos;
import com.wuyao.growth.iam.service.AccountService;
import com.wuyao.growth.iam.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 登录者管理自己的账号，放在 /api/auth 下：改名改密码不需要开通会员。只改自己：用户 ID 从 token 来，接口不接受传参。 */
@RestController
@RequestMapping("/api/auth/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accounts;
    private final AuthService authService;
    private final ClientIpResolver clientIps;

    @GetMapping
    public ApiResponse<AccountDtos.Profile> profile(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(accounts.profile(me.userId()));
    }

    @PutMapping("/name")
    public ApiResponse<AccountDtos.Profile> rename(@AuthenticationPrincipal AuthPrincipal me,
                                                   @Valid @RequestBody AccountDtos.RenameRequest req) {
        return ApiResponse.ok(accounts.renameSelf(me.userId(), req.name()));
    }

    /** 成功后其他设备上的登录全部失效；返回给这台设备的新令牌，前端直接换上。 */
    @PutMapping("/password")
    public ApiResponse<AuthDtos.TokenPair> changePassword(@AuthenticationPrincipal AuthPrincipal me,
                                                          @Valid @RequestBody AccountDtos.PasswordRequest req,
                                                          HttpServletRequest http) {
        return ApiResponse.ok(authService.changePassword(me.userId(), req.currentPassword(), req.code(),
                req.newPassword(), clientIps.resolve(http), http.getHeader("User-Agent"), http.getHeader("X-Device-Id")));
    }
}
