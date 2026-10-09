package com.wuyao.growth.iam.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.iam.dto.AuthDtos;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.config.import=", "growth.worker.enabled=false", "logging.level.root=WARN",
        "growth.storage.endpoint=http://127.0.0.1:9000", "growth.storage.access-key=testadmin",
        "growth.storage.secret-key=testadmin123", "growth.storage.bucket=test-assets",
        "spring.data.redis.client-type=jedis", "growth.wechat.app-id=wx-test-app",
        "growth.wechat.app-secret=test-only-secret",
        "growth.wechat.callback-url=https://login.example.com/api/auth/wechat/callback"})
@AutoConfigureMockMvc
@Testcontainers
class WechatLoginIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wechat_test").withUsername("growth_owner").withPassword("test_owner_password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "growth_app");
        registry.add("spring.datasource.password", () -> "growth_dev_local");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.app_db_password", () -> "growth_dev_local");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("growth.jwt.secret", () -> "test-only-random-signing-secret-0123456789abcdef");
    }

    @Autowired WechatLoginService wechat;
    @Autowired AuthService auth;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    final HttpClient http = mock(HttpClient.class);
    JdbcTemplate owner;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants, sms_codes RESTART IDENTITY CASCADE");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        reset(http);
        ReflectionTestUtils.setField(wechat, "http", http);
    }

    @Test
    void startUsesWebsiteScopeAndSetsAProtectedBrowserCookie() throws Exception {
        var response = mvc.perform(get("/api/auth/wechat/start").header("Host", "login.example.com").param("redirect", "//external.example.com"))
                .andExpect(status().isFound()).andReturn().getResponse();
        String location = response.getRedirectedUrl();
        assertThat(location).startsWith("https://open.weixin.qq.com/connect/qrconnect?")
                .contains("scope=snsapi_login", "appid=wx-test-app").doesNotContain("test-only-secret");
        assertThat(response.getHeader("Set-Cookie")).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api/auth/wechat");
        String state = param(URI.create(location).getRawQuery(), "state");
        JsonNode stored = json.readTree(redis.opsForValue().get("auth:wechat:state:" + state));
        assertThat(stored.path("redirect").asText()).isEqualTo("/dashboard");
        assertThat(redis.getExpire("auth:wechat:state:" + state)).isBetween(590L, 600L);
    }

    @Test
    void startFirstMovesToTheConfiguredCallbackHostSoTheBrowserCookieMatches() throws Exception {
        var response = mvc.perform(get("/api/auth/wechat/start").header("Host", "www.login.example.com").param("redirect", "/works"))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertThat(response.getRedirectedUrl()).isEqualTo("https://login.example.com/api/auth/wechat/start?redirect=%2Fworks");
        assertThat(response.getHeader("Set-Cookie")).isNull();
        var authorization = wechat.start("/login?mode=register");
        JsonNode saved = json.readTree(redis.opsForValue().get("auth:wechat:state:" + authorization.state()));
        assertThat(saved.path("redirect").asText()).isEqualTo("/dashboard");
    }

    @Test
    void callbackRejectsMissingExpiredReusedAndForeignBrowserState() throws Exception {
        var start = wechat.start("/works");
        assertThatThrownBy(() -> wechat.callback("code", null, start.state())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> wechat.callback("code", start.state(), "another-browser")).isInstanceOf(BizException.class);
        assertThat(redis.hasKey("auth:wechat:state:" + start.state())).isTrue();
        redis.delete("auth:wechat:state:" + start.state());
        assertThatThrownBy(() -> wechat.callback("code", start.state(), start.state())).isInstanceOf(BizException.class);
        mvc.perform(get("/api/auth/wechat/callback").param("code", "code").param("state", "missing")
                        .cookie(new Cookie("wechat_login_state", "missing")))
                .andExpect(status().isFound());
        verifyNoInteractions(http);
    }

    @Test
    void callbackExchangesCodeAndReturnsOnlyAShortLivedFragmentTicket() throws Exception {
        stubWechat("{\"openid\":\"open-1\",\"access_token\":\"wechat-provider-token\",\"unionid\":\"union-1\"}",
                "{\"nickname\":\"Test nickname\",\"headimgurl\":\"https://image.example.com/avatar.jpg\"}");
        var start = wechat.start("/works?filter=draft");
        var response = mvc.perform(get("/api/auth/wechat/callback").param("code", "test-code").param("state", start.state())
                        .cookie(new Cookie("wechat_login_state", start.state())))
                .andExpect(status().isFound()).andReturn().getResponse();
        URI callback = URI.create(response.getRedirectedUrl());
        assertThat(callback.getRawQuery()).doesNotContain("wechat_ticket", "access_token", "openid", "secret");
        assertThat(param(callback.getRawQuery(), "redirect")).isEqualTo("/works?filter=draft");
        String ticket = param(callback.getRawFragment(), "wechat_ticket");
        JsonNode stored = json.readTree(redis.opsForValue().get("auth:wechat:ticket:" + ticket));
        assertThat(stored.path("openId").asText()).isEqualTo("open-1");
        assertThat(stored.path("nickname").asText()).isEqualTo("Test nickname");
        assertThat(stored.has("access_token")).isFalse();
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
        assertThatThrownBy(() -> wechat.callback("test-code", start.state(), start.state())).isInstanceOf(BizException.class);
    }

    @Test
    void providerErrorsReturnAnActionableLoginErrorWithoutCredentials() throws Exception {
        stubWechat("{\"errcode\":40029,\"errmsg\":\"invalid code\"}", "{}");
        var start = wechat.start("/works");
        var response = mvc.perform(get("/api/auth/wechat/callback").param("code", "bad-code").param("state", start.state())
                        .cookie(new Cookie("wechat_login_state", start.state())))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertThat(response.getRedirectedUrl()).startsWith("/login?wechat_error=")
                .doesNotContain("test-only-secret", "bad-code", "wechat_ticket");
    }

    @Test
    void firstLoginVerifiesPhoneBeforeAskingForPasswordAndConsumesSmsOnlyOnce() throws Exception {
        String ticket = seedTicket("open-new");
        var initial = complete(ticket, null, null, null);
        assertThat(initial.requiresBinding()).isTrue();
        assertThat(initial.passwordRequired()).isFalse();
        seedCode("13800000001", "123456");
        var verified = complete(ticket, "13800000001", "123456", null);
        assertThat(verified.requiresBinding()).isTrue();
        assertThat(verified.passwordRequired()).isTrue();
        assertThat(count("users")).isZero();
        assertThat(owner.queryForObject("SELECT consumed_at IS NOT NULL FROM sms_codes", Boolean.class)).isTrue();
        assertThatThrownBy(() -> complete(ticket, null, null, "密".repeat(25))).isInstanceOf(BizException.class);
        assertThat(redis.hasKey("auth:wechat:ticket:" + ticket)).isTrue();
        var loggedIn = complete(ticket, null, null, "wechat-password");
        assertThat(loggedIn.requiresBinding()).isFalse();
        assertThat(loggedIn.tokenPair().user().phone()).isEqualTo("13800000001");
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("wechat_accounts")).isEqualTo(1);
        assertThat(redis.hasKey("auth:wechat:ticket:" + ticket)).isFalse();
        assertThatThrownBy(() -> complete(ticket, null, null, "wechat-password")).isInstanceOf(BizException.class);
        var passwordLogin = auth.loginWithPassword("13800000001", "wechat-password", null, null, null);
        assertThat(passwordLogin.user().userId()).isEqualTo(loggedIn.tokenPair().user().userId());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + loggedIn.tokenPair().accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value("13800000001"));
    }

    @Test
    void existingPhonePreservesItsPasswordAndReturnsTheSameUser() throws Exception {
        long userId = seedUser("13800000001", "existing-password");
        String originalHash = owner.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, userId);
        seedCode("13800000001", "123456");
        var result = complete(seedTicket("open-existing"), "13800000001", "123456", "replacement-password");
        assertThat(result.tokenPair().user().userId()).isEqualTo(userId);
        assertThat(result.passwordRequired()).isFalse();
        assertThat(owner.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, userId)).isEqualTo(originalHash);
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void phoneWithoutAPasswordUsesThePasswordStepWithoutCreatingAnotherAccount() throws Exception {
        long userId = seedUser("13800000001", null);
        String ticket = seedTicket("open-no-password");
        seedCode("13800000001", "123456");
        assertThat(complete(ticket, "13800000001", "123456", null).passwordRequired()).isTrue();
        assertThat(complete(ticket, null, null, "wechat-password").tokenPair().user().userId()).isEqualTo(userId);
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void invalidSmsDoesNotRevealPasswordStatusOrBindAnAccount() throws Exception {
        seedUser("13800000001", "existing-password");
        String ticket = seedTicket("open-invalid");
        seedCode("13800000001", "123456");
        assertThatThrownBy(() -> complete(ticket, "13800000001", "654321", null)).isInstanceOf(BizException.class);
        assertThat(json.readTree(redis.opsForValue().get("auth:wechat:ticket:" + ticket)).has("verifiedPhone")).isFalse();
        assertThat(count("wechat_accounts")).isZero();
        assertThat(owner.queryForObject("SELECT attempts FROM sms_codes", Integer.class)).isEqualTo(1);
    }

    @Test
    void boundWechatDoesNotNeedSmsAndCannotBeMovedToAnotherPhone() throws Exception {
        long userId = seedUser("13800000001", "existing-password");
        bind("open-bound", userId);
        var result = complete(seedTicket("open-bound"), "13800000002", "123456", null);
        assertThat(result.tokenPair().user().userId()).isEqualTo(userId);
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("sms_codes")).isZero();
        owner.update("UPDATE users SET status='DISABLED' WHERE id=?", userId);
        assertThatThrownBy(() -> complete(seedTicket("open-bound"), null, null, null)).isInstanceOf(BizException.class);
    }

    @Test
    void expiredAndConcurrentTicketsCannotIssueTwoSessions() throws Exception {
        long userId = seedUser("13800000001", "existing-password");
        bind("open-bound", userId);
        String expired = seedTicket("open-bound");
        redis.delete("auth:wechat:ticket:" + expired);
        assertThatThrownBy(() -> complete(expired, null, null, null)).isInstanceOf(BizException.class);
        String ticket = seedTicket("open-bound");
        assertThat(concurrent(() -> outcome(() -> complete(ticket, null, null, null)),
                () -> outcome(() -> complete(ticket, null, null, null)))).containsExactlyInAnyOrder(200, 2012);
        assertThat(count("refresh_tokens")).isEqualTo(1);
    }

    @Test
    void differentTicketsForTheSameWechatCannotOverwriteTheBinding() throws Exception {
        long first = seedUser("13800000001", "existing-password");
        long second = seedUser("13800000002", "existing-password");
        String ticketA = seedTicket("open-race");
        String ticketB = seedTicket("open-race");
        seedCode("13800000001", "123456");
        seedCode("13800000002", "654321");
        assertThat(concurrent(() -> complete(ticketA, "13800000001", "123456", null).tokenPair().user().userId(),
                () -> complete(ticketB, "13800000002", "654321", null).tokenPair().user().userId()))
                .allMatch(id -> id.equals(first) || id.equals(second)).allMatch(id -> id.equals(
                        owner.queryForObject("SELECT user_id FROM wechat_accounts WHERE open_id='open-race'", Long.class)));
        assertThat(count("wechat_accounts")).isEqualTo(1);
    }

    @Test
    void twoWechatIdentitiesRegisteringOnePhoneCreateOnlyOneUser() throws Exception {
        String ticketA = seedTicket("open-phone-a");
        String ticketB = seedTicket("open-phone-b");
        markVerified(ticketA, "13800000001");
        markVerified(ticketB, "13800000001");
        assertThat(concurrent(() -> complete(ticketA, null, null, "wechat-password").tokenPair().user().userId(),
                () -> complete(ticketB, null, null, "wechat-password").tokenPair().user().userId()))
                .containsExactly(1L, 1L);
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("tenants")).isEqualTo(1);
        assertThat(count("wechat_accounts")).isEqualTo(2);
    }

    private AuthDtos.WechatSession complete(String ticket, String phone, String code, String password) {
        return wechat.complete(new AuthDtos.WechatCompleteRequest(ticket, phone, code, password), "127.0.0.1", "test", null);
    }

    private String seedTicket(String openId) throws Exception {
        String ticket = UUID.randomUUID().toString();
        redis.opsForValue().set("auth:wechat:ticket:" + ticket,
                json.writeValueAsString(Map.of("appId", "wx-test-app", "openId", openId, "nickname", "Test user")), Duration.ofMinutes(10));
        return ticket;
    }

    private void markVerified(String ticket, String phone) throws Exception {
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(redis.opsForValue().get("auth:wechat:ticket:" + ticket));
        data.put("verifiedPhone", phone);
        redis.opsForValue().set("auth:wechat:ticket:" + ticket, json.writeValueAsString(data), Duration.ofMinutes(10));
    }

    private long seedUser(String phone, String password) {
        Long tenantId = owner.queryForObject("INSERT INTO tenants(name) VALUES ('Test merchant') RETURNING id", Long.class);
        return owner.queryForObject("INSERT INTO users(tenant_id,phone,password_hash) VALUES (?,?,?) RETURNING id", Long.class,
                tenantId, phone, password == null ? null : PasswordLoginVerifier.hashPassword(password));
    }

    private void seedCode(String phone, String code) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        owner.update("INSERT INTO sms_codes(phone,code_hash,expires_at) VALUES (?,?,now()+interval '5 minutes')", phone, hash);
    }

    private void bind(String openId, long userId) {
        owner.update("INSERT INTO wechat_accounts(app_id,open_id,user_id) VALUES ('wx-test-app',?,?)", openId, userId);
    }

    private int count(String table) {
        return owner.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String param(String query, String name) {
        for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            if (pair[0].equals(name)) return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        throw new AssertionError("Missing " + name);
    }

    @SuppressWarnings("unchecked")
    private void stubWechat(String tokenBody, String profileBody) throws Exception {
        HttpResponse<String> token = mock(HttpResponse.class);
        when(token.statusCode()).thenReturn(200);
        when(token.body()).thenReturn(tokenBody);
        HttpResponse<String> profile = mock(HttpResponse.class);
        when(profile.statusCode()).thenReturn(200);
        when(profile.body()).thenReturn(profileBody);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(token, profile);
    }

    private int outcome(Supplier<?> action) {
        try { action.get(); return 200; }
        catch (BizException e) { return e.getErrorCode().getCode(); }
    }

    private <T> java.util.List<T> concurrent(Supplier<T> first, Supplier<T> second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return first.get(); });
            var b = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return second.get(); });
            return java.util.List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }
}
