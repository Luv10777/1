package com.wuyao.growth.referral;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.community.IdentityVerifier;
import com.wuyao.growth.community.RealNameCrypto;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
    @Autowired RealNameCrypto crypto;
    @MockitoBean IdentityVerifier verifier;
    JdbcTemplate owner;
    Long operatorTenant, referrerTenant, inviteeTenant, operator, referrer, invitee;
    String operatorToken, referrerToken, inviteeToken;

    @BeforeEach void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        // referral_rates 引用 users，清空商户时会被一起清掉，按 V71、V72 的默认值补回。
        owner.execute("TRUNCATE referral_rates RESTART IDENTITY");
        owner.update("INSERT INTO referral_rates(kind, rate_bp, note) VALUES ('TOPUP', 1000, '上线默认 10%'), ('MEMBERSHIP', 2000, '开通会员默认 20%')");
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
                + "VALUES (?,?,?,?,'张*','110***********002X','VERIFIED')", tenantId, userId,
                crypto.encrypt("张三", tenantId, userId, "name"), crypto.encrypt("11010519491231002X", tenantId, userId, "number"));
    }

    private String takeCode() throws Exception {
        String body = mvc.perform(post("/api/referral/code").header("Authorization", "Bearer " + referrerToken)
                        .contentType("application/json").content("{\"agreementAccepted\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("code").asText();
    }

    private void topup(String orderId, long amountMinor) throws Exception {
        paid(orderId, "TOPUP", amountMinor);
    }

    private void paid(String orderId, String kind, long amountMinor) throws Exception {
        TenantContext.runAs(inviteeTenant, () -> transactions.execute(s -> {
            events.publishEvent(new BillingDtos.OrderPaid(inviteeTenant, orderId, kind, amountMinor));
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
                .andExpect(jsonPath("$.data.topupRateBp").value(1000)).andExpect(jsonPath("$.data.membershipRateBp").value(2000));
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

        // 开通 199 元会员按 20% 返 39.8 元；充值返利不受影响。
        paid("order-member", "MEMBERSHIP", 19_900);
        mvc.perform(get("/api/referral/me").header("Authorization", "Bearer " + referrerToken))
                .andExpect(jsonPath("$.data.commissions[0].kind").value("MEMBERSHIP"))
                .andExpect(jsonPath("$.data.commissions[0].rateBp").value(2000))
                .andExpect(jsonPath("$.data.commissions[0].amountMinor").value(3980))
                .andExpect(jsonPath("$.data.commissions[1].kind").value("TOPUP"));

        // 被邀请商户看不到邀请人的任何数据。
        mvc.perform(get("/api/referral/me").header("Authorization", "Bearer " + inviteeToken))
                .andExpect(jsonPath("$.data.inviteeCount").value(0)).andExpect(jsonPath("$.data.commissions").isEmpty());
    }

    @Test void theRegistrationPageCanCheckATypedCodeWithoutSigningIn() throws Exception {
        verifyRealName(referrerTenant, referrer);
        String code = takeCode();
        mvc.perform(get("/api/public/referral-codes/{code}", code.toLowerCase())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true)).andExpect(jsonPath("$.data.userId").doesNotExist());
        mvc.perform(get("/api/public/referral-codes/{code}", "NOSUCHCD")).andExpect(jsonPath("$.data.valid").value(false));
        mvc.perform(get("/api/public/referral-codes/{code}", "a-b")).andExpect(jsonPath("$.data.valid").value(false));
        owner.update("UPDATE users SET status='DISABLED' WHERE id=?", referrer);
        mvc.perform(get("/api/public/referral-codes/{code}", code)).andExpect(jsonPath("$.data.valid").value(false));
    }

    @Test void operatorsSeeWhoBelongsToWhomEveryRebateAndTheRanking() throws Exception {
        verifyRealName(referrerTenant, referrer);
        String code = takeCode();
        events.publishEvent(new AuthDtos.UserRegistered(inviteeTenant, invitee, "13912345678", code));
        topup("order-a", 10_000);
        paid("order-b", "MEMBERSHIP", 19_900);

        for (String path : List.of("/api/platform/referral/relations", "/api/platform/referral/commissions", "/api/platform/referral/promoters")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + referrerToken)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/platform/referral/relations").header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get("/api/platform/referral/relations?scope=INVITED").header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].tenantName").value("新商户"))
                .andExpect(jsonPath("$.data.items[0].ownerPhoneMasked").value("139****5678"))
                .andExpect(jsonPath("$.data.items[0].referrerName").value("推广人"))
                .andExpect(jsonPath("$.data.items[0].referrerTenantName").value("邀请人商户"))
                .andExpect(jsonPath("$.data.items[0].code").value(code))
                .andExpect(jsonPath("$.data.items[0].topupPaidMinor").value(10_000))
                .andExpect(jsonPath("$.data.items[0].membershipPaidMinor").value(19_900))
                .andExpect(jsonPath("$.data.items[0].commissionMinor").value(1000 + 3980));
        mvc.perform(get("/api/platform/referral/relations?scope=COMPANY").header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].referrerUserId").doesNotExist());
        mvc.perform(get("/api/platform/referral/relations?keyword=13912345678").header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].ownerPhoneMasked").value("139****5678"));

        mvc.perform(get("/api/platform/referral/commissions?referrerUserId=" + referrer).header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.page.total").value(2))
                .andExpect(jsonPath("$.data.page.items[0].kind").value("MEMBERSHIP"))
                .andExpect(jsonPath("$.data.page.items[0].inviteeTenantName").value("新商户"))
                .andExpect(jsonPath("$.data.frozenMinor").value(4980)).andExpect(jsonPath("$.data.availableMinor").value(0));
        mvc.perform(get("/api/platform/referral/commissions?state=AVAILABLE").header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.page.total").value(0));
        mvc.perform(get("/api/platform/referral/commissions?state=BOGUS").header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/platform/referral/promoters").header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("推广人")).andExpect(jsonPath("$.data[0].code").value(code))
                .andExpect(jsonPath("$.data[0].inviteeCount").value(1)).andExpect(jsonPath("$.data[0].payingInviteeCount").value(1))
                .andExpect(jsonPath("$.data[0].commissionMinor").value(4980)).andExpect(jsonPath("$.data[0].frozenMinor").value(4980));
    }

    @Test void unknownCodeOrNoCodeLeavesTheMerchantWithTheCompany() throws Exception {
        events.publishEvent(new AuthDtos.UserRegistered(inviteeTenant, invitee, "13912345678", "NOSUCHCD"));
        events.publishEvent(new AuthDtos.UserRegistered(inviteeTenant, invitee, "13912345678", null));
        assertThat(owner.queryForObject("SELECT count(*) FROM referral_attributions", Long.class)).isZero();
        TenantContext.runAs(inviteeTenant, () -> transactions.execute(s -> {
            events.publishEvent(new BillingDtos.OrderPaid(inviteeTenant, "order-company", "TOPUP", 10_000));
            return null;
        }));
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks WHERE type=?", Long.class, ReferralCommissionHandler.TYPE)).isZero();
    }

    @Test void onlyOperatorsChangeTheRateAndLaterTopupsUseIt() throws Exception {
        verifyRealName(referrerTenant, referrer);
        String code = takeCode();
        events.publishEvent(new AuthDtos.UserRegistered(inviteeTenant, invitee, "13912345678", code));
        topup("order-old", 10_000);

        String change = "{\"kind\":\"TOPUP\",\"rateBp\":1500,\"note\":\"活动期间上调返利\"}";
        mvc.perform(put("/api/platform/referral/rate").header("Authorization", "Bearer " + referrerToken)
                .contentType("application/json").content(change)).andExpect(status().isForbidden());
        mvc.perform(put("/api/platform/referral/rate").header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content("{\"kind\":\"TOPUP\",\"rateBp\":9000,\"note\":\"超过上限\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/platform/referral/rate").header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content("{\"kind\":\"OTHER\",\"rateBp\":100,\"note\":\"未知的返利类型\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/platform/referral/rate").header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content(change)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topupRateBp").value(1500)).andExpect(jsonPath("$.data.membershipRateBp").value(2000))
                .andExpect(jsonPath("$.data.history[0].kind").value("TOPUP")).andExpect(jsonPath("$.data.history[0].note").value("活动期间上调返利"));

        topup("order-new", 10_000);
        assertThat(owner.queryForList("SELECT amount_minor FROM referral_commissions ORDER BY id", Long.class)).containsExactly(1000L, 1500L);
    }

    private org.springframework.test.web.servlet.ResultActions bindCard(String cardNumber) throws Exception {
        return mvc.perform(put("/api/identity/bank-card").header("Authorization", "Bearer " + referrerToken).contentType("application/json")
                .content("{\"bankName\":\"中国工商银行\",\"cardNumber\":\"" + cardNumber + "\",\"mobile\":\"13800000002\",\"declarationAccepted\":true}"));
    }

    private org.springframework.test.web.servlet.ResultActions withdraw() throws Exception {
        return mvc.perform(post("/api/referral/withdrawals").header("Authorization", "Bearer " + referrerToken)
                .contentType("application/json").content("{\"agreementAccepted\":true}"));
    }

    private org.springframework.test.web.servlet.ResultActions operatorPost(String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + operatorToken).contentType("application/json").content(body));
    }

    private Long commissionOf(String orderId) {
        return owner.queryForObject("SELECT id FROM referral_commissions WHERE order_id=?", Long.class, orderId);
    }

    private void unfreeze() {
        owner.update("UPDATE referral_commissions SET available_at = now() - interval '1 minute' WHERE available_at > now()");
    }

    @Test void theBankCardMustBeTheVerifiedPersonsOwnAndMismatchesAreCapped() throws Exception {
        when(verifier.verifyBankCard(any(), any(), any(), any())).thenReturn(new IdentityVerifier.Result(IdentityVerifier.Outcome.MATCH, "REQ-1"));
        bindCard("6222 0000 0000 1234").andExpect(status().isForbidden());
        verifyRealName(referrerTenant, referrer);
        bindCard("6222 0000 0000 1234").andExpect(status().isOk()).andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.card.cardLast4").value("1234")).andExpect(jsonPath("$.data.card.bankName").value("中国工商银行"));
        // 核验用的是实名里的姓名和身份证号，卡号去掉空格；卡号只存密文。
        org.mockito.Mockito.verify(verifier).verifyBankCard(eq("张三"), eq("11010519491231002X"), eq("6222000000001234"), eq("13800000002"));
        byte[] stored = owner.queryForObject("SELECT card_number_ciphertext FROM bank_cards WHERE user_id=?", byte[].class, referrer);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("6222000000001234");

        when(verifier.verifyBankCard(any(), any(), any(), any())).thenReturn(new IdentityVerifier.Result(IdentityVerifier.Outcome.MISMATCH, "REQ-2"));
        for (int i = 0; i < 3; i++) {
            bindCard("6222000000005678").andExpect(status().isOk()).andExpect(jsonPath("$.data.matched").value(false))
                    .andExpect(jsonPath("$.data.card.cardLast4").value("1234"));
        }
        bindCard("6222000000005678").andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/referral/me").header("Authorization", "Bearer " + referrerToken))
                .andExpect(jsonPath("$.data.bankCard.cardLast4").value("1234")).andExpect(jsonPath("$.data.withdrawMinimumMinor").value(10_000));

        when(verifier.verifyBankCard(any(), any(), any(), any())).thenReturn(new IdentityVerifier.Result(IdentityVerifier.Outcome.UNAVAILABLE, null));
        owner.update("DELETE FROM platform_management_audit WHERE target_type='BANK_CARD'");
        bindCard("6222000000005678").andExpect(status().isServiceUnavailable());
    }

    @Test void promotersWithdrawEverythingAvailableAndOperatorsReviewPayAndClawBack() throws Exception {
        verifyRealName(referrerTenant, referrer);
        String code = takeCode();
        events.publishEvent(new AuthDtos.UserRegistered(inviteeTenant, invitee, "13912345678", code));
        topup("order-1", 50_000);
        topup("order-2", 60_000);
        unfreeze();
        topup("order-frozen", 10_000);

        withdraw().andExpect(status().isConflict());
        when(verifier.verifyBankCard(any(), any(), any(), any())).thenReturn(new IdentityVerifier.Result(IdentityVerifier.Outcome.MATCH, "REQ-1"));
        bindCard("6222000000001234").andExpect(status().isOk());

        mvc.perform(post("/api/referral/withdrawals").header("Authorization", "Bearer " + referrerToken)
                .contentType("application/json").content("{\"agreementAccepted\":false}")).andExpect(status().isBadRequest());
        // 可提现 50 + 60 元全部带走，冻结中的不动；提现单记下同意的协议版本。
        String first = withdraw().andExpect(status().isOk()).andExpect(jsonPath("$.data.amountMinor").value(11_000))
                .andExpect(jsonPath("$.data.status").value("PENDING")).andExpect(jsonPath("$.data.cardLast4").value("1234"))
                .andReturn().getResponse().getContentAsString();
        long firstId = json.readTree(first).path("data").path("id").asLong();
        assertThat(owner.queryForObject("SELECT agreement_version FROM referral_withdrawals WHERE id=?", String.class, firstId))
                .isEqualTo(ReferralService.AGREEMENT_VERSION);
        withdraw().andExpect(status().isConflict());
        mvc.perform(get("/api/referral/me").header("Authorization", "Bearer " + referrerToken))
                .andExpect(jsonPath("$.data.availableMinor").value(0)).andExpect(jsonPath("$.data.frozenMinor").value(1000))
                .andExpect(jsonPath("$.data.withdrawals[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.commissions[1].state").value("WITHDRAWING"));

        String base = "/api/platform/referral/withdrawals/" + referrerTenant + "/" + firstId;
        mvc.perform(get("/api/platform/referral/withdrawals").header("Authorization", "Bearer " + referrerToken)).andExpect(status().isForbidden());
        mvc.perform(post(base + "/reject").header("Authorization", "Bearer " + referrerToken).contentType("application/json")
                .content("{\"note\":\"自己驳回\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/referral/withdrawals?status=PENDING").header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.page.total").value(1)).andExpect(jsonPath("$.data.pendingMinor").value(11_000))
                .andExpect(jsonPath("$.data.page.items[0].userName").value("推广人"))
                .andExpect(jsonPath("$.data.page.items[0].tenantId").value(referrerTenant));

        // 待审核单里的返利不能直接作废。
        operatorPost("/api/platform/referral/commissions/" + referrerTenant + "/" + commissionOf("order-1") + "/void",
                "{\"note\":\"被邀请商户退款\"}").andExpect(status().isConflict());

        operatorPost(base + "/reveal", "{\"purpose\":\"网银转账核对收款信息\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.holderName").value("张三")).andExpect(jsonPath("$.data.cardNumber").value("6222000000001234"));
        assertThat(owner.queryForObject("SELECT count(*) FROM platform_management_audit WHERE target_type='REFERRAL_WITHDRAWAL' AND action='REVEAL'",
                Long.class)).isEqualTo(1);

        // 驳回后返利回到可提现。
        operatorPost(base + "/reject", "{\"note\":\"收款卡信息需要更新\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        operatorPost(base + "/reject", "{\"note\":\"重复驳回\"}").andExpect(status().isConflict());
        mvc.perform(get("/api/referral/me").header("Authorization", "Bearer " + referrerToken))
                .andExpect(jsonPath("$.data.availableMinor").value(11_000)).andExpect(jsonPath("$.data.withdrawals[0].reviewNote").value("收款卡信息需要更新"));

        String second = withdraw().andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String paid = "/api/platform/referral/withdrawals/" + referrerTenant + "/" + json.readTree(second).path("data").path("id").asLong() + "/paid";
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        operatorPost(paid, "{\"taxMinor\":20000,\"reference\":\"TX-0001\",\"paidDate\":\"" + today + "\"}").andExpect(status().isBadRequest());
        operatorPost(paid, "{\"taxMinor\":1000,\"reference\":\"TX-0001\",\"paidDate\":\"" + LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2) + "\"}")
                .andExpect(status().isBadRequest());
        operatorPost(paid, "{\"taxMinor\":1000,\"reference\":\"TX-0001\",\"paidDate\":\"" + today + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID")).andExpect(jsonPath("$.data.taxMinor").value(1000))
                .andExpect(jsonPath("$.data.netMinor").value(10_000));

        // 已打款的返利作废后变成欠款；冻结中的直接作废，不算欠款。
        operatorPost("/api/platform/referral/commissions/" + referrerTenant + "/" + commissionOf("order-1") + "/void",
                "{\"note\":\"被邀请商户退款\"}").andExpect(status().isOk());
        operatorPost("/api/platform/referral/commissions/" + referrerTenant + "/" + commissionOf("order-1") + "/void",
                "{\"note\":\"被邀请商户退款\"}").andExpect(status().isConflict());
        operatorPost("/api/platform/referral/commissions/" + referrerTenant + "/" + commissionOf("order-frozen") + "/void",
                "{\"note\":\"冻结期内退款\"}").andExpect(status().isOk());
        mvc.perform(get("/api/referral/me").header("Authorization", "Bearer " + referrerToken))
                .andExpect(jsonPath("$.data.owedMinor").value(5000)).andExpect(jsonPath("$.data.frozenMinor").value(0))
                .andExpect(jsonPath("$.data.availableMinor").value(0));
        mvc.perform(get("/api/platform/referral/commissions?state=WITHDRAWN").header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.page.total").value(1));

        // 新返利 120 元，扣回 50 元后 70 元不满 100 元不能提；再来 50 元，提走 170 - 50 = 120 元。
        topup("order-3", 120_000);
        unfreeze();
        withdraw().andExpect(status().isConflict());
        topup("order-4", 50_000);
        unfreeze();
        withdraw().andExpect(status().isOk()).andExpect(jsonPath("$.data.commissionMinor").value(17_000))
                .andExpect(jsonPath("$.data.clawbackMinor").value(5000)).andExpect(jsonPath("$.data.amountMinor").value(12_000));
        mvc.perform(get("/api/referral/me").header("Authorization", "Bearer " + referrerToken))
                .andExpect(jsonPath("$.data.owedMinor").value(0)).andExpect(jsonPath("$.data.availableMinor").value(0));
    }
}
