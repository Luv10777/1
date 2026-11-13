package com.wuyao.growth.iam;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.security.ClientIpResolver;
import com.wuyao.growth.common.web.ApiResponse;
import com.wuyao.growth.iam.dto.AccountDtos;
import com.wuyao.growth.iam.dto.AuthDtos;
import com.wuyao.growth.iam.service.AccountClosureService;
import com.wuyao.growth.iam.service.AccountService;
import com.wuyao.growth.iam.service.AccountAvatarService;
import com.wuyao.growth.iam.service.AuthService;
import com.wuyao.growth.iam.service.WechatLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 登录者管理自己的账号，放在 /api/auth 下：改名改密码不需要开通会员。只改自己：用户 ID 从 token 来，接口不接受传参。 */
@RestController
@RequestMapping("/api/auth/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accounts;
    private final AccountAvatarService avatars;
    private final AuthService authService;
    private final AccountClosureService closures;
    private final ClientIpResolver clientIps;
    private final WechatLoginService wechat;

    @GetMapping
    public ApiResponse<AccountDtos.Profile> profile(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(accounts.profile(me.userId()));
    }

    @PutMapping("/name")
    public ApiResponse<AccountDtos.Profile> rename(@AuthenticationPrincipal AuthPrincipal me,
                                                   @Valid @RequestBody AccountDtos.RenameRequest req) {
        return ApiResponse.ok(accounts.renameSelf(me.userId(), req.name()));
    }

    @PostMapping("/avatar/upload-url")
    public ApiResponse<AccountDtos.AvatarUploadTicket> avatarUpload(@AuthenticationPrincipal AuthPrincipal me,
            @Valid @RequestBody AccountDtos.AvatarUploadRequest req) {
        return ApiResponse.ok(avatars.presign(me.userId()));
    }

    @PutMapping("/avatar")
    public ApiResponse<AccountDtos.Profile> avatar(@AuthenticationPrincipal AuthPrincipal me,
            @Valid @RequestBody AccountDtos.AvatarRequest req) {
        return ApiResponse.ok(avatars.save(me.userId(), req.uploadKey()));
    }

    /** 成功后其他设备上的登录全部失效；返回给这台设备的新令牌，前端直接换上。 */
    @PutMapping("/password")
    public ApiResponse<AuthDtos.TokenPair> changePassword(@AuthenticationPrincipal AuthPrincipal me,
                                                          @Valid @RequestBody AccountDtos.PasswordRequest req,
                                                          HttpServletRequest http) {
        return ApiResponse.ok(authService.changePassword(me.userId(), req.currentPassword(), req.code(),
                req.newPassword(), clientIps.resolve(http), http.getHeader("User-Agent"), http.getHeader("X-Device-Id")));
    }

    /** 改绑或绑定手机号第一步：用原手机号收到的验证码或当前密码确认是本人。 */
    @PostMapping("/phone/verify")
    public ApiResponse<Void> verifyPhoneChange(@AuthenticationPrincipal AuthPrincipal me,
                                               @Valid @RequestBody AccountDtos.PhoneProofRequest req) {
        authService.verifyPhoneChangeProof(me.userId(), req.code(), req.currentPassword());
        return ApiResponse.ok();
    }

    /** 第二步：新手机号和发到新手机号的验证码。返回更新后的资料。 */
    @PutMapping("/phone")
    public ApiResponse<AccountDtos.Profile> changePhone(@AuthenticationPrincipal AuthPrincipal me,
                                                        @Valid @RequestBody AccountDtos.PhoneChangeRequest req) {
        authService.changePhone(me.userId(), req.phone(), req.code());
        return ApiResponse.ok(accounts.profile(me.userId()));
    }

    @GetMapping("/wechat")
    public ApiResponse<AccountDtos.WechatBinding> wechat(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(wechat.binding(me.userId()));
    }

    /** 扫码绑定微信：返回微信授权页地址并写入 state cookie，前端整页跳过去扫码。 */
    @PostMapping("/wechat/bind")
    public ApiResponse<AccountDtos.WechatBindStart> bindWechat(@AuthenticationPrincipal AuthPrincipal me,
                                                               HttpServletRequest http, HttpServletResponse response) {
        var authorization = wechat.startBinding(http.getServerName(), me.userId());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.addHeader(HttpHeaders.SET_COOKIE, WechatLoginService.stateCookie(authorization.state(), 600));
        return ApiResponse.ok(new AccountDtos.WechatBindStart(authorization.url()));
    }

    /** 注销前的说明：只注销自己还是连商户一起关闭、会作废什么、现在能不能注销。 */
    @GetMapping("/closure")
    public ApiResponse<AccountDtos.ClosurePreview> closurePreview(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(closures.preview(me.userId()));
    }

    /** 注销立即生效，所有设备上的登录随之失效，不能恢复。 */
    @PostMapping("/closure")
    public ApiResponse<Void> close(@AuthenticationPrincipal AuthPrincipal me,
                                   @Valid @RequestBody AccountDtos.ClosureRequest req) {
        closures.close(me.userId(), req.code(), req.currentPassword());
        return ApiResponse.ok();
    }
}
