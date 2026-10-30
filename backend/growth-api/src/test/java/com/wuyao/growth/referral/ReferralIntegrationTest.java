package com.wuyao.growth.referral;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.dto.AuthDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN", "logging.level.com.wuyao.growth=WARN"})
@AutoConfigureMockMvc
@Testcontainers
class ReferralIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("referral_test").withUsername("growth_owner").withPassword("test_owner_password");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "growth_app");
        r.add("spring.datasource.password", () -> "growth_dev_local");
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("spring.flyway.placeholders.app_db_password", () -> "growth_dev_local");
        r.add("growth.jwt.secret", () -> "test-only-random-signing-secret-0123456789abcdef");
        r.add("growth.identity.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("growth.platform.operator-user-ids", () -> "1");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate transactions;
    @Autowired ReferralCommissionHandler handler;
    JdbcTemplate owner;
    Long operatorTenant, referrerTenant, inviteeTenant, operator, referrer, invitee;
    String operatorToken, referrerToken, inviteeToken;

    @BeforeEach void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        // referral_rates 引用 users，清空商户时会被一起清掉，按 V71 的默认值补回。
        owner.execute("TRUNCATE referral_rates RESTART IDENTITY");
        owner.update("INSERT INTO referral_rates(rate_bp, note) VALUES (1000, '上线默认 10%')");
        operatorTenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('平台') RETURNING id", Long.class);
        referrerTenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('邀请人商户') RETURNING id", Long.class);
        inviteeTenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('新商户') RETURNING id", Long.class);
        operator = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000001', '平台人员') RETURNING id", Long.class, operatorTenant);
        referrer = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000002', '推广人') RETURNING id", Long.class, referrerTenant);
        invitee = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13912345678', '新用户') RETURNING id", Long.class, inviteeTenant);
        operatorToken = jwt.issueAccessToken(operator, operatorTenant, "13800000001");
        referrerToken = jwt.issueAccessToken(referrer, referrerTenant, "13800000002");
        inviteeToken = jwt.issueAccessToken(invitee, inviteeTenant, "13912345678");
    }

    private void verifyRealName(Long tenantId, Long userId) {
        owner.update("INSERT INTO real_name_applications(tenant_id,user_id,legal_name_ciphertext,id_number_ciphertext,legal_name_masked,id_number_masked,status) "
                + "VALUES (?,?,?,?,'张*','110***********002X','VERIFIED')", tenantId, userId, new byte[]{1}, new byte[]{1});
    }

    private String takeCode() throws Exception {
        String body = mvc.perform(post("/api/referral/code").header("Authorization", "Bearer " + referrerToken)
                        .contentType("application/json").content("{\"agreementAccepted\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("code").asText();
    }

    private void topup(String orderId, long amountMinor) throws Exception {
        TenantContext.runAs(inviteeTenant, () -> transactions.execute(s -> {
            events.publishEvent(new BillingDtos.TopupPaid(inviteeTenant, orderId, amountMinor));
            return null;
        }));
        var row = owner.queryForMap("SELECT id, tenant_id, payload::text AS payload FROM tasks WHERE type=? AND idempotency_key=?",
                ReferralCommissionHandler.TYPE, "referral-commission:" + orderId);
        var task = new Task();
        task.setId((Long) row.get("id"));
        task.setTenantId((Long) row.get("tenant_id"));
        task.setPayload(json.readValue((String) row.get("payload"), new TypeReference<Map<String, Object>>() {}));
        assertThat(TenantContext.runAs(task.getTenantId(), () -> handler.handle(task))).containsEntry("status", "CREATED");
        assertThat(TenantContext.runAs(task.getTenantId(), () -> handler.handle(task))).containsEntry("status", "EXISTS");
    }

    @Test void codeNeedsRealNameAndTheAgreement() throws Exception {
        mvc.perform(post("/api/referral/code").header("Authorization", "Bearer " + referrerToken)
                .contentType("application/json").content("{\"agreementAccepted\":true}")).andExpect(status().isForbidden());
        verifyRealName(referrerTenant, referrer);
        mvc.perform(post("/api/referral/code").header("Authorization", "Bearer " + referrerToken)
                .contentType("application/json").content("{\"agreementAccepted\":false}")).andExpect(status().isBadRequest());
        String code = takeCode();
        assertThat(code).matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}");
        assertThat(takeCode()).isEqualTo(code);
        mvc.perform(get("/api/referral/me").header("Authorization", "Bearer " + referrerToken))
                .andExpect(jsonPath("$.data.realNameVerified").value(true)).andExpect(jsonPath("$.data.code").value(code))
                .andExpect(jsonPath("$.data.rateBp").value(1000));
    }

    @Test void inviteLinkBindsTheNewMerchantAndTopupsEarnFrozenCommission() throws Exception {
        verifyRealName(referrerTenant, referrer);
        String code = takeCode();
        events.publishEvent(new AuthDtos.UserRegistered(inviteeTenant, invitee, "13912345678", code.toLowerCase()));
        // 同一个邀请码再来一次不会多写。
        events.publishEvent(new AuthDtos.UserRegistered(inviteeTenant, invitee, "13912345678", code));
        assertThat(owner.queryForObject("SELECT referrer_user_id FROM referral_attributions WHERE tenant_id=?", Long.class, inviteeTenant)).isEqualTo(referrer);
        assertThat(owner.queryForObject("SELECT count(*) FROM referral_invitees", Long.class)).isEqualTo(1);

        topup("order-1", 10_000);
        mvc.perform(get("/api/referral/me").header("Authorization", "Bearer " + referrerToken))
                .andExpect(jsonPath("$.data.inviteeCount").value(1))
                .andExpect(jsonPath("$.data.invitees[0].phoneMasked").value("139****5678"))
                .andExpect(jsonPath("$.data.frozenMinor").value(1000)).andExpect(jsonPath("$.data.availableMinor").value(0))
                .andExpect(jsonPath("$.data.commissions[0].state").value("FROZEN"))
                .andExpect(jsonPath("$.data.commissions[0].inviteePhoneMasked").value("139****5678"));

        // 过了冻结期就是可提现。
        owner.update("UPDATE referral_commissions SET available_at = now() - interval '1 minute'");
        mvc.perform(get("/api/referral/me").header("Authorization", "Bearer " + referrerToken))
                .andExpect(jsonPath("$.data.frozenMinor").value(0)).andExpect(jsonPath("$.data.availableMinor").value(1000))
                .andExpect(jsonPath("$.data.commissions[0].state").value("AVAILABLE"));

        // 被邀请商户看不到邀请人的任何数据。
        mvc.perform(get("/api/referral/me").header("Authorization", "Bearer " + inviteeToken))
                .andExpect(jsonPath("$.data.inviteeCount").value(0)).andExpect(jsonPath("$.data.commissions").isEmpty());
    }

    @Test void unknownCodeOrNoCodeLeavesTheMerchantWithTheCompany() throws Exception {
        events.publishEvent(new AuthDtos.UserRegistered(inviteeTenant, invitee, "13912345678", "NOSUCHCD"));
        events.publishEvent(new AuthDtos.UserRegistered(inviteeTenant, invitee, "13912345678", null));
        assertThat(owner.queryForObject("SELECT count(*) FROM referral_attributions", Long.class)).isZero();
        TenantContext.runAs(inviteeTenant, () -> transactions.execute(s -> {
            events.publishEvent(new BillingDtos.TopupPaid(inviteeTenant, "order-company", 10_000));
            return null;
        }));
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks WHERE type=?", Long.class, ReferralCommissionHandler.TYPE)).isZero();
    }

    @Test void onlyOperatorsChangeTheRateAndLaterTopupsUseIt() throws Exception {
        verifyRealName(referrerTenant, referrer);
        String code = takeCode();
        events.publishEvent(new AuthDtos.UserRegistered(inviteeTenant, invitee, "13912345678", code));
        topup("order-old", 10_000);

        String change = "{\"rateBp\":1500,\"note\":\"活动期间上调返利\"}";
        mvc.perform(put("/api/platform/referral/rate").header("Authorization", "Bearer " + referrerToken)
                .contentType("application/json").content(change)).andExpect(status().isForbidden());
        mvc.perform(put("/api/platform/referral/rate").header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content("{\"rateBp\":9000,\"note\":\"超过上限\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/platform/referral/rate").header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content(change)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rateBp").value(1500)).andExpect(jsonPath("$.data.history[0].note").value("活动期间上调返利"));

        topup("order-new", 10_000);
        assertThat(owner.queryForList("SELECT amount_minor FROM referral_commissions ORDER BY id", Long.class)).containsExactly(1000L, 1500L);
    }
}
