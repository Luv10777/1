package com.wuyao.growth.billing;

import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.tenant.TenantContext;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 兑换码：运营生成与作废、商家兑换入对应余额、一码一用、充值码按实收金额记返利，以及烟火看板的合计。用户编号 1 在计费运营名单里。 */
@SpringBootTest(properties = {"spring.config.import=", "growth.worker.enabled=false", "logging.level.root=WARN",
        "growth.voice.sample-storage=minio", "growth.billing.operator-user-ids=1"})
@AutoConfigureMockMvc
@Testcontainers
class BillingRedeemCodeIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("redeem_code_test").withUsername("growth_owner").withPassword("test_owner_password");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "growth_app"); r.add("spring.datasource.password", () -> "growth_dev_local");
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl); r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword); r.add("spring.flyway.placeholders.app_db_password", () -> "growth_dev_local");
        r.add("growth.jwt.secret", () -> "test-only-random-signing-secret-0123456789abcdef");
    }

    @Autowired BillingRedeemCodeService codes;
    @Autowired BillingLedgerReport report;
    @Autowired BillingService billing;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    JdbcTemplate owner;
    long shop, cafe, operator, shopOwner, shopStaff, cafeOwner;

    @BeforeEach void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants, billing_redeem_codes, tasks RESTART IDENTITY CASCADE");
        shop = owner.queryForObject("INSERT INTO tenants(name) VALUES ('冰雪时光') RETURNING id", Long.class);
        cafe = owner.queryForObject("INSERT INTO tenants(name) VALUES ('一方小馆') RETURNING id", Long.class);
        operator = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?,'ops','运营小王') RETURNING id", Long.class, shop);
        shopOwner = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?,'shop','李老板') RETURNING id", Long.class, shop);
        shopStaff = owner.queryForObject("INSERT INTO users(tenant_id,username,name,role) VALUES (?,'clerk','店员','STAFF') RETURNING id", Long.class, shop);
        cafeOwner = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?,'cafe','陈老板') RETURNING id", Long.class, cafe);
    }

    @Test void giftAndTopUpCodesLandInTheMatchingBalanceAndEachCodeWorksOnce() throws Exception {
        String key = "batch-" + UUID.randomUUID();
        var gifts = codes.create(new BillingDtos.NewRedeemCodes(key, "GIFT", 50, 0, 3, null, "春节活动"), operator);
        assertThat(gifts).hasSize(3).allSatisfy(c -> {
            assertThat(c.code()).hasSize(16).matches("[23456789A-HJ-NP-Z]+");
            assertThat(c.status()).isEqualTo("UNUSED");
        });
        // 重复提交同一批次不再生成新码。
        assertThat(codes.create(new BillingDtos.NewRedeemCodes(key, "GIFT", 50, 0, 3, null, "春节活动"), operator))
                .extracting(BillingDtos.RedeemCodeView::code).containsExactlyElementsOf(gifts.stream().map(BillingDtos.RedeemCodeView::code).toList());
        String topUp = codes.create(new BillingDtos.NewRedeemCodes("batch-" + UUID.randomUUID(), "TOPUP", 200, 0, 1, null, null), operator)
                .getFirst().code();

        // 带横线、小写也能兑换。
        String dashed = gifts.getFirst().code().toLowerCase().replaceAll("(.{4})(?!$)", "$1-");
        redeem(shopOwner, shop, dashed).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("GIFT")).andExpect(jsonPath("$.data.available").value(50));
        redeem(shopOwner, shop, topUp).andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(250));
        assertThat(owner.queryForMap("SELECT gift_available, purchased_available FROM billing_accounts WHERE tenant_id=?", shop))
                .isEqualTo(Map.of("gift_available", 50L, "purchased_available", 200L));
        assertThat(owner.queryForList("SELECT type FROM billing_ledger WHERE tenant_id=? ORDER BY id", String.class, shop))
                .containsExactly("GRANT", "PURCHASE");

        redeem(shopOwner, shop, gifts.getFirst().code()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("你已兑换过这个兑换码"));
        redeem(cafeOwner, cafe, gifts.getFirst().code()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该兑换码已被使用"));
        // 店员也能兑换，烟火进全店共用的余额。
        redeem(shopStaff, shop, gifts.get(1).code()).andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(300));
        redeem(cafeOwner, cafe, "ABCD-EFGH-JKLM-NPQR").andExpect(status().isBadRequest());

        var redeemed = codes.list(0, 20, "REDEEMED", null, null);
        assertThat(redeemed.total()).isEqualTo(3);
        assertThat(redeemed.items()).allSatisfy(c -> assertThat(c.redeemedTenantName()).isEqualTo("冰雪时光"));
        assertThat(codes.list(0, 20, "UNUSED", "GIFT", "春节").total()).isEqualTo(1);
    }

    @Test void topUpCodesWithAPaidAmountQueueAReferralRebateForTheInviter() throws Exception {
        owner.update("INSERT INTO referral_attributions(tenant_id,referrer_user_id,referrer_tenant_id,code) VALUES (?,?,?,'TESTCODE')", cafe, shopOwner, shop);
        var paid = codes.create(new BillingDtos.NewRedeemCodes("batch-" + UUID.randomUUID(), "TOPUP", 1000, 9900, 1, null, null), operator).getFirst();
        String free = codes.create(new BillingDtos.NewRedeemCodes("batch-" + UUID.randomUUID(), "TOPUP", 500, 0, 1, null, null), operator).getFirst().code();
        String gift = codes.create(new BillingDtos.NewRedeemCodes("batch-" + UUID.randomUUID(), "GIFT", 300, 0, 1, null, null), operator).getFirst().code();
        assertThat(paid.amountMinor()).isEqualTo(9900);

        redeem(cafeOwner, cafe, paid.code()).andExpect(status().isOk());
        redeem(cafeOwner, cafe, free).andExpect(status().isOk());
        redeem(cafeOwner, cafe, gift).andExpect(status().isOk());
        // 只有带实收金额的充值码登记返利任务，按实收金额、充值类型。
        assertThat(owner.queryForList("SELECT payload->>'orderId' AS order_id, payload->>'paidMinor' AS paid, payload->>'kind' AS kind "
                + "FROM tasks WHERE type='REFERRAL_COMMISSION'")).singleElement()
                .isEqualTo(Map.of("order_id", "redeem-code-" + paid.id(), "paid", "9900", "kind", "TOPUP"));

        mvc.perform(post("/api/billing/operations/redeem-codes").header("Authorization", "Bearer " + jwt.issueAccessToken(operator, shop, null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"batch-gift-paid\",\"kind\":\"GIFT\",\"credits\":5,\"amountMinor\":100,\"quantity\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test void voidedAndExpiredCodesCannotBeRedeemedAndRedeemedCodesCannotBeVoided() throws Exception {
        var batch = codes.create(new BillingDtos.NewRedeemCodes("batch-" + UUID.randomUUID(), "GIFT", 10, 0, 3, Instant.now().plusSeconds(3600), null), operator);
        codes.voidCode(batch.get(0).id(), operator);
        redeem(cafeOwner, cafe, batch.get(0).code()).andExpect(jsonPath("$.message").value("该兑换码已作废"));

        owner.update("UPDATE billing_redeem_codes SET expires_at=now()-interval '1 minute' WHERE id=?", batch.get(1).id());
        redeem(cafeOwner, cafe, batch.get(1).code()).andExpect(jsonPath("$.message").value("该兑换码已过期"));
        assertThat(codes.list(0, 20, "EXPIRED", null, null).items()).extracting(BillingDtos.RedeemCodeView::id).containsExactly(batch.get(1).id());

        redeem(cafeOwner, cafe, batch.get(2).code()).andExpect(status().isOk());
        String token = jwt.issueAccessToken(operator, shop, null);
        mvc.perform(post("/api/billing/operations/redeem-codes/" + batch.get(2).id() + "/void").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test void overviewSplitsGrantedAndPurchasedCreditsBySourceAndListsEveryMerchant() throws Exception {
        TenantContext.runAs(shop, () -> billing.grantCredits(30, "test-grant", operator));
        String gift = codes.create(new BillingDtos.NewRedeemCodes("batch-" + UUID.randomUUID(), "GIFT", 20, 0, 1, null, null), operator).getFirst().code();
        String topUp = codes.create(new BillingDtos.NewRedeemCodes("batch-" + UUID.randomUUID(), "TOPUP", 100, 0, 1, null, null), operator).getFirst().code();
        redeem(shopOwner, shop, gift).andExpect(status().isOk());
        redeem(cafeOwner, cafe, topUp).andExpect(status().isOk());
        TenantContext.runAs(cafe, () -> {
            String r = billing.reserve("test:" + UUID.randomUUID(), "IMAGE", 9, "图片创作预占");
            billing.capture(r, 6, "test-capture", "图片交付");
            return null;
        });

        var overview = report.overview();
        assertThat(overview.granted()).isEqualTo(50);
        assertThat(overview.purchased()).isEqualTo(100);
        assertThat(overview.consumed()).isEqualTo(6);
        assertThat(overview.available()).isEqualTo(50 + 91);
        assertThat(overview.held()).isEqualTo(3);
        assertThat(overview.grantedBySource()).isEqualTo(Map.of("ADMIN", 30L, "REDEEM_CODE", 20L));
        assertThat(overview.purchasedBySource()).isEqualTo(Map.of("REDEEM_CODE", 100L));
        assertThat(overview.merchants()).extracting(BillingDtos.MerchantCredits::tenantName).containsExactly("一方小馆", "冰雪时光");
        assertThat(overview.merchants().getFirst()).isEqualTo(new BillingDtos.MerchantCredits(cafe, "一方小馆", 0, 91, 3, 100, 0, 6));

        mvc.perform(get("/api/billing/operations/overview").header("Authorization", "Bearer " + jwt.issueAccessToken(cafeOwner, cafe, null)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/billing/operations/redeem-codes").header("Authorization", "Bearer " + jwt.issueAccessToken(cafeOwner, cafe, null)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/billing/operations/redeem-codes").header("Authorization", "Bearer " + jwt.issueAccessToken(operator, shop, null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"batch-http-1\",\"kind\":\"GIFT\",\"credits\":5,\"quantity\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/billing/operations/overview").header("Authorization", "Bearer " + jwt.issueAccessToken(operator, shop, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.granted").value(50));
    }

    private org.springframework.test.web.servlet.ResultActions redeem(long user, long tenant, String code) throws Exception {
        return mvc.perform(post("/api/billing/redeem").header("Authorization", "Bearer " + jwt.issueAccessToken(user, tenant, null))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"));
    }
}
