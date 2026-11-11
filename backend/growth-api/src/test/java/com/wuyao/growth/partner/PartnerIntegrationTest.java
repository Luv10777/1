package com.wuyao.growth.partner;

import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用户 ID 与服务商：平台建服务商、发烟火、分激活码额度；服务商按用户 ID 添加名下商户、分烟火、在额度内生成激活码；
 * 兑换服务商的激活码归属到该服务商（第一次为准），平台可以改归属。用户编号 1 在计费运营名单里。
 */
@SpringBootTest(properties = {"spring.config.import=", "growth.worker.enabled=false", "logging.level.root=WARN",
        "growth.voice.sample-storage=minio", "growth.billing.operator-user-ids=1"})
@AutoConfigureMockMvc
@Testcontainers
class PartnerIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("partner_test").withUsername("growth_owner").withPassword("test_owner_password");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "growth_app"); r.add("spring.datasource.password", () -> "growth_dev_local");
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl); r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword); r.add("spring.flyway.placeholders.app_db_password", () -> "growth_dev_local");
        r.add("growth.jwt.secret", () -> "test-only-random-signing-secret-0123456789abcdef");
    }

    @Autowired BillingService billing;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    JdbcTemplate owner;
    long ops, agency, shop, cafe, operator, agentA, agentB, shopOwner, cafeOwner;
    String planCode;

    @BeforeEach void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants, billing_redeem_codes, tasks RESTART IDENTITY CASCADE");
        ops = tenant("平台");
        agency = tenant("代理公司");
        shop = tenant("冰雪时光");
        cafe = tenant("一方小馆");
        operator = user(ops, "ops", "运营小王");
        agentA = user(agency, "agent-a", "甲服务商");
        agentB = user(agency, "agent-b", "乙服务商");
        shopOwner = user(shop, "shop", "李老板");
        cafeOwner = user(cafe, "cafe", "陈老板");
        planCode = "MONTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        billing.createPlan(new BillingDtos.NewPlan(planCode, "月卡", "test-only", 50000, 30, 0, true, true, true, true));
    }

    @Test void everyAccountGetsADistinctEightDigitUidShownOnItsProfile() throws Exception {
        List<Long> uids = owner.queryForList("SELECT uid FROM users ORDER BY id", Long.class);
        assertThat(uids).hasSize(5).doesNotHaveDuplicates().allSatisfy(uid -> assertThat(uid).isBetween(10_000_000L, 99_999_999L));
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(shopOwner, shop)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.uid").value(uid(shopOwner)));
    }

    @Test void partnersShareCreditsAndCodesWithTenantsTheyOwnAndTheFirstOwnerWins() throws Exception {
        long a = partner("甲服务商", agentA), b = partner("乙服务商", agentB);
        platform(post("/api/platform/partners/" + a + "/credits"), "{\"requestKey\":\"grant-a-0001\",\"credits\":1000,\"note\":\"线下打款\"}").andExpect(status().isOk());
        platform(post("/api/platform/partners/" + a + "/credits"), "{\"requestKey\":\"grant-a-0001\",\"credits\":1000,\"note\":\"线下打款\"}")
                .andExpect(jsonPath("$.data.partner.creditsAvailable").value(1000));
        platform(put("/api/platform/partners/" + a + "/quotas"), "{\"planCode\":\"" + planCode + "\",\"quota\":2}")
                .andExpect(jsonPath("$.data.quotas[0].remaining").value(2));
        platform(put("/api/platform/partners/" + b + "/quotas"), "{\"planCode\":\"" + planCode + "\",\"quota\":1}").andExpect(status().isOk());

        // 按用户 ID 添加名下商户；别家已经添加的不能再加。
        call(agentA, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(shopOwner) + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tenantName").value("冰雪时光")).andExpect(jsonPath("$.data.source").value("UID"));
        call(agentB, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(shopOwner) + "}").andExpect(status().isConflict());
        call(agentA, agency, post("/api/partner/tenants"), "{\"uid\":99999999}").andExpect(status().isNotFound());

        // 分烟火：记入商户的充值烟火，同一请求只分一次；不在名下的商户不能分，超出余额不能分。
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"transfer-0001\",\"credits\":300}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.creditsAvailable").value(300));
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"transfer-0001\",\"credits\":300}").andExpect(status().isOk());
        assertThat(owner.queryForObject("SELECT purchased_available FROM billing_accounts WHERE tenant_id=?", Long.class, shop)).isEqualTo(300);
        assertThat(owner.queryForObject("SELECT credits_available FROM partners WHERE id=?", Long.class, a)).isEqualTo(700);
        call(agentA, agency, post("/api/partner/tenants/" + cafe + "/credits"), "{\"requestKey\":\"transfer-0002\",\"credits\":10}").andExpect(status().isNotFound());
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"transfer-0003\",\"credits\":800}")
                .andExpect(jsonPath("$.code").value(9003));
        assertThat(owner.queryForObject("SELECT credits_available FROM partners WHERE id=?", Long.class, a)).isEqualTo(700);

        // 额度内生成激活码；同一请求重复提交不重复扣额度，超出额度拒绝。
        String batch = "{\"requestKey\":\"codes-0001\",\"planCode\":\"" + planCode + "\",\"quantity\":2}";
        String first = call(agentA, agency, post("/api/partner/codes"), batch).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2)).andReturn().getResponse().getContentAsString();
        // 重复提交返回同一批码（只比码：第一次返回的时间带纳秒，再读出来是数据库的微秒）。
        call(agentA, agency, post("/api/partner/codes"), batch).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].code").value(codeAt(first, 0))).andExpect(jsonPath("$.data[1].code").value(codeAt(first, 1)));
        call(agentA, agency, post("/api/partner/codes"), "{\"requestKey\":\"codes-0002\",\"planCode\":\"" + planCode + "\",\"quantity\":1}")
                .andExpect(status().isBadRequest());
        assertThat(owner.queryForObject("SELECT used FROM partner_code_quotas WHERE partner_id=?", Integer.class, a)).isEqualTo(2);
        call(agentA, agency, get("/api/partner/codes"), null).andExpect(jsonPath("$.data.total").value(2));
        String codeA = owner.queryForObject("SELECT code FROM billing_redeem_codes WHERE partner_id=? ORDER BY id LIMIT 1", String.class, a);

        // 一方小馆兑换甲的码，归到甲名下；之后再兑换乙的码，会员顺延但归属不变。
        redeem(cafeOwner, cafe, codeA).andExpect(status().isOk()).andExpect(jsonPath("$.data.planName").value("月卡"));
        assertThat(owner.queryForMap("SELECT partner_id, source FROM partner_tenants WHERE tenant_id=?", cafe))
                .containsEntry("partner_id", a).containsEntry("source", "CODE");
        call(agentB, agency, post("/api/partner/codes"), "{\"requestKey\":\"codes-b-0001\",\"planCode\":\"" + planCode + "\",\"quantity\":1}").andExpect(status().isOk());
        String codeB = owner.queryForObject("SELECT code FROM billing_redeem_codes WHERE partner_id=?", String.class, b);
        redeem(cafeOwner, cafe, codeB).andExpect(status().isOk());
        assertThat(owner.queryForObject("SELECT partner_id FROM partner_tenants WHERE tenant_id=?", Long.class, cafe)).isEqualTo(a);
        call(agentA, agency, get("/api/partner/tenants"), null).andExpect(jsonPath("$.data.total").value(2));
        call(agentB, agency, get("/api/partner/tenants"), null).andExpect(jsonPath("$.data.total").value(0));

        // 只有平台能改归属。
        platform(get("/api/platform/partners/lookup?uid=" + uid(cafeOwner)), null).andExpect(jsonPath("$.data.partnerName").value("甲服务商"));
        platform(put("/api/platform/partners/assignments/" + cafe), "{\"partnerId\":" + b + "}").andExpect(status().isOk());
        assertThat(owner.queryForMap("SELECT partner_id, source FROM partner_tenants WHERE tenant_id=?", cafe))
                .containsEntry("partner_id", b).containsEntry("source", "PLATFORM");
        call(agentA, agency, get("/api/partner/ledger"), null).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].type").value("TRANSFER")).andExpect(jsonPath("$.data.items[0].delta").value(-300));
    }

    @Test void onlyPartnerAccountsAndBillingOperatorsReachTheirBackOffices() throws Exception {
        long a = partner("甲服务商", agentA);
        call(shopOwner, shop, get("/api/partner/me"), null).andExpect(status().isForbidden());
        call(agentA, agency, get("/api/partner/me"), null).andExpect(status().isOk()).andExpect(jsonPath("$.data.partner.name").value("甲服务商"));
        call(agentA, agency, get("/api/platform/partners"), null).andExpect(status().isForbidden());
        // 一个账号只能属于一家服务商；停用后服务商后台不能再用。
        platform(post("/api/platform/partners"), "{\"name\":\"丙\",\"adminUid\":" + uid(agentA) + "}").andExpect(status().isConflict());
        platform(post("/api/platform/partners/" + a + "/status"), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        call(agentA, agency, get("/api/partner/me"), null).andExpect(status().isForbidden());
    }

    private long partner(String name, long admin) throws Exception {
        String body = platform(post("/api/platform/partners"), "{\"name\":\"" + name + "\",\"adminUid\":" + uid(admin) + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.members[0].userId").value(admin))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"partner\":\\{\"id\":(\\d+).*", "$1"));
    }

    private ResultActions platform(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(operator, ops, request, body);
    }

    private ResultActions call(long user, long tenant, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", bearer(user, tenant));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private ResultActions redeem(long user, long tenant, String code) throws Exception {
        return call(user, tenant, post("/api/billing/redeem"), "{\"code\":\"" + code + "\"}");
    }

    private String bearer(long user, long tenant) { return "Bearer " + jwt.issueAccessToken(user, tenant, null); }

    private long tenant(String name) { return owner.queryForObject("INSERT INTO tenants(name) VALUES (?) RETURNING id", Long.class, name); }

    private long user(long tenant, String username, String name) {
        return owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?,?,?) RETURNING id", Long.class, tenant, username, name);
    }

    private static String codeAt(String json, int index) {
        var m = java.util.regex.Pattern.compile("\"code\":\"([0-9A-Z]{16})\"").matcher(json);
        for (int i = 0; i <= index; i++) assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private long uid(long user) { return owner.queryForObject("SELECT uid FROM users WHERE id=?", Long.class, user); }
}
