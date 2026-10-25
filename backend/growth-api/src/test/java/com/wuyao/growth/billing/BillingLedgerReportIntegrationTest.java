package com.wuyao.growth.billing;

import com.wuyao.growth.common.security.JwtService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 全平台烟火流水：跨商户合并、筛选、合计，以及只有计费运营名单能调用。用户编号 1 在名单里。 */
@SpringBootTest(properties = {"spring.config.import=", "growth.worker.enabled=false", "logging.level.root=WARN",
        "growth.voice.sample-storage=minio", "growth.billing.operator-user-ids=1"})
@AutoConfigureMockMvc
@Testcontainers
class BillingLedgerReportIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_report_test").withUsername("growth_owner").withPassword("test_owner_password");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "growth_app"); r.add("spring.datasource.password", () -> "growth_dev_local");
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl); r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword); r.add("spring.flyway.placeholders.app_db_password", () -> "growth_dev_local");
        r.add("growth.jwt.secret", () -> "test-only-random-signing-secret-0123456789abcdef");
    }

    @Autowired BillingLedgerReport report;
    @Autowired BillingService billing;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    long shop, cafe, operator, merchant;

    @BeforeEach void setUp() {
        var owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        shop = owner.queryForObject("INSERT INTO tenants(name) VALUES ('冰雪时光') RETURNING id", Long.class);
        cafe = owner.queryForObject("INSERT INTO tenants(name) VALUES ('一方小馆') RETURNING id", Long.class);
        BillingTestFixtures.activateMembership(owner, shop, cafe);
        owner.update("UPDATE billing_accounts SET purchased_available=1000");
        operator = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?,'ops','运营小王') RETURNING id", Long.class, shop);
        merchant = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?,'cafe','陈老板') RETURNING id", Long.class, cafe);

        // 冰雪时光：图片预占 12，交付 9，退回 3。一方小馆：视频预占 10，全部交付。
        TenantContext.runAs(shop, () -> {
            String r = billing.reserve("test:" + UUID.randomUUID(), "IMAGE", 12, "图片创作预占");
            billing.capture(r, 9, "test-capture-image", "图片交付");
            billing.release(r, "test-release-image", "未交付退回");
            return null;
        });
        TenantContext.runAs(cafe, () -> {
            String r = billing.reserve("test:" + UUID.randomUUID(), "VIDEO", 10, "视频创作预占");
            billing.capture(r, 10, "test-capture-video", "视频交付");
            return null;
        });
    }

    @Test void entriesMergeEveryMerchantNewestFirstAndFilterByTypeFeatureAndMerchant() {
        var all = report.entries(0, 20, 7, null, null, null);
        assertThat(all.total()).isEqualTo(5);
        assertThat(all.items()).extracting(BillingDtos.PlatformLedgerEntry::tenantName).containsOnly("冰雪时光", "一方小馆");
        assertThat(all.items().getFirst().createdAt()).isAfterOrEqualTo(all.items().getLast().createdAt());

        var videoCaptures = report.entries(0, 20, 7, "CAPTURE", "VIDEO", null);
        assertThat(videoCaptures.items()).singleElement().satisfies(e -> {
            assertThat(e.tenantId()).isEqualTo(cafe);
            assertThat(e.heldDelta()).isEqualTo(-10);
            assertThat(e.capability()).isEqualTo("VIDEO");
        });
        assertThat(report.entries(0, 20, 7, null, null, "冰雪").items())
                .extracting(BillingDtos.PlatformLedgerEntry::type).containsExactlyInAnyOrder("RESERVE", "CAPTURE", "RELEASE");
        assertThat(report.entries(0, 2, 7, null, null, null).totalPages()).isEqualTo(3);
    }

    @Test void summaryCountsOnlyDeliveredCreditsAsConsumption() {
        var summary = report.summary(7);
        assertThat(summary.consumed()).isEqualTo(19);
        assertThat(summary.consumedToday()).isEqualTo(19);
        assertThat(summary.refunded()).isEqualTo(3);
        assertThat(summary.consumedByCapability()).isEqualTo(Map.of("IMAGE", 9L, "VIDEO", 10L));
    }

    @Test void imageLedgerKeepsFirstPreviewDurationWhileDurableDeliveryContinues() {
        var owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        String reservation=TenantContext.runAs(shop,()->billing.reserve("preview:"+UUID.randomUUID(),"IMAGE",3,"Image preview"));
        long creation=owner.queryForObject("INSERT INTO image_creations(tenant_id,request_key,request_hash,request,status,billing_reservation_id,created_at) "
            +"VALUES (?,?,'test','{\"count\":1,\"workflow\":\"POSTER\"}','GENERATING',?,'2026-10-24T00:00:00Z') RETURNING id",Long.class,shop,UUID.randomUUID().toString(),reservation);
        owner.update("INSERT INTO image_items(tenant_id,creation_id,ordinal,spec,status,previewed_at) "
            +"VALUES (?,?,0,'{}','GENERATING','2026-10-24T00:00:55Z')",shop,creation);
        assertThat(report.entries(0,20,90,null,"IMAGE",null).items()).filteredOn(r->r.previewElapsedMillis()!=null)
            .singleElement().satisfies(r->{assertThat(r.previewElapsedMillis()).isEqualTo(55000);assertThat(r.elapsedMillis()).isEqualTo(55000);assertThat(r.taskStatus()).isEqualTo("SUCCEEDED");});
        owner.update("UPDATE image_items SET status='SUCCEEDED',persisted_at='2026-10-24T00:01:58Z' WHERE creation_id=?",creation);
        assertThat(report.entries(0,20,90,null,"IMAGE",null).items()).filteredOn(r->r.previewElapsedMillis()!=null)
            .singleElement().satisfies(r->{assertThat(r.previewElapsedMillis()).isEqualTo(55000);assertThat(r.elapsedMillis()).isEqualTo(55000);assertThat(r.taskStatus()).isEqualTo("SUCCEEDED");});
    }

    @Test void ledgerRowsUseTheSameTaskModelAndElapsedTimeWithoutDuplicatingSettlements() {
        var owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        String reservation=TenantContext.runAs(shop,()->billing.reserve("plan:"+UUID.randomUUID(),"VIDEO_PLAN",1,"视频方案预占"));
        owner.update("INSERT INTO video_plans(tenant_id,request_key,status,billing_reservation_id,created_at,updated_at) "
            +"VALUES (?,?,'SUCCEEDED',?,'2026-10-24T00:00:00Z','2026-10-24T00:00:12Z')",shop,UUID.randomUUID().toString(),reservation);
        TenantContext.runAs(shop,()->{billing.capture(reservation,1,"plan-delivered","视频方案交付");return null;});
        var settled=owner.queryForObject("SELECT created_at FROM billing_ledger WHERE reservation_id=? AND type='CAPTURE'",
            java.sql.Timestamp.class,reservation).toInstant();
        owner.update("UPDATE video_plans SET created_at=?,updated_at=? WHERE billing_reservation_id=?",
            java.sql.Timestamp.from(settled.minusMillis(12000)),java.sql.Timestamp.from(settled),reservation);
        var entries=report.entries(0,20,90,null,"VIDEO_PLAN",null);
        assertThat(entries.items()).hasSize(2).allSatisfy(row->{
            assertThat(row.model()).isEqualTo("一方志提示词");
            assertThat(row.elapsedMillis()).isEqualTo(12000);
            assertThat(row.taskStatus()).isEqualTo("SUCCEEDED");
        });
        assertThat(report.entries(0,20,90,null,"IMAGE",null).items()).allSatisfy(row->assertThat(row.model()).isEqualTo("一方志Image"));
        assertThat(report.summary(90).consumed()).isEqualTo(20);
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_ledger",Long.class)).isEqualTo(7);
    }

    @Test void onlyBillingOperatorsCanReadThePlatformLedger() throws Exception {
        mvc.perform(get("/api/billing/operations/ledger")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/billing/operations/ledger").header("Authorization", "Bearer " + jwt.issueAccessToken(merchant, cafe, null)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/billing/operations/ledger/summary").header("Authorization", "Bearer " + jwt.issueAccessToken(operator, shop, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.consumed").value(19));
        mvc.perform(get("/api/billing/operations/ledger?days=91").header("Authorization", "Bearer " + jwt.issueAccessToken(operator, shop, null)))
                .andExpect(status().isBadRequest());
    }
}
