package com.wuyao.growth.iam.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.dto.AccountDtos;
import com.wuyao.growth.iam.dto.AuthDtos;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.entity.WechatAccount;
import com.wuyao.growth.iam.repository.UserRepository;
import com.wuyao.growth.iam.repository.SmsCodeRepository;
import com.wuyao.growth.iam.repository.WechatAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 网站应用扫码登录：OAuth state、code 交换和微信身份绑定都只在服务端完成。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatLoginService {
    public record Authorization(String url, String state) {}

    public static final String STATE_COOKIE = "wechat_login_state";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration TICKET_TTL = Duration.ofMinutes(10);
    private static final Duration TICKET_LOCK_TTL = Duration.ofSeconds(30);
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final AuthService authService;
    private final LoginCodeVerifier codeVerifier;
    private final UserRepository users;
    private final WechatAccountRepository accounts;
    private final SmsCodeRepository smsCodes;
    private final TransactionTemplate transactions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    @Value("${growth.wechat.app-id:}")
    private String appId;

    @Value("${growth.wechat.app-secret:}")
    private String appSecret;

    @Value("${growth.wechat.callback-url:}")
    private String callbackUrl;

    @Value("${growth.wechat.frontend-login-path:/login}")
    private String frontendLoginPath;

    /** 已登录的人在设置页扫码绑定微信，完成后回到这里。 */
    @Value("${growth.wechat.frontend-settings-path:/settings}")
    private String frontendSettingsPath;

    /** 其他也在微信授权回调域里的站点域名：从这些域名发起的登录直接回到原域名，不先跳到 callback-url 的域名。 */
    @Value("${growth.wechat.callback-hosts:}")
    private List<String> callbackHosts;

    public String canonicalStartUrl(String requestHost, String redirect) {
        requireConfigured();
        URI callback = URI.create(callbackUrl);
        return callback.getHost().equalsIgnoreCase(requestHost) || isCallbackHost(requestHost) ? null
                : "https://" + callback.getRawAuthority() + "/api/auth/wechat/start?redirect=" + encode(safeRedirect(redirect));
    }

    public Authorization start(String redirect) {
        return start(URI.create(callbackUrl).getHost(), redirect);
    }

    public Authorization start(String requestHost, String redirect) {
        return authorize(requestHost, Map.of("redirect", safeRedirect(redirect)));
    }

    public boolean configured() {
        try {
            requireConfigured();
            return true;
        } catch (BizException e) {
            return false;
        }
    }

    /** 本人的微信绑定情况，设置页展示用。 */
    public AccountDtos.WechatBinding binding(Long userId) {
        return accounts.findFirstByUserIdOrderByIdDesc(userId)
                .map(account -> new AccountDtos.WechatBinding(configured(), true, account.getNickname(), account.getCreatedAt()))
                .orElseGet(() -> new AccountDtos.WechatBinding(configured(), false, null, null));
    }

    /**
     * 已登录的人在设置页扫码绑定微信。和扫码登录共用回调地址，state 里记下是哪个账号在绑定，
     * 回调时直接绑到这个账号上，不再走手机号验证。只能在回调域名下发起，否则浏览器带不回 state cookie。
     */
    public Authorization startBinding(String requestHost, Long userId) {
        if (canonicalStartUrl(requestHost, null) != null) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "请在 " + URI.create(callbackUrl).getHost() + " 打开设置页再绑定微信");
        }
        users.findById(userId).filter(user -> "ACTIVE".equals(user.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED, "账号不可用"));
        if (accounts.findFirstByUserIdOrderByIdDesc(userId).isPresent()) {
            throw BizException.of(ErrorCode.CONFLICT, "这个账号已经绑定了微信");
        }
        return authorize(requestHost, Map.of("bindUserId", userId));
    }

    private Authorization authorize(String requestHost, Map<String, Object> stateData) {
        requireConfigured();
        URI callback = URI.create(callbackUrl);
        String redirectUri = isCallbackHost(requestHost)
                ? "https://" + requestHost.toLowerCase() + (callback.getPort() < 0 ? "" : ":" + callback.getPort())
                        + callback.getRawPath() + (callback.getRawQuery() == null ? "" : "?" + callback.getRawQuery())
                : callbackUrl;
        String state = randomToken();
        redis.opsForValue().set(key("state", state), write(stateData), STATE_TTL);
        String url = "https://open.weixin.qq.com/connect/qrconnect?appid=" + encode(appId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code&scope=snsapi_login&state=" + encode(state)
                + "#wechat_redirect";
        return new Authorization(url, state);
    }

    public String callback(String code, String state, String browserState) {
        requireConfigured();
        if (!StringUtils.hasText(code) || !StringUtils.hasText(state)) {
            throw BizException.of(ErrorCode.WECHAT_LOGIN_FAILED, "微信授权参数不完整");
        }
        if (state.length() > 128 || !state.equals(browserState)) {
            throw BizException.of(ErrorCode.WECHAT_TICKET_INVALID, "请在发起微信登录的浏览器中完成授权");
        }
        String stateJson = redis.opsForValue().getAndDelete(key("state", state));
        if (!StringUtils.hasText(stateJson)) {
            throw BizException.of(ErrorCode.WECHAT_TICKET_INVALID, "微信登录已过期，请重新扫码");
        }
        JsonNode stateNode = read(stateJson);
        if (stateNode.hasNonNull("bindUserId")) return bindCallback(code, stateNode.get("bindUserId").asLong());
        String redirect = safeRedirect(stateNode.path("redirect").asText("/dashboard"));

        JsonNode token = requestToken(code);
        String openId = required(token, "openid");
        JsonNode profile = requestProfile(required(token, "access_token"), openId);
        String unionId = text(token, "unionid");
        if (!StringUtils.hasText(unionId)) unionId = text(profile, "unionid");

        String ticket = randomToken();
        Map<String, Object> ticketData = new HashMap<>();
        ticketData.put("appId", appId);
        ticketData.put("openId", openId);
        ticketData.put("unionId", unionId);
        ticketData.put("nickname", text(profile, "nickname"));
        ticketData.put("headimgUrl", text(profile, "headimgurl"));
        ticketData.put("redirect", redirect);
        redis.opsForValue().set(key("ticket", ticket), write(ticketData), TICKET_TTL);
        return frontendLoginPath + "?redirect=" + encode(redirect) + "#wechat_ticket=" + encode(ticket);
    }

    /** 扫码绑定的回调：换到微信身份就绑到发起绑定的账号上，成功失败都回设置页说明。 */
    private String bindCallback(String code, long userId) {
        try {
            JsonNode token = requestToken(code);
            String openId = required(token, "openid");
            JsonNode profile = requestProfile(required(token, "access_token"), openId);
            String unionId = text(token, "unionid");
            if (!StringUtils.hasText(unionId)) unionId = text(profile, "unionid");
            bind(userId, openId, unionId, text(profile, "nickname"), text(profile, "headimgurl"));
            return frontendSettingsPath + "?wechat_bound=1";
        } catch (BizException e) {
            return frontendSettingsPath + "?wechat_error=" + encode(e.getMessage());
        } catch (RuntimeException e) {
            log.error("设置页绑定微信失败: userId={}", userId, e);
            return frontendSettingsPath + "?wechat_error=" + encode("微信绑定暂时不可用，请稍后重试");
        }
    }

    void bind(Long userId, String openId, String unionId, String nickname, String headimgUrl) {
        try {
            transactions.executeWithoutResult(status -> {
                // 和扫码登录绑定同一把锁：同一个微信不能同时绑到两个账号上。
                accounts.lockIdentity("auth:wechat:" + appId + ":" + openId);
                WechatAccount current = accounts.findByAppIdAndOpenId(appId, openId).orElse(null);
                if (current != null) {
                    if (current.getUserId().equals(userId)) return;
                    throw BizException.of(ErrorCode.CONFLICT, "这个微信已经绑定了其他账号，请换一个微信");
                }
                users.findForUpdate(userId).filter(user -> "ACTIVE".equals(user.getStatus()))
                        .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED, "账号不可用"));
                if (accounts.findFirstByUserIdOrderByIdDesc(userId).isPresent()) {
                    throw BizException.of(ErrorCode.CONFLICT, "这个账号已经绑定了另一个微信");
                }
                WechatAccount account = new WechatAccount();
                account.setAppId(appId);
                account.setOpenId(openId);
                account.setUnionId(unionId);
                account.setUserId(userId);
                account.setNickname(nickname);
                account.setHeadimgUrl(headimgUrl);
                account.setUpdatedAt(Instant.now());
                accounts.saveAndFlush(account);
            });
        } catch (DataIntegrityViolationException e) {
            // union_id 唯一：同一个微信用户在这个应用下已经以另一个 openid 绑过账号。
            throw BizException.of(ErrorCode.CONFLICT, "这个微信已经绑定了其他账号，请换一个微信");
        }
        log.info("设置页绑定微信: userId={}", userId);
    }

    /** 浏览器侧的 state cookie：回调时核对，防止别人把自己发起的授权链接塞给你扫。 */
    public static String stateCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(STATE_COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/api/auth/wechat").maxAge(maxAgeSeconds).build().toString();
    }

    public String errorRedirect(String message) {
        return frontendLoginPath + "?wechat_error=" + encode(StringUtils.hasText(message) ? message : "微信登录失败，请重试");
    }

    public AuthDtos.WechatSession complete(AuthDtos.WechatCompleteRequest request,
                                           String ip, String userAgent, String deviceId) {
        requireConfigured();
        return withTicketLock(request.ticket(), () -> {
            ObjectNode ticket = ticket(request.ticket());
            String openId = required(ticket, "openId");
            String nickname = text(ticket, "nickname");
            WechatAccount bound = accounts.findByAppIdAndOpenId(appId, openId).orElse(null);
            if (bound != null) return loginBound(request.ticket(), bound.getUserId(), nickname, ip, userAgent, deviceId);

            if (StringUtils.hasText(request.phone()) || StringUtils.hasText(request.code())) {
                String phone = request.phone() == null ? "" : request.phone().trim();
                if (!phone.matches("^1[3-9]\\d{9}$") || request.code() == null || !request.code().matches("^\\d{6}$")) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "请输入有效手机号和 6 位验证码");
                }
                codeVerifier.verifyAndConsume(phone, request.code());
                ticket.put("verifiedPhone", phone);
                Long ttl = redis.getExpire(key("ticket", request.ticket()), TimeUnit.MILLISECONDS);
                if (ttl == null || ttl <= 0 || !Boolean.TRUE.equals(redis.opsForValue().setIfPresent(
                        key("ticket", request.ticket()), write(ticket), Duration.ofMillis(ttl)))) {
                    throw invalidTicket();
                }
            }
            String phone = text(ticket, "verifiedPhone");
            if (!StringUtils.hasText(phone)) return new AuthDtos.WechatSession(true, false, nickname, null);

            return transactions.execute(status -> {
                // 不同扫码票据也必须串行绑定同一个微信身份，不能覆盖已完成的绑定。
                accounts.lockIdentity("auth:wechat:" + appId + ":" + openId);
                WechatAccount current = accounts.findByAppIdAndOpenId(appId, openId).orElse(null);
                if (current != null) return loginBound(request.ticket(), current.getUserId(), nickname, ip, userAgent, deviceId);
                smsCodes.lockPhone(phone);
                User user = users.findByPhone(phone).orElse(null);
                if (user != null && !"ACTIVE".equals(user.getStatus())) {
                    throw BizException.of(ErrorCode.WECHAT_LOGIN_FAILED, PlatformAccountDirectory.SUSPENDED.equals(user.getStatus())
                            ? "账号已被平台停用，如有疑问请联系客服" : "账号已停用，请联系商户管理员");
                }
                boolean passwordRequired = user == null || !StringUtils.hasText(user.getPasswordHash());
                if (passwordRequired && !StringUtils.hasText(request.password())) {
                    return new AuthDtos.WechatSession(true, true, nickname, null);
                }
                if (passwordRequired && !validPassword(request.password())) {
                    throw BizException.of(ErrorCode.WECHAT_PASSWORD_REQUIRED, "请设置 8–64 位密码，UTF-8 编码不超过 72 字节");
                }
                consumeTicket(request.ticket());
                if (user == null) user = authService.registerNewUser(phone, request.inviteCode());
                if (!StringUtils.hasText(user.getPasswordHash())) {
                    user.setPasswordHash(PasswordLoginVerifier.hashPassword(request.password()));
                }
                WechatAccount account = new WechatAccount();
                account.setAppId(appId);
                account.setOpenId(openId);
                account.setUnionId(text(ticket, "unionId"));
                account.setUserId(user.getId());
                account.setNickname(nickname);
                account.setHeadimgUrl(text(ticket, "headimgUrl"));
                account.setUpdatedAt(Instant.now());
                accounts.save(account);
                return new AuthDtos.WechatSession(false, false, nickname,
                        authService.issueTokensForUser(user.getId(), ip, userAgent, deviceId));
            });
        });
    }

    private AuthDtos.WechatSession loginBound(String ticket, Long userId, String nickname,
                                             String ip, String userAgent, String deviceId) {
        consumeTicket(ticket);
        try {
            return new AuthDtos.WechatSession(false, false, nickname,
                    authService.issueTokensForUser(userId, ip, userAgent, deviceId));
        } catch (BizException e) {
            if (e.getErrorCode() == ErrorCode.UNAUTHORIZED) {
                throw BizException.of(ErrorCode.WECHAT_LOGIN_FAILED, e.getMessage());
            }
            throw e;
        }
    }

    private JsonNode requestToken(String code) {
        JsonNode result = getJson("https://api.weixin.qq.com/sns/oauth2/access_token?appid=" + encode(appId)
                + "&secret=" + encode(appSecret) + "&code=" + encode(code)
                + "&grant_type=authorization_code");
        if (result.has("errcode") && result.get("errcode").asInt() != 0) {
            log.warn("微信 code 换取 access_token 失败: errcode={}", result.get("errcode").asInt());
            throw BizException.of(ErrorCode.WECHAT_LOGIN_FAILED, "微信授权已失效，请重新扫码");
        }
        return result;
    }

    private JsonNode requestProfile(String accessToken, String openId) {
        try {
            return getJson("https://api.weixin.qq.com/sns/userinfo?access_token=" + encode(accessToken)
                    + "&openid=" + encode(openId) + "&lang=zh_CN");
        } catch (RuntimeException e) {
            log.warn("微信用户资料获取失败，将使用 OpenID 完成登录");
            return json.createObjectNode();
        }
    }

    private JsonNode getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("微信接口 HTTP " + response.statusCode());
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(ErrorCode.WECHAT_LOGIN_FAILED, "微信服务暂时不可用，请稍后重试");
        } catch (Exception e) {
            throw BizException.of(ErrorCode.WECHAT_LOGIN_FAILED, "微信服务暂时不可用，请稍后重试");
        }
    }

    private ObjectNode ticket(String value) {
        if (!StringUtils.hasText(value) || value.length() > 128) {
            throw BizException.of(ErrorCode.WECHAT_TICKET_INVALID, "微信登录已过期，请重新扫码");
        }
        String valueJson = redis.opsForValue().get(key("ticket", value));
        if (!StringUtils.hasText(valueJson)) {
            throw BizException.of(ErrorCode.WECHAT_TICKET_INVALID, "微信登录已过期，请重新扫码");
        }
        JsonNode data = read(valueJson);
        if (!(data instanceof ObjectNode node) || !appId.equals(text(data, "appId"))) throw invalidTicket();
        return node;
    }

    private <T> T withTicketLock(String ticket, java.util.function.Supplier<T> action) {
        if (!StringUtils.hasText(ticket) || ticket.length() > 128) throw invalidTicket();
        String lock = key("ticket-lock", ticket);
        String owner = randomToken();
        Boolean acquired = redis.opsForValue().setIfAbsent(lock, owner, TICKET_LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw BizException.of(ErrorCode.WECHAT_TICKET_INVALID, "微信登录正在处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            redis.execute(RELEASE_LOCK, List.of(lock), owner);
        }
    }

    private void requireConfigured() {
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret) || !StringUtils.hasText(callbackUrl)) {
            throw BizException.of(ErrorCode.WECHAT_NOT_CONFIGURED, "微信登录尚未配置");
        }
        URI callback;
        try {
            callback = URI.create(callbackUrl);
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.WECHAT_NOT_CONFIGURED, "微信回调地址配置不正确");
        }
        if (!"https".equalsIgnoreCase(callback.getScheme()) || !StringUtils.hasText(callback.getHost())
                || callback.getUserInfo() != null || callback.getFragment() != null
                || !frontendLoginPath.matches("^/(?!/)[^\\s\\\\?#]*$")) {
            throw BizException.of(ErrorCode.WECHAT_NOT_CONFIGURED, "微信回调地址和登录页路径配置不正确");
        }
    }

    private boolean isCallbackHost(String host) {
        return host != null && callbackHosts.stream().anyMatch(allowed -> allowed.trim().equalsIgnoreCase(host));
    }

    private boolean validPassword(String password) {
        return StringUtils.hasText(password) && password.length() >= 8 && password.length() <= 64
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private void consumeTicket(String ticket) {
        if (!StringUtils.hasText(redis.opsForValue().getAndDelete(key("ticket", ticket)))) throw invalidTicket();
    }

    private BizException invalidTicket() {
        return BizException.of(ErrorCode.WECHAT_TICKET_INVALID, "微信登录已过期，请重新扫码");
    }

    private String key(String type, String value) {
        return "auth:wechat:" + type + ":" + value;
    }

    private String randomToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    private String safeRedirect(String value) {
        return value != null && value.matches("^/(?!/)[^\\s\\\\]*$")
                && !value.matches("^/login(?:[/?#].*)?$") ? value : "/dashboard";
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String required(JsonNode node, String field) {
        String value = text(node, field);
        if (!StringUtils.hasText(value)) throw BizException.of(ErrorCode.WECHAT_LOGIN_FAILED, "微信返回数据不完整");
        return value;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("微信登录状态序列化失败", e);
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception e) {
            throw BizException.of(ErrorCode.WECHAT_TICKET_INVALID, "微信登录状态无效，请重新扫码");
        }
    }
}
