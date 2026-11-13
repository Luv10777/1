package com.wuyao.growth.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.iam.service.WechatLoginService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.crypto.bcrypt.BCrypt.gensalt;
import static org.springframework.security.crypto.bcrypt.BCrypt.hashpw;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 设置页里改绑、绑定手机号和扫码绑定微信。全部经真实的 HTTP 接口、数据库和 Redis。 */
@SpringBootTest(properties = {"spring.config.import=", "growth.worker.enabled=false", "logging.level.root=WARN",
        "spring.data.redis.client-type=jedis", "growth.ai.writer.url=", "growth.voice.sample-storage=minio",
        "growth.wechat.app-id=wx-test-app", "growth.wechat.app-secret=test-only-secret",
        "growth.wechat.callback-url=https://login.example.com/api/auth/wechat/callback"})
@AutoConfigureMockMvc
@Testcontainers
class AccountBindingIntegrationTest {
    private static final String PHONE = "13900000021";
    private static final String NEW_PHONE = "13900000022";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("binding_test").withUsername("growth_owner").withPassword("test_owner_password");

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

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired WechatLoginService wechat;
    @MockitoBean ObjectStorage storage;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = mock(HttpClient.class);
    JdbcTemplate owner;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE sms_codes, tenants RESTART IDENTITY CASCADE");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        reset(http);
        ReflectionTestUtils.setField(wechat, "http", http);
    }

    @Test
    void changingThePhoneNeedsTheOldPhoneThenTheNewOneAndMovesBothLogins() throws Exception {
        JsonNode session = login(PHONE);
        String token = session.path("accessToken").asText();
        owner.update("UPDATE users SET password_hash = ? WHERE phone = ?", hashpw("secret-123", gensalt(4)), PHONE);

        // 没先确认身份，不能直接换号。
        seedCode(NEW_PHONE, "222222");
        assertThat(refused(put("/api/auth/account/phone"), token, Map.of("phone", NEW_PHONE, "code", "222222"), 400, 1400))
                .contains("身份确认");

        seedCode(PHONE, "333333");
        refused(post("/api/auth/account/phone/verify"), token, Map.of("code", "444444"), 401, 2003);
        api(post("/api/auth/account/phone/verify"), token, Map.of("code", "333333"));

        refused(put("/api/auth/account/phone"), token, Map.of("phone", NEW_PHONE, "code", "999999"), 401, 2003);
        refused(put("/api/auth/account/phone"), token, Map.of("phone", PHONE, "code", "222222"), 400, 1400);
        JsonNode profile = api(put("/api/auth/account/phone"), token, Map.of("phone", NEW_PHONE, "code", "222222"));
        assertThat(profile.path("phone").asText()).isEqualTo(NEW_PHONE);

        // 已登录的这台不受影响；新号码能用密码和验证码登录，原号码已释放。
        assertThat(api(get("/api/auth/me"), token, null).path("phone").asText()).isEqualTo(NEW_PHONE);
        api(post("/api/auth/password-login"), null, Map.of("account", NEW_PHONE, "password", "secret-123"));
        refused(post("/api/auth/password-login"), null, Map.of("account", PHONE, "password", "secret-123"), 401, 2009);
        assertThat(login(NEW_PHONE).path("user").path("userId").asLong()).isEqualTo(session.path("user").path("userId").asLong());

        // 一次身份确认只能换一次号。
        seedCode("13900000023", "555555");
        refused(put("/api/auth/account/phone"), token, Map.of("phone", "13900000023", "code", "555555"), 400, 1400);
    }

    @Test
    void aPhoneTakenByAnotherAccountCannotBeBound() throws Exception {
        String token = login(PHONE).path("accessToken").asText();
        login(NEW_PHONE);
        seedCode(PHONE, "333333");
        api(post("/api/auth/account/phone/verify"), token, Map.of("code", "333333"));
        seedCode(NEW_PHONE, "222222");
        assertThat(refused(put("/api/auth/account/phone"), token, Map.of("phone", NEW_PHONE, "code", "222222"), 409, 1409))
                .contains("已经注册");
        assertThat(owner.queryForObject("SELECT count(*) FROM users WHERE phone = ?", Integer.class, PHONE)).isOne();
    }

    @Test
    void anAccountWithoutAPhoneBindsOneAfterItsPassword() throws Exception {
        Long tenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('丁商户') RETURNING id", Long.class);
        owner.update("INSERT INTO users(tenant_id, username, name, password_hash) VALUES (?, 'shop-admin', '店长', ?)",
                tenant, hashpw("old-secret-1", gensalt(4)));
        String token = api(post("/api/auth/password-login"), null,
                Map.of("account", "shop-admin", "password", "old-secret-1")).path("accessToken").asText();

        assertThat(refused(post("/api/auth/account/phone/verify"), token, Map.of("code", "111111"), 400, 1400))
                .contains("没有绑定手机号");
        refused(post("/api/auth/account/phone/verify"), token, Map.of("currentPassword", "wrong-guess"), 401, 2009);
        api(post("/api/auth/account/phone/verify"), token, Map.of("currentPassword", "old-secret-1"));
        seedCode(NEW_PHONE, "222222");
        assertThat(api(put("/api/auth/account/phone"), token, Map.of("phone", NEW_PHONE, "code", "222222"))
                .path("phone").asText()).isEqualTo(NEW_PHONE);
        api(post("/api/auth/password-login"), null, Map.of("account", NEW_PHONE, "password", "old-secret-1"));
    }

    @Test
    void scanningInSettingsBindsTheWechatToTheSignedInAccountOnly() throws Exception {
        JsonNode session = login(PHONE);
        String token = session.path("accessToken").asText();
        long userId = session.path("user").path("userId").asLong();
        JsonNode before = api(get("/api/auth/account/wechat"), token, null);
        assertThat(before.path("available").asBoolean()).isTrue();
        assertThat(before.path("bound").asBoolean()).isFalse();

        var response = mvc.perform(prepared(post("/api/auth/account/wechat/bind").header("Host", "login.example.com"), token, null))
                .andExpect(status().isOk()).andReturn().getResponse();
        String url = json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data").path("url").asText();
        assertThat(url).startsWith("https://open.weixin.qq.com/connect/qrconnect?").doesNotContain("test-only-secret");
        assertThat(response.getHeader("Set-Cookie")).contains("wechat_login_state=", "HttpOnly", "Path=/api/auth/wechat");
        String state = param(URI.create(url).getRawQuery(), "state");

        // 回调要带着发起绑定那个浏览器的 cookie。
        stubWechat("{\"openid\":\"open-settings\",\"access_token\":\"provider-token\"}", "{\"nickname\":\"老王\"}");
        String location = mvc.perform(get("/api/auth/wechat/callback").param("code", "c").param("state", state)
                        .cookie(new Cookie("wechat_login_state", state)))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        assertThat(location).isEqualTo("/settings?wechat_bound=1");
        assertThat(owner.queryForObject("SELECT user_id FROM wechat_accounts WHERE open_id = 'open-settings'", Long.class))
                .isEqualTo(userId);
        JsonNode after = api(get("/api/auth/account/wechat"), token, null);
        assertThat(after.path("bound").asBoolean()).isTrue();
        assertThat(after.path("nickname").asText()).isEqualTo("老王");

        // 已经绑过的账号不能再发起；同一个微信也不能被另一个账号绑走。
        refused(post("/api/auth/account/wechat/bind").header("Host", "login.example.com"), token, null, 409, 1409);
        String other = login(NEW_PHONE).path("accessToken").asText();
        var second = mvc.perform(prepared(post("/api/auth/account/wechat/bind").header("Host", "login.example.com"), other, null))
                .andExpect(status().isOk()).andReturn().getResponse();
        String secondState = param(URI.create(json.readTree(second.getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("url").asText()).getRawQuery(), "state");
        stubWechat("{\"openid\":\"open-settings\",\"access_token\":\"provider-token\"}", "{}");
        String refusedAt = mvc.perform(get("/api/auth/wechat/callback").param("code", "c").param("state", secondState)
                        .cookie(new Cookie("wechat_login_state", secondState)))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        assertThat(refusedAt).startsWith("/settings?wechat_error=");
        assertThat(owner.queryForObject("SELECT count(*) FROM wechat_accounts", Integer.class)).isOne();
    }

    @Test
    void bindingStartsOnlyOnTheCallbackHost() throws Exception {
        String token = login(PHONE).path("accessToken").asText();
        assertThat(refused(post("/api/auth/account/wechat/bind").header("Host", "other.example.com"), token, null, 400, 1400))
                .contains("login.example.com");
    }

    private JsonNode login(String phone) throws Exception {
        seedCode(phone, "111111");
        return api(post("/api/auth/login"), null, Map.of("phone", phone, "code", "111111"));
    }

    private void seedCode(String phone, String code) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        owner.update("INSERT INTO sms_codes(phone,code_hash,expires_at) VALUES (?,?,now()+interval '5 minutes')", phone, hash);
    }

    @SuppressWarnings("unchecked")
    private void stubWechat(String tokenBody, String profileBody) throws Exception {
        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn(tokenBody);
        HttpResponse<String> profile = mock(HttpResponse.class);
        when(profile.statusCode()).thenReturn(200);
        when(profile.body()).thenReturn(profileBody);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(tokenResponse, profile);
    }

    private String param(String query, String name) {
        for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            if (pair[0].equals(name)) return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        throw new AssertionError("Missing " + name);
    }

    private JsonNode api(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        var response = mvc.perform(prepared(request, token, body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    /** @return the message the caller is shown */
    private String refused(MockHttpServletRequestBuilder request, String token, Object body, int http, int code) throws Exception {
        var response = mvc.perform(prepared(request, token, body)).andExpect(status().is(http))
                .andExpect(jsonPath("$.code").value(code)).andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("message").asText();
    }

    private MockHttpServletRequestBuilder prepared(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(new HashMap<>((Map<?, ?>) body)));
        return request;
    }
}
