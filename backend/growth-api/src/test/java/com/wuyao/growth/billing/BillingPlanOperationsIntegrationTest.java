package com.wuyao.growth.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN", "logging.level.com.wuyao.growth=WARN"})
@AutoConfigureMockMvc
@Testcontainers
class BillingPlanOperationsIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("plans_test").withUsername("growth_owner").withPassword("test_owner_password");

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
    String operatorToken;
    String merchantToken;

    @BeforeEach
    void setUp() {
        var owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        owner.execute("TRUNCATE billing_plans RESTART IDENTITY CASCADE");
        Long tenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('平台') RETURNING id", Long.class);
        Long operator = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000001', '运营') RETURNING id", Long.class, tenant);
        Long merchant = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000002', '商户') RETURNING id", Long.class, tenant);
        billingConfig.setOperatorUserIds(List.of(operator));
        operatorToken = jwt.issueAccessToken(operator, tenant, "13800000001");
        merchantToken = jwt.issueAccessToken(merchant, tenant, "13800000002");
    }

    private Map<String, Object> plan(long price, boolean published, boolean replace) {
        var body = new HashMap<String, Object>(Map.of("code", "FANGZHI", "name", "方志", "description", "日常内容经营", "priceMinor", price,
                "durationDays", 30, "giftCredits", 1000, "imageEnabled", true, "videoEnabled", true, "liveEnabled", false, "published", published));
        body.put("allianceQuotaMinor", 15000);
        body.put("replacePublished", replace);
        return body;
    }

    @Test
    void aNewVersionCanReplaceThePublishedOneWithoutAGap() throws Exception {
        long v1 = call(post("/api/billing/operations/plans"), operatorToken, plan(29900, true, false), 200).path("data").path("id").asLong();
        assertThat(call(post("/api/billing/operations/plans"), operatorToken, plan(32900, true, false), 400).path("message").asText()).contains("替换上架");
        long v2 = call(post("/api/billing/operations/plans"), operatorToken, plan(32900, true, true), 200).path("data").path("id").asLong();

        var listings = call(get("/api/billing/operations/plans"), operatorToken, null, 200).path("data");
        assertThat(listings.size()).isEqualTo(2);
        listings.forEach(item -> assertThat(item.path("createdAt").isTextual()).isTrue());
        assertThat(published(listings)).containsExactly(v2);
        assertThat(call(get("/api/billing/plans"), merchantToken, null, 200).path("data").get(0).path("priceMinor").asLong()).isEqualTo(32900);

        // 切回旧版本：一次替换，不用先下架
        call(post("/api/billing/operations/plans/" + v1 + "/publication"), operatorToken, Map.of("published", true, "replacePublished", true), 200);
        assertThat(published(call(get("/api/billing/operations/plans"), operatorToken, null, 200).path("data"))).containsExactly(v1);
        call(post("/api/billing/operations/plans"), merchantToken, plan(1, false, false), 403);
    }

    private static List<Long> published(JsonNode listings) {
        var ids = new java.util.ArrayList<Long>();
        listings.forEach(item -> { if (item.path("published").asBoolean()) ids.add(item.path("plan").path("id").asLong()); });
        return ids;
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
