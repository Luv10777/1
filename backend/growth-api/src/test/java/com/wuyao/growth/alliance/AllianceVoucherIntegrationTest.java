package com.wuyao.growth.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.billing.BillingProperties;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.tenant.TenantContext;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN", "growth.platform.operator-user-ids=900"})
@AutoConfigureMockMvc
@Testcontainers
class AllianceVoucherIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("alliance_test").withUsername("growth_owner").withPassword("test_owner_password");

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

    private static final String SUPPLIER_CODE = "91330400MA2B000001";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired BillingService billing;
    @Autowired BillingProperties billingConfig;
    @Autowired AllianceSyncHandler sync;

    JdbcTemplate owner;
    Long supplier, holder, sameSubject, rival, operatorTenant;
    String supplierToken, staffToken, holderToken, sameSubjectToken, rivalToken, operatorToken;
    Long store, otherStore, product;
    final Set<Long> handledTasks = new HashSet<>();

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        owner.execute("TRUNCATE billing_plans RESTART IDENTITY CASCADE");
        handledTasks.clear();

        operatorTenant = tenant("平台");
        owner.update("INSERT INTO users(id,tenant_id,phone,name) VALUES (900,?,'13800000900','平台运营')", operatorTenant);
        operatorToken = jwt.issueAccessToken(900L, operatorTenant, "13800000900");

        supplier = tenant("乙家茶铺");
        Long supplierOwner = user(supplier, "13800000001", "OWNER");
        Long staff = user(supplier, "13800000002", "STAFF");
        holder = tenant("甲家花店");
        Long holderOwner = user(holder, "13800000003", "OWNER");
        sameSubject = tenant("乙家分号");
        Long sameSubjectOwner = user(sameSubject, "13800000004", "OWNER");
        rival = tenant("丙家书店");
        Long rivalOwner = user(rival, "13800000005", "OWNER");
        supplierToken = jwt.issueAccessToken(supplierOwner, supplier, "13800000001");
        staffToken = jwt.issueAccessToken(staff, supplier, "13800000002");
        holderToken = jwt.issueAccessToken(holderOwner, holder, "13800000003");
        sameSubjectToken = jwt.issueAccessToken(sameSubjectOwner, sameSubject, "13800000004");
        rivalToken = jwt.issueAccessToken(rivalOwner, rival, "13800000005");

        store = owner.queryForObject("""
                INSERT INTO stores(tenant_id,name,created_by,address,phone,business_hours)
                VALUES (?, '乙家茶铺·南湖店', ?, '浙江省嘉兴市南湖区中山路 1 号', '0573-1234567', '09:00-21:00') RETURNING id""",
                Long.class, supplier, supplierOwner);
        otherStore = owner.queryForObject("INSERT INTO stores(tenant_id,name,created_by) VALUES (?, '乙家茶铺·秀洲店', ?) RETURNING id",
                Long.class, supplier, supplierOwner);
        owner.update("INSERT INTO store_members(tenant_id,store_id,user_id) VALUES (?,?,?)", supplier, store, staff);
        owner.update("INSERT INTO store_members(tenant_id,store_id,user_id) VALUES (?,?,?)", supplier, otherStore, staff);
        product = owner.queryForObject("""
                INSERT INTO products(tenant_id,store_id,name,type,category,price,sale_unit,inventory,created_by)
                VALUES (?,?,'龙井茶 50g','PHYSICAL','茶叶',30.00,'罐',10,?) RETURNING id""", Long.class, supplier, store, supplierOwner);

        application(supplier, supplierOwner, "乙家茶铺", SUPPLIER_CODE, "APPROVED");
        application(sameSubject, sameSubjectOwner, "乙家分号", SUPPLIER_CODE, "PENDING_REVIEW");

        billingConfig.setOperatorUserIds(List.of(900L));
        billingConfig.setPaymentMethod("MANUAL");
        billingConfig.setPaymentRecipient("test receipt fixture");
        billingConfig.setPaymentAccount("test-only");
        var plan = billing.createPlan(new BillingDtos.NewPlan("FANGZHI", "方志", "test-only", 29900, 30, 1000,
                true, true, false, true, 15000));
        for (Long tenant : List.of(supplier, holder, sameSubject, rival)) member(tenant, plan.id(), "first");
    }

    // ---------- 场景 ----------

    @Test
    void offerReviewClaimRedeemAndSettleEndToEnd() throws Exception {
        long offerId = createOffer(3);
        assertThat(inventory()).isEqualTo(7);
        refused(post("/api/alliance/offers"), supplierToken, Map.of("storeId", store, "productId", product, "quantity", 4), 400);
        assertThat(data(get("/api/alliance/pool"), holderToken, 200).path("total").asInt()).isZero();

        approve(offerId);
        var quota = data(get("/api/alliance/quota"), holderToken, 200);
        assertThat(quota.path("current").path("amountMinor").asLong()).isEqualTo(15000);
        var pool = data(get("/api/alliance/pool").param("city", "嘉兴"), holderToken, 200);
        assertThat(pool.path("items").get(0).path("remaining").asInt()).isEqualTo(3);
        assertThat(pool.path("items").get(0).path("storeAddress").asText()).contains("南湖区");
        assertThat(data(get("/api/alliance/pool").param("city", "杭州"), holderToken, 200).path("total").asInt()).isZero();

        String ref = claim(offerId, holderToken);
        assertThat(data(get("/api/alliance/quota"), holderToken, 200).path("current").path("usedMinor").asLong()).isEqualTo(3000);
        String code = data(get("/api/alliance/vouchers/" + ref), holderToken, 200).path("code").asText();
        assertThat(code).matches("\\d{8}");
        assertThat(data(get("/api/alliance/vouchers"), holderToken, 200).path("items").get(0).path("code").isTextual()).isFalse();
        claim(offerId, holderToken);
        refused(post("/api/alliance/offers/" + offerId + "/claims"), holderToken, null, 409);

        var preview = data(post(redeemPath(store) + "/preview"), staffToken, Map.of("code", code), 200);
        assertThat(preview.path("productName").asText()).isEqualTo("龙井茶 50g");
        assertThat(preview.path("holderName").asText()).isEqualTo("甲家花店");
        data(post(redeemPath(store)), staffToken, Map.of("code", code), 200);
        assertThat(message(post(redeemPath(store)), staffToken, Map.of("code", code), 409)).contains("已经核销过");

        runSyncTasks();
        var voucher = data(get("/api/alliance/vouchers/" + ref), holderToken, 200);
        assertThat(voucher.path("status").asText()).isEqualTo("REDEEMED");
        assertThat(voucher.path("canDispute").asBoolean()).isTrue();
        assertThat(voucher.path("code").isTextual()).isFalse();

        var settlement = data(get("/api/alliance/settlements"), supplierToken, 200).path("items").get(0);
        assertThat(settlement.path("state").asText()).isEqualTo("IN_DISPUTE_WINDOW");
        assertThat(settlement.path("settleAmountMinor").asLong()).isEqualTo(1500);
        long settlementId = settlement.path("id").asLong();
        String paid = "/api/platform/tenants/" + supplier + "/alliance/settlements/" + settlementId + "/paid";
        assertThat(message(post(paid), operatorToken, Map.of("reference", "BANK-0001"), 409)).contains("申诉期");
        owner.update("UPDATE alliance_settlements SET payable_after = now() - interval '1 minute' WHERE id = ?", settlementId);
        assertThat(data(post(paid), operatorToken, Map.of("reference", "BANK-0001"), 200).path("state").asText()).isEqualTo("PAID");

        var rules = json.readTree(mvc.perform(get("/api/public/alliance/rules")).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        assertThat(rules.path("settleRateBp").asInt()).as("规则页不登录也能读到").isEqualTo(5000);
        assertThat(rules.path("voucherValidDays").asInt()).isEqualTo(30);
        var summary = data(get("/api/platform/alliance/summary"), operatorToken, 200);
        assertThat(summary.path("grantedMinor").asLong()).isEqualTo(60000);
        assertThat(summary.path("claimedFaceMinor").asLong()).isEqualTo(6000);
        assertThat(summary.path("redeemedFaceMinor").asLong()).isEqualTo(3000);
        assertThat(summary.path("paidSettleMinor").asLong()).isEqualTo(1500);
        refused(get("/api/platform/alliance/summary"), holderToken, null, 403);
    }

    @Test
    void disputeFreezesSettlementAndAnInvalidRulingLetsTheVoucherBeUsedAgain() throws Exception {
        long offerId = createOffer(2);
        approve(offerId);
        String ref = claim(offerId, holderToken);
        String code = data(get("/api/alliance/vouchers/" + ref), holderToken, 200).path("code").asText();
        data(post(redeemPath(store)), staffToken, Map.of("code", code), 200);
        runSyncTasks();

        var disputed = data(post("/api/alliance/vouchers/" + ref + "/dispute"), holderToken, Map.of("reason", "到店没有拿到茶叶"), 200);
        assertThat(disputed.path("status").asText()).isEqualTo("DISPUTED");
        runSyncTasks();
        var settlement = data(get("/api/alliance/settlements"), supplierToken, 200).path("items").get(0);
        assertThat(settlement.path("state").asText()).isEqualTo("FROZEN");
        owner.update("UPDATE alliance_settlements SET payable_after = now() - interval '1 minute'");
        refused(post("/api/platform/tenants/" + supplier + "/alliance/settlements/" + settlement.path("id").asLong() + "/paid"),
                operatorToken, Map.of("reference", "BANK-0002"), 409);

        var disputes = data(get("/api/platform/alliance/disputes"), operatorToken, 200);
        assertThat(disputes.get(0).path("disputeReason").asText()).isEqualTo("到店没有拿到茶叶");
        data(post("/api/platform/tenants/" + holder + "/alliance/vouchers/" + ref + "/dispute-resolution"), operatorToken,
                Map.of("valid", false, "note", "门店未能提供核销凭证"), 200);
        assertThat(data(get("/api/alliance/vouchers/" + ref), holderToken, 200).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(data(get("/api/alliance/settlements"), supplierToken, 200).path("items").get(0).path("state").asText()).isEqualTo("VOID");

        data(post(redeemPath(store)), staffToken, Map.of("code", code), 200);
        runSyncTasks();
        var again = data(get("/api/alliance/vouchers/" + ref), holderToken, 200);
        assertThat(again.path("status").asText()).isEqualTo("REDEEMED");
        assertThat(again.path("canDispute").asBoolean()).isTrue();
        var settlements = data(get("/api/alliance/settlements"), supplierToken, 200);
        assertThat(settlements.path("total").asInt()).isEqualTo(1);
        assertThat(settlements.path("items").get(0).path("state").asText()).isEqualTo("IN_DISPUTE_WINDOW");
    }

    @Test
    void anExpiredVoucherReturnsItsStockAndCannotBeRedeemed() throws Exception {
        long offerId = createOffer(2);
        approve(offerId);
        String ref = claim(offerId, holderToken);
        String code = data(get("/api/alliance/vouchers/" + ref), holderToken, 200).path("code").asText();
        int before = inventory();
        owner.update("UPDATE alliance_offer_claims SET expires_at = now() - interval '1 minute'");
        owner.update("UPDATE alliance_vouchers SET expires_at = now() - interval '1 minute'");
        assertThat(message(post(redeemPath(store) + "/preview"), staffToken, Map.of("code", code), 409)).contains("过期");

        var due = TenantContext.runAs(supplier, () -> settlementsDue());
        assertThat(due).containsExactly(UUID.fromString(ref));
        handle(supplier, Map.of("action", "EXPIRE", "ref", ref));
        handle(supplier, Map.of("action", "EXPIRE", "ref", ref));
        assertThat(inventory()).isEqualTo(before + 1);
        assertThat(owner.queryForObject("SELECT status FROM alliance_offer_claims", String.class)).isEqualTo("EXPIRED");
        assertThat(data(get("/api/alliance/vouchers/" + ref), holderToken, 200).path("status").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void sameSubjectOwnStoreForeignStoreAndGuessingAreRefused() throws Exception {
        long offerId = createOffer(2);
        approve(offerId);
        assertThat(message(post("/api/alliance/offers/" + offerId + "/claims"), sameSubjectToken, null, 400)).contains("同一经营主体");
        assertThat(message(post("/api/alliance/offers/" + offerId + "/claims"), supplierToken, null, 400)).contains("自己商户");
        assertThat(data(get("/api/alliance/pool"), sameSubjectToken, 200).path("total").asInt()).isZero();

        String ref = claim(offerId, holderToken);
        String code = data(get("/api/alliance/vouchers/" + ref), holderToken, 200).path("code").asText();
        assertThat(message(post(redeemPath(otherStore) + "/preview"), staffToken, Map.of("code", code), 409)).contains("券码无效");
        assertThat(message(delete("/api/products/" + product), supplierToken, null, 409)).contains("联盟券");
        assertThat(message(delete("/api/stores/" + store), supplierToken, null, 409)).contains("联盟券");

        // 上面在别家门店输码已经算一次失败，再错 9 次就是连续 10 次。
        String wrong = code.equals("00000000") ? "00000001" : "00000000";
        for (int i = 0; i < 9; i++) refused(post(redeemPath(store) + "/preview"), staffToken, Map.of("code", wrong), 409);
        assertThat(message(post(redeemPath(store) + "/preview"), staffToken, Map.of("code", code), 429)).contains("连续输错");

        assertThat(data(get("/api/alliance/settlements"), holderToken, 200).path("total").asInt()).isZero();
        assertThat(data(get("/api/alliance/vouchers"), supplierToken, 200).path("total").asInt()).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM alliance_offer_claims WHERE code_hash = ?", Integer.class, code)).isZero();
    }

    @Test
    void onlyOneOfTwoConcurrentClaimsGetsTheLastVoucherAndTheLoserKeepsItsQuota() throws Exception {
        long offerId = createOffer(1);
        approve(offerId);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (String token : List.of(holderToken, rivalToken)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/alliance/offers/" + offerId + "/claims").header("Authorization", "Bearer " + token))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (var result : results) result.get();
        }
        runSyncTasks();
        assertThat(owner.queryForObject("SELECT claimed_qty FROM alliance_offers WHERE id = ?", Integer.class, offerId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM alliance_offer_claims", Integer.class)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM alliance_vouchers WHERE status = 'ACTIVE'", Integer.class)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM alliance_vouchers WHERE status = 'CLAIMING'", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT sum(used_minor) FROM alliance_quota_grants", Long.class)).isEqualTo(3000);
    }

    @Test
    void twoClerksRedeemingTheSameCodeAtOnceSucceedOnlyOnce() throws Exception {
        long offerId = createOffer(2);
        approve(offerId);
        String ref = claim(offerId, holderToken);
        String code = data(get("/api/alliance/vouchers/" + ref), holderToken, 200).path("code").asText();
        var start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (String token : List.of(staffToken, supplierToken)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post(redeemPath(store)).header("Authorization", "Bearer " + token)
                                    .contentType("application/json").content(json.writeValueAsBytes(Map.of("code", code))))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (var result : results) statuses.add(result.get());
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(owner.queryForObject("SELECT count(*) FROM alliance_settlements", Integer.class)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks WHERE idempotency_key LIKE 'alliance-redeemed:%'", Integer.class)).isEqualTo(1);
    }

    @Test
    void sellingOnlineAndPostingInformationRequireAnApprovedMerchantAndSuspensionTakesListingsDown() throws Exception {
        Map<String, Object> published = Map.of("name", "白茶 100g", "type", "PHYSICAL", "category", "茶叶", "price", 50,
                "saleUnit", "盒", "onlineStatus", "PUBLISHED");
        Map<String, Object> information = Map.of("category", "notice", "title", "新茶到店", "merchantName", "某商户",
                "region", "嘉兴", "content", "本周新茶到店", "declarationAccepted", true);
        Long holderStore = owner.queryForObject("INSERT INTO stores(tenant_id,name,created_by) VALUES (?, '甲家花店', (SELECT id FROM users WHERE phone='13800000003')) RETURNING id",
                Long.class, holder);
        // 甲家没有入驻申请：可以存草稿，但不能上架线上售卖，也不能发布信息。
        assertThat(message(post("/api/stores/" + holderStore + "/products"), holderToken, published, 403)).contains("入驻审核");
        data(post("/api/stores/" + holderStore + "/products"), holderToken, Map.of("name", "白茶 100g", "type", "PHYSICAL",
                "category", "茶叶", "price", 50, "saleUnit", "盒", "onlineStatus", "DRAFT"), 200);
        assertThat(message(post("/api/information-publications"), holderToken, information, 403)).contains("入驻审核");

        // 乙家入驻通过：上架和发布都可以。
        data(post("/api/stores/" + store + "/products"), supplierToken, published, 200);
        data(post("/api/information-publications"), supplierToken, information, 200);
        long offerId = createOffer(3);
        approve(offerId);
        String ref = claim(offerId, holderToken);
        String code = data(get("/api/alliance/vouchers/" + ref), holderToken, 200).path("code").asText();
        int reserved = inventory();

        Long application = owner.queryForObject("SELECT id FROM merchant_applications WHERE tenant_id = ?", Long.class, supplier);
        data(post("/api/platform/tenants/" + supplier + "/merchants/" + application + "/review"), operatorToken,
                Map.of("status", "SUSPENDED", "note", "经营资料待复核"), 200);
        assertThat(owner.queryForObject("SELECT count(*) FROM products WHERE tenant_id = ? AND online_status = 'PUBLISHED'", Integer.class, supplier)).isZero();
        assertThat(owner.queryForList("SELECT status FROM information_publications WHERE tenant_id = ?", String.class, supplier)).containsOnly("OFFLINE");
        assertThat(owner.queryForObject("SELECT status FROM alliance_offers WHERE id = ?", String.class, offerId)).isEqualTo("WITHDRAWN");
        assertThat(inventory()).isEqualTo(reserved + 2);
        assertThat(message(post("/api/stores/" + store + "/products"), supplierToken, published, 403)).contains("入驻审核");
        // 已经领出去的券照常能在门店核销。
        data(post(redeemPath(store)), staffToken, Map.of("code", code), 200);
    }

    @Test
    void earlyRenewalAddsTheNextPeriodWithoutStackingQuota() throws Exception {
        Long planId = owner.queryForObject("SELECT id FROM billing_plans", Long.class);
        member(holder, planId, "renewal");
        var quota = data(get("/api/alliance/quota"), holderToken, 200);
        assertThat(quota.path("current").path("amountMinor").asLong()).isEqualTo(15000);
        assertThat(quota.path("upcoming").size()).isEqualTo(1);
        assertThat(quota.path("upcoming").get(0).path("validFrom").asText())
                .isEqualTo(quota.path("current").path("validUntil").asText());
        member(holder, planId, "renewal");
        assertThat(owner.queryForObject("SELECT count(*) FROM alliance_quota_grants WHERE tenant_id = ?", Integer.class, holder)).isEqualTo(2);
    }

    @Test
    void aClaimInterruptedAfterTheFirstStepIsFinishedByItsTask() throws Exception {
        long offerId = createOffer(2);
        approve(offerId);
        Long holderOwner = owner.queryForObject("SELECT id FROM users WHERE phone = '13800000003'", Long.class);
        var ticket = TenantContext.runAs(holder, () -> voucherService.startClaim(offerId, holderOwner));
        assertThat(data(get("/api/alliance/vouchers/" + ticket.ref()), holderToken, 200).path("status").asText()).isEqualTo("CLAIMING");
        assertThat(owner.queryForObject("SELECT count(*) FROM alliance_offer_claims", Integer.class)).isZero();

        runSyncTasks();
        runSyncTasks();
        var voucher = data(get("/api/alliance/vouchers/" + ticket.ref()), holderToken, 200);
        assertThat(voucher.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(voucher.path("code").asText()).matches("\\d{8}");
        assertThat(owner.queryForObject("SELECT claimed_qty FROM alliance_offers WHERE id = ?", Integer.class, offerId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT used_minor FROM alliance_quota_grants WHERE tenant_id = ?", Long.class, holder)).isEqualTo(3000);
    }

    @Autowired AllianceVoucherService voucherService;

    // ---------- 工具 ----------

    private long createOffer(int quantity) throws Exception {
        return data(post("/api/alliance/offers"), supplierToken,
                Map.of("storeId", store, "productId", product, "quantity", quantity), 200).path("id").asLong();
    }

    private void approve(long offerId) throws Exception {
        assertThat(data(post("/api/platform/tenants/" + supplier + "/alliance/offers/" + offerId + "/review"), operatorToken,
                Map.of("approved", true), 200).path("status").asText()).isEqualTo("ACTIVE");
    }

    private String claim(long offerId, String token) throws Exception {
        var voucher = data(post("/api/alliance/offers/" + offerId + "/claims"), token, null, 200);
        assertThat(voucher.path("status").asText()).isEqualTo("ACTIVE");
        return voucher.path("ref").asText();
    }

    private static String redeemPath(Long storeId) {
        return "/api/stores/" + storeId + "/alliance-redemptions";
    }

    private int inventory() {
        return owner.queryForObject("SELECT inventory FROM products WHERE id = ?", Integer.class, product);
    }

    @Autowired AllianceSettlementService settlementService;

    private List<UUID> settlementsDue() {
        return settlementService.dueForExpiry(10);
    }

    /** 测试里不开 worker：把登记的联盟券同步任务按顺序直接交给处理器。 */
    private void runSyncTasks() throws Exception {
        var rows = owner.queryForList("SELECT id, tenant_id, payload::text AS payload FROM tasks WHERE type = 'ALLIANCE_SYNC' ORDER BY id");
        for (var row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            if (!handledTasks.add(id)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = json.readValue((String) row.get("payload"), Map.class);
            handle(((Number) row.get("tenant_id")).longValue(), payload);
        }
    }

    private void handle(Long tenantId, Map<String, Object> payload) {
        var task = new Task();
        task.setTenantId(tenantId);
        task.setType(AllianceSyncHandler.TYPE);
        task.setPayload(new java.util.HashMap<>(payload));
        TenantContext.runAs(tenantId, () -> sync.handle(task));
    }

    private Long tenant(String name) {
        return owner.queryForObject("INSERT INTO tenants(name) VALUES (?) RETURNING id", Long.class, name);
    }

    private Long user(Long tenant, String phone, String role) {
        return owner.queryForObject("INSERT INTO users(tenant_id,phone,name,role) VALUES (?,?,?,?) RETURNING id",
                Long.class, tenant, phone, phone, role);
    }

    private void application(Long tenant, Long user, String name, String code, String status) {
        owner.update("""
                INSERT INTO merchant_applications(tenant_id,submitted_by,merchant_name,company_name,industry,region,
                    credit_code,contact_name,contact_phone,introduction,status)
                VALUES (?,?,?,?,'餐饮 / 茶饮','浙江嘉兴',?,'联系人','13800009999','测试商户',?)""",
                tenant, user, name, name + "有限公司", code, status);
    }

    private void member(Long tenant, Long planId, String label) {
        TenantContext.runAs(tenant, () -> {
            var order = billing.subscribe(new BillingDtos.Subscribe("alliance-" + label + "-" + tenant, planId));
            billing.confirmPayment(order.id(), new BillingDtos.Confirmation(29900, "receipt-" + label + "-" + tenant), 900L);
            return null;
        });
    }

    private JsonNode data(MockHttpServletRequestBuilder request, String token, int status) throws Exception {
        return data(request, token, null, status);
    }

    private JsonNode data(MockHttpServletRequestBuilder request, String token, Object body, int status) throws Exception {
        return response(request, token, body, status).path("data");
    }

    private String message(MockHttpServletRequestBuilder request, String token, Object body, int status) throws Exception {
        return response(request, token, body, status).path("message").asText();
    }

    private void refused(MockHttpServletRequestBuilder request, String token, Object body, int status) throws Exception {
        response(request, token, body, status);
    }

    private JsonNode response(MockHttpServletRequestBuilder request, String token, Object body, int status) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsBytes(body));
        var result = mvc.perform(request).andReturn().getResponse();
        String content = result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(result.getStatus()).as(content).isEqualTo(status);
        return json.readTree(content);
    }
}
