package com.wuyao.growth.partner;

import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.billing.BillingService;
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
    long ops, agency, shop, cafe, bakery, operator, agentA, agentB, bd, shopOwner, cafeOwner, bakeryOwner;
    String planCode;

    @BeforeEach void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants, billing_redeem_codes, tasks RESTART IDENTITY CASCADE");
        ops = tenant("平台");
        agency = tenant("代理公司");
        shop = tenant("冰雪时光");
        cafe = tenant("一方小馆");
        bakery = tenant("麦香烘焙");
        operator = user(ops, "ops", "运营小王");
        agentA = user(agency, "agent-a", "甲服务商");
        agentB = user(agency, "agent-b", "乙服务商");
        bd = user(agency, "bd", "BD 小周");
        shopOwner = user(shop, "shop", "李老板");
        cafeOwner = user(cafe, "cafe", "陈老板");
        bakeryOwner = user(bakery, "bakery", "王老板");
        planCode = "MONTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        billing.createPlan(new BillingDtos.NewPlan(planCode, "月卡", "test-only", 50000, 30, 0, true, true, true, true));
    }

    @Test void everyAccountGetsADistinctEightDigitUidShownOnItsProfile() throws Exception {
        List<Long> uids = owner.queryForList("SELECT uid FROM users ORDER BY id", Long.class);
        assertThat(uids).hasSize(7).doesNotHaveDuplicates().allSatisfy(uid -> assertThat(uid).isBetween(10_000_000L, 99_999_999L));
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

    @Test void bdsManageOnlyTheirOwnTenantsAndOwnersRenewVoidAndReassign() throws Exception {
        long a = partner("甲服务商", agentA);
        platform(post("/api/platform/partners/" + a + "/credits"), "{\"requestKey\":\"grant-bd-0001\",\"credits\":5000,\"note\":\"线下打款\"}").andExpect(status().isOk());
        platform(put("/api/platform/partners/" + a + "/quotas"), "{\"planCode\":\"" + planCode + "\",\"quota\":5}").andExpect(status().isOk());

        // 负责人按 UID 加 BD；BD 不能管团队，负责人不能被当成 BD 再加一次。
        call(agentA, agency, post("/api/partner/team"), "{\"uid\":" + uid(bd) + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].role").value("BD")).andExpect(jsonPath("$.data[1].tenantCount").value(0));
        call(agentA, agency, post("/api/partner/team"), "{\"uid\":" + uid(agentA) + "}").andExpect(status().isConflict());
        call(bd, agency, get("/api/partner/team"), null).andExpect(status().isForbidden());
        call(bd, agency, get("/api/partner/me"), null).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("BD"))
                .andExpect(jsonPath("$.data.partner.creditsAvailable").doesNotExist());

        // BD 按 UID 加的商户归到这个 BD；负责人直接加的 BD 看不到，BD 也不能再加到自己名下。
        call(bd, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(shopOwner) + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bdUserId").value(bd)).andExpect(jsonPath("$.data.bdName").value("BD 小周"));
        call(agentA, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(cafeOwner) + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bdUserId").doesNotExist());
        call(bd, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(cafeOwner) + "}").andExpect(status().isConflict());
        call(bd, agency, get("/api/partner/tenants"), null).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].tenantName").value("冰雪时光"));
        call(agentA, agency, get("/api/partner/tenants"), null).andExpect(jsonPath("$.data.total").value(2));
        call(agentA, agency, get("/api/partner/tenants?bdUserId=0"), null).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].tenantName").value("一方小馆"));

        // 只有负责人能分烟火、看服务商流水。
        call(bd, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"transfer-bd-01\",\"credits\":10}").andExpect(status().isForbidden());
        call(bd, agency, get("/api/partner/ledger"), null).andExpect(status().isForbidden());
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"transfer-bd-02\",\"credits\":2000}").andExpect(status().isOk());
        call(agentA, agency, post("/api/partner/tenants/" + cafe + "/credits"), "{\"requestKey\":\"transfer-bd-03\",\"credits\":2000}").andExpect(status().isOk());
        assertThat(owner.queryForObject("SELECT credits_available FROM partners WHERE id=?", Long.class, a)).isEqualTo(1000);

        // BD 生成的码兑换后归到这个 BD；BD 只看自己生成的码，负责人能按生成人筛。
        call(bd, agency, post("/api/partner/codes"), "{\"requestKey\":\"codes-bd-0001\",\"planCode\":\"" + planCode + "\",\"quantity\":2}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].createdBy").value(bd));
        call(agentA, agency, post("/api/partner/codes"), "{\"requestKey\":\"codes-own-0001\",\"planCode\":\"" + planCode + "\",\"quantity\":1}").andExpect(status().isOk());
        call(bd, agency, get("/api/partner/codes"), null).andExpect(jsonPath("$.data.total").value(2));
        call(agentA, agency, get("/api/partner/codes"), null).andExpect(jsonPath("$.data.total").value(3));
        call(agentA, agency, get("/api/partner/codes?createdBy=" + bd), null).andExpect(jsonPath("$.data.total").value(2));
        List<Long> bdCodes = owner.queryForList("SELECT id FROM billing_redeem_codes WHERE created_by=? ORDER BY id", Long.class, bd);
        long ownCode = owner.queryForObject("SELECT id FROM billing_redeem_codes WHERE created_by=?", Long.class, agentA);
        redeem(bakeryOwner, bakery, owner.queryForObject("SELECT code FROM billing_redeem_codes WHERE id=?", String.class, bdCodes.get(0))).andExpect(status().isOk());
        assertThat(owner.queryForObject("SELECT bd_user_id FROM partner_tenants WHERE tenant_id=?", Long.class, bakery)).isEqualTo(bd);

        // 作废：只能作废没兑换的；BD 不能作废负责人的码；额度不退回。
        call(bd, agency, post("/api/partner/codes/" + ownCode + "/void"), null).andExpect(status().isNotFound());
        call(bd, agency, post("/api/partner/codes/" + bdCodes.get(0) + "/void"), null).andExpect(status().isConflict());
        call(bd, agency, post("/api/partner/codes/" + bdCodes.get(1) + "/void"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"));
        call(agentA, agency, post("/api/partner/codes/" + ownCode + "/void"), null).andExpect(status().isOk());
        assertThat(owner.queryForObject("SELECT used FROM partner_code_quotas WHERE partner_id=?", Integer.class, a)).isEqualTo(3);

        // 直接续期：扣一个额度，在原到期日上顺延；同一请求重复提交不重复扣、不重复顺延。BD 只能续自己的商户。
        java.time.Instant before = java.time.Instant.parse(owner.queryForObject(
                "SELECT to_char(member_until AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM billing_accounts WHERE tenant_id=?", String.class, bakery));
        String renew = "{\"requestKey\":\"renew-0001\",\"planCode\":\"" + planCode + "\"}";
        call(bd, agency, post("/api/partner/tenants/" + bakery + "/renewals"), renew).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberActive").value(true));
        call(bd, agency, post("/api/partner/tenants/" + bakery + "/renewals"), renew).andExpect(status().isOk());
        java.time.Instant after = java.time.Instant.parse(owner.queryForObject(
                "SELECT to_char(member_until AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM billing_accounts WHERE tenant_id=?", String.class, bakery));
        assertThat(java.time.Duration.between(before, after)).isEqualTo(java.time.Duration.ofDays(30));
        assertThat(owner.queryForObject("SELECT used FROM partner_code_quotas WHERE partner_id=?", Integer.class, a)).isEqualTo(4);
        assertThat(owner.queryForObject("SELECT note FROM billing_redeem_codes WHERE batch_key LIKE 'partner-renew-%'", String.class)).isEqualTo("直接续期");
        call(bd, agency, post("/api/partner/tenants/" + cafe + "/renewals"), "{\"requestKey\":\"renew-0002\",\"planCode\":\"" + planCode + "\"}")
                .andExpect(status().isNotFound());

        // 到期筛选：冰雪时光和一方小馆从未开通，麦香烘焙 60 天后才到期。
        call(agentA, agency, get("/api/partner/tenants?expiry=NEVER"), null).andExpect(jsonPath("$.data.total").value(2));
        call(agentA, agency, get("/api/partner/tenants?expiry=SOON30"), null).andExpect(jsonPath("$.data.total").value(0));
        call(bd, agency, get("/api/partner/tenants?expiry=NEVER"), null).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].tenantName").value("冰雪时光"));
        call(agentA, agency, get("/api/partner/tenants?expiry=BAD"), null).andExpect(status().isBadRequest());

        // 按 UID 预查询：负责人看得到归属和账号身份，BD 只看商户这边；姓名打码。
        call(agentA, agency, get("/api/partner/lookup?uid=" + uid(shopOwner)), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantState").value("MINE")).andExpect(jsonPath("$.data.tenantId").value(shop))
                .andExpect(jsonPath("$.data.userName").value("李**")).andExpect(jsonPath("$.data.bdName").value("BD 小周"))
                .andExpect(jsonPath("$.data.memberState").value("NONE"));
        call(bd, agency, get("/api/partner/lookup?uid=" + uid(cafeOwner)), null)
                .andExpect(jsonPath("$.data.tenantState").value("TEAM")).andExpect(jsonPath("$.data.tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.memberState").doesNotExist());
        call(agentA, agency, get("/api/partner/lookup?uid=" + uid(bd)), null).andExpect(jsonPath("$.data.memberState").value("BD"));
        call(agentA, agency, get("/api/partner/lookup?uid=99999999"), null).andExpect(status().isNotFound());

        // 商户详情：负责人看得到分给他的烟火，BD 看不到；兑换记录含直接续期；不归自己管的看不到。
        call(agentA, agency, get("/api/partner/tenants/" + shop), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant.tenantName").value("冰雪时光")).andExpect(jsonPath("$.data.transfers[0].delta").value(-2000));
        call(bd, agency, get("/api/partner/tenants/" + bakery), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transfers").doesNotExist()).andExpect(jsonPath("$.data.codes.length()").value(2))
                .andExpect(jsonPath("$.data.codes[0].note").value("直接续期"));
        call(bd, agency, get("/api/partner/tenants/" + cafe), null).andExpect(status().isNotFound());
        call(agentA, agency, get("/api/partner/team"), null).andExpect(jsonPath("$.data[1].codes.generated").value(3))
                .andExpect(jsonPath("$.data[1].codes.redeemed").value(2)).andExpect(jsonPath("$.data[1].codes.voided").value(1))
                .andExpect(jsonPath("$.data[1].codes.unused").value(0)).andExpect(jsonPath("$.data[1].codes.renewals").value(1));

        // 负责人调配商户；移除 BD 时指定商户转给谁（0 表示回到负责人直接管）。
        call(bd, agency, put("/api/partner/tenants/" + cafe + "/bd"), "{\"bdUserId\":" + bd + "}").andExpect(status().isForbidden());
        call(agentA, agency, put("/api/partner/tenants/" + cafe + "/bd"), "{\"bdUserId\":" + bd + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bdUserId").value(bd));
        call(agentA, agency, put("/api/partner/tenants/" + cafe + "/bd"), "{\"bdUserId\":" + agentA + "}").andExpect(status().isNotFound());
        call(agentA, agency, get("/api/partner/team"), null).andExpect(jsonPath("$.data[1].tenantCount").value(3));
        platform(get("/api/platform/partners"), null).andExpect(jsonPath("$.data[0].members.length()").value(2));
        call(agentA, agency, delete("/api/partner/team/" + agentA + "?transferTo=0"), null).andExpect(status().isNotFound());
        call(agentA, agency, delete("/api/partner/team/" + bd + "?transferTo=" + bd), null).andExpect(status().isNotFound());
        call(agentA, agency, delete("/api/partner/team/" + bd), null).andExpect(status().isBadRequest());
        call(agentA, agency, delete("/api/partner/team/" + bd + "?transferTo=0"), null).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        assertThat(owner.queryForObject("SELECT count(*) FROM partner_tenants WHERE bd_user_id IS NOT NULL", Long.class)).isZero();
        call(bd, agency, get("/api/partner/me"), null).andExpect(status().isForbidden());
    }

    @Test void workbenchAndDashboardShowUsageWithoutWorksAndFlagLowBalance() throws Exception {
        long a = partner("甲服务商", agentA);
        platform(post("/api/platform/partners/" + a + "/credits"), "{\"requestKey\":\"grant-wb-0001\",\"credits\":5000,\"note\":\"线下打款\"}").andExpect(status().isOk());
        platform(put("/api/platform/partners/" + a + "/quotas"), "{\"planCode\":\"" + planCode + "\",\"quota\":5}").andExpect(status().isOk());
        call(agentA, agency, post("/api/partner/team"), "{\"uid\":" + uid(bd) + "}").andExpect(status().isOk());
        call(agentA, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(shopOwner) + "}").andExpect(status().isOk());
        call(bd, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(cafeOwner) + "}").andExpect(status().isOk());

        // 冰雪时光：续期后分 1000 烟火，用掉 905，余 95 不到最近充值的 10%；一方小馆：续期了还没用过。
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/renewals"), "{\"requestKey\":\"renew-wb-01\",\"planCode\":\"" + planCode + "\"}").andExpect(status().isOk());
        call(agentA, agency, post("/api/partner/tenants/" + cafe + "/renewals"), "{\"requestKey\":\"renew-wb-02\",\"planCode\":\"" + planCode + "\"}").andExpect(status().isOk());
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"transfer-wb-01\",\"credits\":1000}").andExpect(status().isOk());
        TenantContext.runAs(shop, () -> {
            String image = billing.reserve("wb-image-" + UUID.randomUUID(), "IMAGE", 5, "图片创作预占");
            billing.capture(image, 5, "wb-capture-image", "图片交付");
            String video = billing.reserve("wb-video-" + UUID.randomUUID(), "VIDEO", 900, "视频创作预占");
            billing.capture(video, 900, "wb-capture-video", "视频交付");
            return null;
        });

        // 商户详情带使用情况和上手进度。
        call(agentA, agency, get("/api/partner/tenants/" + shop), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usage.consumed").value(905)).andExpect(jsonPath("$.data.usage.uses").value(2))
                .andExpect(jsonPath("$.data.usage.consumedByCapability.VIDEO").value(900))
                .andExpect(jsonPath("$.data.usage.lastTopUpCredits").value(1000)).andExpect(jsonPath("$.data.usage.lowBalance").value(true))
                .andExpect(jsonPath("$.data.onboarding.activated").value(true)).andExpect(jsonPath("$.data.onboarding.firstUse").value(true))
                .andExpect(jsonPath("$.data.onboarding.stores").value(0));

        // 待办：负责人看到冰雪时光余额不足、一方小馆开通未使用；BD 只看到自己的一方小馆。
        call(agentA, agency, get("/api/partner/workbench"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.LOW_BALANCE").value(1)).andExpect(jsonPath("$.data.counts.NOT_STARTED").value(1))
                .andExpect(jsonPath("$.data.counts.EXPIRING").value(0))
                .andExpect(jsonPath("$.data.todos[?(@.type=='LOW_BALANCE')].tenant.tenantName").value("冰雪时光"));
        call(bd, agency, get("/api/partner/workbench"), null).andExpect(jsonPath("$.data.todos.length()").value(1))
                .andExpect(jsonPath("$.data.todos[0].type").value("NOT_STARTED")).andExpect(jsonPath("$.data.todos[0].tenant.tenantName").value("一方小馆"));

        // 看板：负责人看全部和烟火，BD 只看自己的、没有烟火数字；按 BD 筛。
        call(agentA, agency, get("/api/partner/dashboard?days=7"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenants").value(2)).andExpect(jsonPath("$.data.consumed").value(905))
                .andExpect(jsonPath("$.data.activeTenants").value(1)).andExpect(jsonPath("$.data.firstUses").value(1))
                .andExpect(jsonPath("$.data.granted").value(5000)).andExpect(jsonPath("$.data.transferred").value(1000))
                .andExpect(jsonPath("$.data.renewals").value(2)).andExpect(jsonPath("$.data.codesRedeemed").value(2))
                .andExpect(jsonPath("$.data.daily.length()").value(7)).andExpect(jsonPath("$.data.topTenants[0].tenantName").value("冰雪时光"))
                .andExpect(jsonPath("$.data.bds[0].name").value("BD 小周")).andExpect(jsonPath("$.data.bds[0].tenants").value(1));
        call(bd, agency, get("/api/partner/dashboard?days=30"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenants").value(1)).andExpect(jsonPath("$.data.consumed").value(0))
                .andExpect(jsonPath("$.data.granted").doesNotExist()).andExpect(jsonPath("$.data.creditsAvailable").doesNotExist())
                .andExpect(jsonPath("$.data.renewals").value(0)).andExpect(jsonPath("$.data.bds.length()").value(0));
        call(agentA, agency, get("/api/partner/dashboard?days=7&bdUserId=" + bd), null)
                .andExpect(jsonPath("$.data.tenants").value(1)).andExpect(jsonPath("$.data.transferred").value(0));
        call(agentA, agency, get("/api/partner/dashboard?days=5"), null).andExpect(status().isBadRequest());
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
