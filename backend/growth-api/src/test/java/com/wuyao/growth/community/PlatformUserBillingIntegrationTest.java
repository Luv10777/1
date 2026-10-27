package com.wuyao.growth.community;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.billing.BillingProperties;
import com.wuyao.growth.common.security.JwtService;
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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 管理后台在用户管理里开通、关闭会员和赠送烟火：真实 PostgreSQL，含行级安全、账本和审计。 */
@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN", "logging.level.com.wuyao.growth=WARN"})
@AutoConfigureMockMvc
@Testcontainers
class PlatformUserBillingIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform_billing_test").withUsername("growth_owner").withPassword("test_owner_password");

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
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired BillingProperties billingConfig;
    @Autowired PlatformProperties platformConfig;
    JdbcTemplate owner;
    Long merchantTenant, operator, platformOnly, merchant;
    String operatorToken, platformOnlyToken, merchantToken;
    long planId;

    @BeforeEach
    void setUp() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        owner.execute("TRUNCATE billing_plans RESTART IDENTITY CASCADE");
        Long platformTenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('平台') RETURNING id", Long.class);
        merchantTenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('冰雪时光') RETURNING id", Long.class);
        operator = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000001', '运营') RETURNING id", Long.class, platformTenant);
        platformOnly = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000003', '审核员') RETURNING id", Long.class, platformTenant);
        merchant = owner.queryForObject("INSERT INTO users(tenant_id,phone,name,role) VALUES (?, '13800000002', '林知夏', 'OWNER') RETURNING id", Long.class, merchantTenant);
        platformConfig.setOperatorUserIds(List.of(operator, platformOnly));
        billingConfig.setOperatorUserIds(List.of(operator));
        operatorToken = jwt.issueAccessToken(operator, platformTenant, "13800000001");
        platformOnlyToken = jwt.issueAccessToken(platformOnly, platformTenant, "13800000003");
        merchantToken = jwt.issueAccessToken(merchant, merchantTenant, "13800000002");
        var plan = new HashMap<String, Object>(Map.of("code", "FANGZHI", "name", "方志", "description", "日常内容经营", "priceMinor", 29900,
                "durationDays", 30, "giftCredits", 1000, "imageEnabled", true, "videoEnabled", true, "liveEnabled", false, "published", true));
        plan.put("allianceQuotaMinor", 15000);
        planId = call(post("/api/billing/operations/plans"), operatorToken, plan, 200).path("data").path("id").asLong();
    }

    @Test
    void operatorGrantsMembershipOnceExtendsItAndRevokesItKeepingCredits() throws Exception {
        call(post(membership()), merchantToken, grant("grant-key-0001"), 403);
        assertThat(call(post(membership()), platformOnlyToken, grant("grant-key-0001"), 403).path("message").asText()).contains("会员与烟火");
        assertThat(count("billing_ledger")).isZero();

        var view = call(post(membership()), operatorToken, grant("grant-key-0001"), 200).path("data");
        assertThat(view.path("memberActive").asBoolean()).isTrue();
        assertThat(view.path("planName").asText()).isEqualTo("方志");
        assertThat(view.path("creditsAvailable").asLong()).isEqualTo(1000);
        // 网络重试带同一个 requestKey：不重复开通、不重复记审计
        assertThat(call(post(membership()), operatorToken, grant("grant-key-0001"), 200).path("data").path("creditsAvailable").asLong()).isEqualTo(1000);
        assertThat(audits("GRANT_MEMBERSHIP")).isEqualTo(1);

        call(post(membership()), operatorToken, grant("grant-key-0002"), 200);
        var summary = call(get("/api/billing/summary"), merchantToken, null, 200).path("data");
        assertThat(summary.path("memberActive").asBoolean()).isTrue();
        assertThat(summary.path("giftAvailable").asLong()).isEqualTo(2000);
        assertThat(Instant.parse(summary.path("memberUntil").asText())).isCloseTo(Instant.now().plus(Duration.ofDays(60)), within(Duration.ofMinutes(5)));
        assertThat(count("alliance_quota_grants")).isEqualTo(2);
        assertThat(owner.queryForList("SELECT DISTINCT actor_user_id FROM billing_ledger WHERE type = 'GRANT'", Long.class)).containsExactly(operator);

        var revoked = call(post(membership()), operatorToken, Map.of("granted", false, "requestKey", "revoke-key-0001", "note", "商户申请退订会员"), 200).path("data");
        assertThat(revoked.path("memberActive").asBoolean()).isFalse();
        assertThat(revoked.path("creditsAvailable").asLong()).isEqualTo(2000);
        assertThat(call(get("/api/billing/summary"), merchantToken, null, 200).path("data").path("memberActive").asBoolean()).isFalse();
        call(post(membership()), operatorToken, Map.of("granted", false, "requestKey", "revoke-key-0002", "note", "商户申请退订会员"), 409);

        var trail = call(get("/api/platform/tenants/" + merchantTenant + "/audit/USER/" + merchant), operatorToken, null, 200).path("data");
        assertThat(trail.findValuesAsText("action")).containsExactly("REVOKE_MEMBERSHIP", "GRANT_MEMBERSHIP", "GRANT_MEMBERSHIP");
        assertThat(trail.get(1).path("note").asText()).startsWith("「方志」30 天：");
    }

    @Test
    void operatorGrantsCreditsAsGiftWithinTheCapAndARequiredReason() throws Exception {
        assertThat(call(post(credits()), operatorToken, credits(500, "credit-key-0001", "活动奖励赠送"), 200).path("data").path("creditsAvailable").asLong()).isEqualTo(500);
        call(post(credits()), operatorToken, credits(500, "credit-key-0001", "活动奖励赠送"), 200);
        call(post(credits()), operatorToken, credits(100_001, "credit-key-0002", "活动奖励赠送"), 400);
        call(post(credits()), operatorToken, credits(0, "credit-key-0003", "活动奖励赠送"), 400);
        call(post(credits()), operatorToken, credits(10, "credit-key-0004", "太短"), 400);
        call(post(credits()), platformOnlyToken, credits(10, "credit-key-0005", "活动奖励赠送"), 403);

        var summary = call(get("/api/billing/summary"), merchantToken, null, 200).path("data");
        assertThat(summary.path("giftAvailable").asLong()).isEqualTo(500);
        assertThat(summary.path("purchasedAvailable").asLong()).isZero();
        assertThat(owner.queryForObject("SELECT description FROM billing_ledger WHERE type = 'GRANT'", String.class)).isEqualTo("平台赠送烟火");
        assertThat(audits("GRANT_CREDITS")).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT note FROM platform_management_audit WHERE action = 'GRANT_CREDITS'", String.class)).isEqualTo("500 烟火：活动奖励赠送");
    }

    @Test
    void grantNeedsAPublishedPlanAndRefusesAnotherPlanWhileMemberIsActive() throws Exception {
        call(post("/api/billing/operations/plans/" + planId + "/publication"), operatorToken, Map.of("published", false), 200);
        assertThat(call(post(membership()), operatorToken, grant("grant-key-0003"), 400).path("message").asText()).contains("未上架");
        call(post("/api/billing/operations/plans/" + planId + "/publication"), operatorToken, Map.of("published", true), 200);
        call(post(membership()), operatorToken, grant("grant-key-0004"), 200);

        var other = new HashMap<String, Object>(Map.of("code", "YIFANG", "name", "一方", "description", "进阶经营", "priceMinor", 59900,
                "durationDays", 30, "giftCredits", 3000, "imageEnabled", true, "videoEnabled", true, "liveEnabled", true, "published", true));
        other.put("allianceQuotaMinor", 30000);
        long otherPlan = call(post("/api/billing/operations/plans"), operatorToken, other, 200).path("data").path("id").asLong();
        var body = new HashMap<String, Object>(grant("grant-key-0005"));
        body.put("planId", otherPlan);
        assertThat(call(post(membership()), operatorToken, body, 400).path("message").asText()).contains("同一套餐");
        assertThat(audits("GRANT_MEMBERSHIP")).isEqualTo(1);
    }

    private String membership() { return "/api/platform/users/" + merchant + "/membership"; }
    private String credits() { return "/api/platform/users/" + merchant + "/credits"; }
    private Map<String, Object> grant(String key) { return Map.of("granted", true, "planId", planId, "requestKey", key, "note", "线下签约赠送会员"); }
    private Map<String, Object> credits(long amount, String key, String note) { return Map.of("credits", amount, "requestKey", key, "note", note); }
    private long count(String table) { return owner.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private long audits(String action) {
        return owner.queryForObject("SELECT count(*) FROM platform_management_audit WHERE action = ?", Long.class, action);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String token, Object body, int status) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        String content = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(content).isEqualTo(status);
        return json.readTree(content);
    }
}
