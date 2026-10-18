package com.wuyao.growth.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.crypto.bcrypt.BCrypt.gensalt;
import static org.springframework.security.crypto.bcrypt.BCrypt.hashpw;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 系统设置里的个人账户：看自己的资料、改名、设置和修改密码。
 * 全部经真实的 HTTP 接口、登录流程和数据库。
 */
@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN",
        // 本机 .env 不能把这组测试指向真实的模型或存储桶。
        "growth.ai.writer.url=", "growth.voice.sample-storage=minio"})
@AutoConfigureMockMvc
@Testcontainers
class AccountSettingsIntegrationTest {
    private static final String PHONE = "13900000011";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("account_test").withUsername("growth_owner").withPassword("test_owner_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "growth_app");
        registry.add("spring.datasource.password", () -> "growth_dev_local");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.app_db_password", () -> "growth_dev_local");
        registry.add("growth.jwt.secret", () -> "test-only-random-signing-secret-0123456789abcdef");
    }

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    JdbcTemplate owner;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE sms_codes, tenants RESTART IDENTITY CASCADE");
    }

    @Test
    void aPersonSeesTheirOwnProfileAndRenamesThemselves() throws Exception {
        String token = login(PHONE).path("accessToken").asText();

        JsonNode profile = api(get("/api/auth/account"), token, null);
        assertThat(profile.path("phone").asText()).isEqualTo(PHONE);
        assertThat(profile.path("name").asText()).isEqualTo("用户0011");
        assertThat(profile.path("role").asText()).isEqualTo("OWNER");
        assertThat(profile.path("passwordSet").asBoolean()).isFalse();
        assertThat(profile.has("passwordHash")).isFalse();

        assertThat(api(put("/api/auth/account/name"), token, Map.of("name", "  王老板 ")).path("name").asText())
                .isEqualTo("王老板");
        assertThat(api(get("/api/auth/me"), token, null).path("name").asText()).isEqualTo("王老板");
        refused(put("/api/auth/account/name"), token, Map.of("name", "   "), 400, 1400);
        refused(put("/api/auth/account/name"), token, Map.of("name", "名".repeat(81)), 400, 1400);
        refused(get("/api/auth/account"), null, null, 401, 1401);
    }

    @Test
    void theFirstPasswordNeedsTheCodeSentToTheirPhoneAndSignsOutOtherDevices() throws Exception {
        JsonNode laptop = login(PHONE);
        JsonNode phone = login(PHONE);
        String token = laptop.path("accessToken").asText();

        // 只拿到登录态、收不到短信的人不能给账号加密码。
        assertThat(refused(put("/api/auth/account/password"), token, Map.of("newPassword", "new-secret-1"), 400, 1400))
                .contains("短信验证码");
        seedCode(PHONE, "222222");
        refused(put("/api/auth/account/password"), token, Map.of("code", "333333", "newPassword", "new-secret-1"), 401, 2003);
        refused(put("/api/auth/account/password"), token, Map.of("code", "222222", "newPassword", "short"), 400, 1400);

        JsonNode renewed = api(put("/api/auth/account/password"), token, Map.of("code", "222222", "newPassword", "new-secret-1"));
        String fresh = renewed.path("accessToken").asText();
        assertThat(api(get("/api/auth/account"), fresh, null).path("passwordSet").asBoolean()).isTrue();

        // 改密码前签发的令牌全部作废，包括另一台设备上的。
        refused(get("/api/auth/account"), token, null, 401, 1401);
        refused(get("/api/auth/account"), phone.path("accessToken").asText(), null, 401, 1401);
        refused(post("/api/auth/refresh"), null, Map.of("refreshToken", phone.path("refreshToken").asText()), 401, 2005);
        api(post("/api/auth/refresh"), null, Map.of("refreshToken", renewed.path("refreshToken").asText()));

        // 验证码用过一次就不能再用。
        refused(put("/api/auth/account/password"), fresh, Map.of("code", "222222", "newPassword", "new-secret-2"), 401, 2003);
        api(post("/api/auth/password-login"), null, Map.of("account", PHONE, "password", "new-secret-1"));
    }

    @Test
    void changingAPasswordChecksTheCurrentOneAndFallsBackToTheCodeWhenLockedOut() throws Exception {
        String token = login(PHONE).path("accessToken").asText();
        owner.update("UPDATE users SET password_hash = ? WHERE phone = ?", hashpw("old-secret-1", gensalt(4)), PHONE);

        refused(put("/api/auth/account/password"), token, Map.of("newPassword", "new-secret-1"), 400, 1400);
        for (int i = 0; i < 5; i++) {
            assertThat(refused(put("/api/auth/account/password"), token,
                    Map.of("currentPassword", "wrong-guess", "newPassword", "new-secret-1"), 401, 2009))
                    .contains("当前密码不正确");
        }
        // 连错五次就锁住，猜对了也不放行；这时只能改用短信验证码。
        refused(put("/api/auth/account/password"), token,
                Map.of("currentPassword", "old-secret-1", "newPassword", "new-secret-1"), 401, 2009);
        refused(post("/api/auth/password-login"), null, Map.of("account", PHONE, "password", "old-secret-1"), 401, 2009);

        seedCode(PHONE, "444444");
        String fresh = api(put("/api/auth/account/password"), token, Map.of("code", "444444", "newPassword", "new-secret-1"))
                .path("accessToken").asText();
        assertThat(owner.queryForObject("SELECT password_failed_attempts FROM users WHERE phone = ?", Integer.class, PHONE))
                .isZero();

        String later = api(put("/api/auth/account/password"), fresh,
                Map.of("currentPassword", "new-secret-1", "newPassword", "newer-secret-2")).path("accessToken").asText();
        api(post("/api/auth/password-login"), null, Map.of("account", PHONE, "password", "newer-secret-2"));
        refused(post("/api/auth/password-login"), null, Map.of("account", PHONE, "password", "new-secret-1"), 401, 2009);
        api(get("/api/auth/account"), later, null);
    }

    @Test
    void anAccountWithoutAPhoneCanOnlyUseItsCurrentPassword() throws Exception {
        Long tenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('丙商户') RETURNING id", Long.class);
        owner.update("INSERT INTO users(tenant_id, username, name, password_hash) VALUES (?, 'shop-admin', '店长', ?)",
                tenant, hashpw("old-secret-1", gensalt(4)));
        String token = api(post("/api/auth/password-login"), null,
                Map.of("account", "shop-admin", "password", "old-secret-1")).path("accessToken").asText();

        JsonNode profile = api(get("/api/auth/account"), token, null);
        assertThat(profile.path("username").asText()).isEqualTo("shop-admin");
        assertThat(profile.path("phone").isMissingNode() || profile.path("phone").isNull()).isTrue();
        assertThat(profile.path("passwordSet").asBoolean()).isTrue();

        assertThat(refused(put("/api/auth/account/password"), token, Map.of("code", "555555", "newPassword", "new-secret-1"), 400, 1400))
                .contains("没有绑定手机号");
        api(put("/api/auth/account/password"), token, Map.of("currentPassword", "old-secret-1", "newPassword", "new-secret-1"));
        api(post("/api/auth/password-login"), null, Map.of("account", "shop-admin", "password", "new-secret-1"));
    }

    /** Signs in the way a person does: the code they were sent, then the login request. */
    private JsonNode login(String phone) throws Exception {
        seedCode(phone, "111111");
        return api(post("/api/auth/login"), null, Map.of("phone", phone, "code", "111111"));
    }

    private void seedCode(String phone, String code) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        owner.update("INSERT INTO sms_codes(phone,code_hash,expires_at) VALUES (?,?,now()+interval '5 minutes')", phone, hash);
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
