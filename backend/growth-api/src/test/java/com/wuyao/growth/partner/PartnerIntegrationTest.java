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
 * 用户 ID 与服务商：平台建服务商（负责人的商户就是团队账户）、把烟火发进团队账户；服务商按用户 ID 添加名下商户、
 * 从团队账户分烟火、不限个数地生成激活码；成员按手机号加入团队账户，按权限做事；
 * 兑换服务商的激活码归属到该服务商（第一次为准），平台可以改归属。用户编号 1 在计费运营名单里。
 */
@SpringBootTest(properties = {"spring.config.import=", "growth.worker.enabled=false", "logging.level.root=WARN",
        "growth.voice.sample-storage=minio", "growth.billing.operator-user-ids=1", "growth.platform.operator-user-ids=1"})
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

    static final String MEMBER_PERMS = "{\"tenants\":true,\"codes\":true,\"distribute\":false,\"finance\":false,\"team\":false}";

    @Autowired BillingService billing;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    JdbcTemplate owner;
    long ops, agency, agencyB, shop, cafe, bakery, operator, agentA, agentB, shopOwner, cafeOwner, bakeryOwner;
    String planCode;

    @BeforeEach void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants, billing_redeem_codes, tasks RESTART IDENTITY CASCADE");
        ops = tenant("平台");
        agency = tenant("甲公司");
        agencyB = tenant("乙公司");
        shop = tenant("冰雪时光");
        cafe = tenant("一方小馆");
        bakery = tenant("麦香烘焙");
        operator = user(ops, "ops", "运营小王");
        agentA = user(agency, "agent-a", "甲老板");
        agentB = user(agencyB, "agent-b", "乙老板");
        shopOwner = user(shop, "shop", "李老板");
        cafeOwner = user(cafe, "cafe", "陈老板");
        bakeryOwner = user(bakery, "bakery", "王老板");
        planCode = "MONTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        billing.createPlan(new BillingDtos.NewPlan(planCode, "月卡", "test-only", 50000, 30, 0, true, true, true, true));
    }

    @Test void everyAccountGetsADistinctEightDigitUidShownOnItsProfile() throws Exception {
        List<Long> uids = owner.queryForList("SELECT uid FROM users ORDER BY id", Long.class);
        assertThat(uids).hasSize(6).doesNotHaveDuplicates().allSatisfy(uid -> assertThat(uid).isBetween(10_000_000L, 99_999_999L));
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(shopOwner, shop)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.uid").value(uid(shopOwner)));
    }

    @Test void grantsLandInTheTeamAccountAndCodesAreUnlimited() throws Exception {
        long a = partner("甲服务商", agentA), b = partner("乙服务商", agentB);
        platform(post("/api/platform/partners/" + a + "/credits"), "{\"requestKey\":\"grant-a-0001\",\"credits\":1000,\"note\":\"线下打款\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.teamTenantName").value("甲公司"));
        platform(post("/api/platform/partners/" + a + "/credits"), "{\"requestKey\":\"grant-a-0001\",\"credits\":1000,\"note\":\"线下打款\"}")
                .andExpect(jsonPath("$.data.partner.creditsAvailable").value(1000));
        assertThat(owner.queryForObject("SELECT purchased_available FROM billing_accounts WHERE tenant_id=?", Long.class, agency)).isEqualTo(1000);

        // 按用户 ID 添加名下商户；别家已经添加的不能再加；团队账户自己不能加。
        call(agentA, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(shopOwner) + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tenantName").value("冰雪时光")).andExpect(jsonPath("$.data.source").value("UID"));
        call(agentB, agencyB, post("/api/partner/tenants"), "{\"uid\":" + uid(shopOwner) + "}").andExpect(status().isConflict());
        call(agentA, agency, post("/api/partner/tenants"), "{\"uid\":99999999}").andExpect(status().isNotFound());
        call(agentA, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(agentA) + "}").andExpect(status().isBadRequest());

        // 分烟火：从团队账户扣，记入商户的充值烟火；同一请求只分一次；不在名下的商户不能分，超出余额不能分。
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"transfer-0001\",\"credits\":300}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.creditsAvailable").value(300));
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"transfer-0001\",\"credits\":300}").andExpect(status().isOk());
        assertThat(owner.queryForObject("SELECT purchased_available FROM billing_accounts WHERE tenant_id=?", Long.class, shop)).isEqualTo(300);
        assertThat(owner.queryForObject("SELECT purchased_available FROM billing_accounts WHERE tenant_id=?", Long.class, agency)).isEqualTo(700);
        call(agentA, agency, post("/api/partner/tenants/" + cafe + "/credits"), "{\"requestKey\":\"transfer-0002\",\"credits\":10}").andExpect(status().isNotFound());
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"transfer-0003\",\"credits\":800}")
                .andExpect(jsonPath("$.code").value(9003));
        assertThat(owner.queryForObject("SELECT purchased_available FROM billing_accounts WHERE tenant_id=?", Long.class, agency)).isEqualTo(700);

        // 激活码不限个数；同一请求重复提交返回同一批码。
        String batch = "{\"requestKey\":\"codes-0001\",\"planCode\":\"" + planCode + "\",\"quantity\":2}";
        String first = call(agentA, agency, post("/api/partner/codes"), batch).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2)).andReturn().getResponse().getContentAsString();
        // 重复提交返回同一批码（只比码：第一次返回的时间带纳秒，再读出来是数据库的微秒）。
        call(agentA, agency, post("/api/partner/codes"), batch).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].code").value(codeAt(first, 0))).andExpect(jsonPath("$.data[1].code").value(codeAt(first, 1)));
        call(agentA, agency, post("/api/partner/codes"), "{\"requestKey\":\"codes-0002\",\"planCode\":\"" + planCode + "\",\"quantity\":200}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(200));
        call(agentA, agency, post("/api/partner/codes"), "{\"requestKey\":\"codes-0003\",\"planCode\":\"" + planCode + "\",\"quantity\":201}")
                .andExpect(status().isBadRequest());
        call(agentA, agency, get("/api/partner/codes"), null).andExpect(jsonPath("$.data.total").value(202));
        call(agentA, agency, get("/api/partner/me"), null).andExpect(jsonPath("$.data.monthCodes").value(202))
                .andExpect(jsonPath("$.data.plans[0].planCode").value(planCode)).andExpect(jsonPath("$.data.account.available").value(700));
        String codeA = owner.queryForObject("SELECT code FROM billing_redeem_codes WHERE partner_id=? ORDER BY id LIMIT 1", String.class, a);

        // 一方小馆兑换甲的码，归到甲名下；之后再兑换乙的码，会员顺延但归属不变。
        redeem(cafeOwner, cafe, codeA).andExpect(status().isOk()).andExpect(jsonPath("$.data.planName").value("月卡"));
        assertThat(owner.queryForMap("SELECT partner_id, source FROM partner_tenants WHERE tenant_id=?", cafe))
                .containsEntry("partner_id", a).containsEntry("source", "CODE");
        call(agentB, agencyB, post("/api/partner/codes"), "{\"requestKey\":\"codes-b-0001\",\"planCode\":\"" + planCode + "\",\"quantity\":1}").andExpect(status().isOk());
        String codeB = owner.queryForObject("SELECT code FROM billing_redeem_codes WHERE partner_id=?", String.class, b);
        redeem(cafeOwner, cafe, codeB).andExpect(status().isOk());
        assertThat(owner.queryForObject("SELECT partner_id FROM partner_tenants WHERE tenant_id=?", Long.class, cafe)).isEqualTo(a);
        call(agentA, agency, get("/api/partner/tenants"), null).andExpect(jsonPath("$.data.total").value(2));
        call(agentB, agencyB, get("/api/partner/tenants"), null).andExpect(jsonPath("$.data.total").value(0));

        // 只有平台能改归属。服务商流水只记平台发放。
        platform(get("/api/platform/partners/lookup?uid=" + uid(cafeOwner)), null).andExpect(jsonPath("$.data.partnerName").value("甲服务商"));
        platform(put("/api/platform/partners/assignments/" + cafe), "{\"partnerId\":" + b + "}").andExpect(status().isOk());
        assertThat(owner.queryForMap("SELECT partner_id, source FROM partner_tenants WHERE tenant_id=?", cafe))
                .containsEntry("partner_id", b).containsEntry("source", "PLATFORM");
        call(agentA, agency, get("/api/partner/ledger"), null).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("GRANT")).andExpect(jsonPath("$.data.items[0].delta").value(1000))
                .andExpect(jsonPath("$.data.items[0].availableAfter").doesNotExist());
    }

    @Test void onlyPartnerAccountsAndBillingOperatorsReachTheirBackOffices() throws Exception {
        long a = partner("甲服务商", agentA);
        call(shopOwner, shop, get("/api/partner/me"), null).andExpect(status().isForbidden());
        // 一方志站内问身份：不是成员也正常返回。
        call(shopOwner, shop, get("/api/partner/membership"), null).andExpect(status().isOk()).andExpect(jsonPath("$.data.member").value(false));
        call(agentA, agency, get("/api/partner/membership"), null).andExpect(jsonPath("$.data.member").value(true))
                .andExpect(jsonPath("$.data.role").value("OWNER")).andExpect(jsonPath("$.data.partnerName").value("甲服务商"));
        // 平台改的名称就是服务商后台里显示的服务商名。
        platform(put("/api/platform/partners/" + a), "{\"name\":\"甲服务商（嘉兴）\",\"contact\":\"张经理\"}").andExpect(status().isOk());
        call(agentA, agency, put("/api/platform/partners/" + a), "{\"name\":\"自己改\"}").andExpect(status().isForbidden());
        call(agentA, agency, get("/api/partner/me"), null).andExpect(status().isOk()).andExpect(jsonPath("$.data.partner.name").value("甲服务商（嘉兴）"))
                .andExpect(jsonPath("$.data.role").value("OWNER")).andExpect(jsonPath("$.data.permissions.finance").value(true));
        call(agentA, agency, get("/api/platform/partners"), null).andExpect(status().isForbidden());
        // 一个账号只能属于一家服务商；停用后服务商后台不能再用。
        platform(post("/api/platform/partners"), "{\"name\":\"丙\",\"adminUid\":" + uid(agentA) + "}").andExpect(status().isConflict());
        platform(post("/api/platform/partners/" + a + "/status"), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        call(agentA, agency, get("/api/partner/me"), null).andExpect(status().isForbidden());
        call(agentA, agency, get("/api/partner/membership"), null).andExpect(jsonPath("$.data.active").value(false));
    }

    @Test void membersJoinTheTeamAccountByPhoneAndWorkWithinTheirPermissions() throws Exception {
        long a = partner("甲服务商", agentA);
        platform(post("/api/platform/partners/" + a + "/credits"), "{\"requestKey\":\"grant-m-0001\",\"credits\":2000,\"note\":\"线下打款\"}").andExpect(status().isOk());

        // 负责人按手机号加成员：在团队账户里新建一个员工账号。同一个号不能再加；注册了别的商户的号不能加。
        call(agentA, agency, post("/api/partner/team"), member("13800001111", "周晓", MEMBER_PERMS, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].role").value("MEMBER")).andExpect(jsonPath("$.data[1].phone").value("13800001111"))
                .andExpect(jsonPath("$.data[1].permissions.distribute").value(false));
        long staff = owner.queryForObject("SELECT id FROM users WHERE phone='13800001111'", Long.class);
        assertThat(owner.queryForMap("SELECT tenant_id, role FROM users WHERE id=?", staff)).containsEntry("tenant_id", agency).containsEntry("role", "STAFF");
        call(agentA, agency, post("/api/partner/team"), member("13800001111", "周晓", MEMBER_PERMS, null)).andExpect(status().isConflict());
        owner.update("UPDATE users SET phone='13900002222' WHERE id=?", shopOwner);
        call(agentA, agency, post("/api/partner/team"), member("13900002222", "李", MEMBER_PERMS, null)).andExpect(status().isConflict());

        // 成员第一次进来还没有称呼；称呼团队内不能重名。团队账户的烟火大家看到的是同一个数。
        call(staff, agency, get("/api/partner/me"), null).andExpect(jsonPath("$.data.role").value("MEMBER"))
                .andExpect(jsonPath("$.data.displayName").doesNotExist()).andExpect(jsonPath("$.data.userName").value("周晓"))
                .andExpect(jsonPath("$.data.account.available").value(2000)).andExpect(jsonPath("$.data.partner.creditsAvailable").value(2000));
        call(staff, agency, put("/api/partner/me/name"), "{\"displayName\":\"小周\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("小周"));
        call(agentA, agency, put("/api/partner/me/name"), "{\"displayName\":\"小周\"}").andExpect(status().isConflict());
        call(agentA, agency, put("/api/partner/me/name"), "{\"displayName\":\"   \"}").andExpect(status().isBadRequest());

        // 默认能管商户和激活码，不能分烟火、看流水、管团队；团队列表看不到手机号。
        call(staff, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(shopOwner) + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addedByName").value("小周"));
        call(staff, agency, post("/api/partner/codes"), "{\"requestKey\":\"codes-m-0001\",\"planCode\":\"" + planCode + "\",\"quantity\":1}").andExpect(status().isOk());
        call(staff, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"m-transfer-01\",\"credits\":10}").andExpect(status().isForbidden());
        call(staff, agency, get("/api/partner/ledger"), null).andExpect(status().isForbidden());
        call(staff, agency, get("/api/partner/distributions"), null).andExpect(status().isForbidden());
        call(staff, agency, post("/api/partner/team"), member("13800003333", "吴", MEMBER_PERMS, null)).andExpect(status().isForbidden());
        call(staff, agency, get("/api/partner/team"), null).andExpect(status().isOk()).andExpect(jsonPath("$.data[1].phone").doesNotExist())
                .andExpect(jsonPath("$.data[1].tenantsAdded").value(1)).andExpect(jsonPath("$.data[1].codes.generated").value(1));

        // 负责人给分配权限并设每月上限 500：分 300 可以，再分 300 超上限被拒；只看得到自己分出去的。
        String distribute = "{\"tenants\":true,\"codes\":true,\"distribute\":true,\"finance\":false,\"team\":false}";
        call(agentA, agency, put("/api/partner/team/" + staff), "{\"permissions\":" + distribute + ",\"monthlyCap\":500}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].monthlyCap").value(500));
        call(staff, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"m-transfer-02\",\"credits\":300}").andExpect(status().isOk());
        call(staff, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"m-transfer-03\",\"credits\":300}").andExpect(status().isBadRequest());
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"o-transfer-01\",\"credits\":600}").andExpect(status().isOk());
        assertThat(owner.queryForObject("SELECT purchased_available FROM billing_accounts WHERE tenant_id=?", Long.class, shop)).isEqualTo(900);
        call(staff, agency, get("/api/partner/distributions"), null).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].actorName").value("小周"));
        call(agentA, agency, get("/api/partner/distributions"), null).andExpect(jsonPath("$.data.total").value(2));
        call(agentA, agency, get("/api/partner/team"), null).andExpect(jsonPath("$.data[1].distributedThisMonth").value(300));

        // 有团队管理权限的成员：能加人，但只能给出自己也有的权限，分配和财务只有负责人能给；不能改自己、不能动负责人。
        String manager = "{\"tenants\":true,\"codes\":true,\"distribute\":true,\"finance\":false,\"team\":true}";
        call(agentA, agency, put("/api/partner/team/" + staff), "{\"permissions\":" + manager + ",\"monthlyCap\":500}").andExpect(status().isOk());
        call(staff, agency, post("/api/partner/team"), member("13800003333", "吴凡", distribute, null)).andExpect(status().isForbidden());
        call(staff, agency, post("/api/partner/team"), member("13800003333", "吴凡", MEMBER_PERMS, null)).andExpect(status().isOk());
        long second = owner.queryForObject("SELECT id FROM users WHERE phone='13800003333'", Long.class);
        call(staff, agency, put("/api/partner/team/" + second), "{\"permissions\":" + distribute + "}").andExpect(status().isForbidden());
        call(staff, agency, put("/api/partner/team/" + second), "{\"permissions\":" + MEMBER_PERMS + ",\"displayName\":\"小吴\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].displayName").value("小吴"));
        call(staff, agency, put("/api/partner/team/" + staff), "{\"permissions\":" + manager + ",\"monthlyCap\":500}").andExpect(status().isForbidden());
        call(staff, agency, put("/api/partner/team/" + agentA), "{\"permissions\":" + MEMBER_PERMS + "}").andExpect(status().isForbidden());
        call(staff, agency, delete("/api/partner/team/" + agentA), null).andExpect(status().isForbidden());

        // 移除成员：一并移出团队账户（账号停用、手机号释放），服务商后台进不去了。
        call(staff, agency, delete("/api/partner/team/" + second), null).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        assertThat(owner.queryForMap("SELECT status, phone FROM users WHERE id=?", second)).containsEntry("status", "REMOVED").containsEntry("phone", null);
        assertThat(owner.queryForObject("SELECT count(*) FROM partner_members WHERE user_id=?", Long.class, second)).isZero();
        call(staff, agency, get("/api/partner/membership"), null).andExpect(jsonPath("$.data.member").value(true)).andExpect(jsonPath("$.data.role").value("MEMBER"));
    }

    @Test void workbenchAndDashboardShowUsageWithoutWorksAndFlagLowBalance() throws Exception {
        long a = partner("甲服务商", agentA);
        platform(post("/api/platform/partners/" + a + "/credits"), "{\"requestKey\":\"grant-wb-0001\",\"credits\":5000,\"note\":\"线下打款\"}").andExpect(status().isOk());
        call(agentA, agency, post("/api/partner/team"), member("13800004444", "周晓", MEMBER_PERMS, null)).andExpect(status().isOk());
        long staff = owner.queryForObject("SELECT id FROM users WHERE phone='13800004444'", Long.class);
        call(agentA, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(shopOwner) + "}").andExpect(status().isOk());
        call(staff, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(cafeOwner) + "}").andExpect(status().isOk());

        // 冰雪时光：续期后分 1000 烟火，用掉 905，余 95 不到最近充值的 10%；一方小馆：续期了还没用过。
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/renewals"), "{\"requestKey\":\"renew-wb-01\",\"planCode\":\"" + planCode + "\"}").andExpect(status().isOk());
        call(staff, agency, post("/api/partner/tenants/" + cafe + "/renewals"), "{\"requestKey\":\"renew-wb-02\",\"planCode\":\"" + planCode + "\"}").andExpect(status().isOk());
        call(staff, agency, post("/api/partner/tenants/" + cafe + "/renewals"), "{\"requestKey\":\"renew-wb-02\",\"planCode\":\"" + planCode + "\"}").andExpect(status().isOk());
        assertThat(owner.queryForObject("SELECT note FROM billing_redeem_codes WHERE batch_key LIKE 'partner-renew-%' LIMIT 1", String.class)).isEqualTo("直接续期");
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"transfer-wb-01\",\"credits\":1000}").andExpect(status().isOk());
        TenantContext.runAs(shop, () -> {
            String image = billing.reserve("wb-image-" + UUID.randomUUID(), "IMAGE", 5, "图片创作预占");
            billing.capture(image, 5, "wb-capture-image", "图片交付");
            String video = billing.reserve("wb-video-" + UUID.randomUUID(), "VIDEO", 900, "视频创作预占");
            billing.capture(video, 900, "wb-capture-video", "视频交付");
            return null;
        });

        // 商户详情带使用情况和上手进度；没有分配和财务权限的成员看不到分给他的烟火。
        call(agentA, agency, get("/api/partner/tenants/" + shop), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usage.consumed").value(905)).andExpect(jsonPath("$.data.usage.uses").value(2))
                .andExpect(jsonPath("$.data.usage.consumedByCapability.VIDEO").value(900))
                .andExpect(jsonPath("$.data.usage.lastTopUpCredits").value(1000)).andExpect(jsonPath("$.data.usage.lowBalance").value(true))
                .andExpect(jsonPath("$.data.onboarding.activated").value(true)).andExpect(jsonPath("$.data.onboarding.firstUse").value(true))
                .andExpect(jsonPath("$.data.onboarding.stores").value(0)).andExpect(jsonPath("$.data.transfers[0].credits").value(1000));
        call(staff, agency, get("/api/partner/tenants/" + shop), null).andExpect(status().isOk()).andExpect(jsonPath("$.data.transfers").doesNotExist());

        // 待办：名下商户整个服务商共有，成员和负责人看到的一样。
        for (long who : new long[]{agentA, staff})
            call(who, agency, get("/api/partner/workbench"), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.counts.LOW_BALANCE").value(1)).andExpect(jsonPath("$.data.counts.NOT_STARTED").value(1))
                    .andExpect(jsonPath("$.data.counts.EXPIRING").value(0))
                    .andExpect(jsonPath("$.data.todos[?(@.type=='LOW_BALANCE')].tenant.tenantName").value("冰雪时光"));

        // 看板：烟火数字只给有财务权限的人；成员表列出每个人做的事。
        call(agentA, agency, get("/api/partner/dashboard?days=7"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenants").value(2)).andExpect(jsonPath("$.data.consumed").value(905))
                .andExpect(jsonPath("$.data.activeMembers").value(2)).andExpect(jsonPath("$.data.neverActivated").value(0))
                .andExpect(jsonPath("$.data.activeTenants").value(1)).andExpect(jsonPath("$.data.firstUses").value(1))
                .andExpect(jsonPath("$.data.granted").value(5000)).andExpect(jsonPath("$.data.transferred").value(1000))
                .andExpect(jsonPath("$.data.creditsAvailable").value(4000))
                .andExpect(jsonPath("$.data.renewals").value(2)).andExpect(jsonPath("$.data.codesRedeemed").value(2))
                .andExpect(jsonPath("$.data.daily.length()").value(7)).andExpect(jsonPath("$.data.topTenants[0].tenantName").value("冰雪时光"))
                .andExpect(jsonPath("$.data.members[1].name").value("周晓")).andExpect(jsonPath("$.data.members[1].tenantsAdded").value(1))
                .andExpect(jsonPath("$.data.members[1].renewals").value(1)).andExpect(jsonPath("$.data.members[0].distributed").value(1000));
        call(staff, agency, get("/api/partner/dashboard?days=30"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenants").value(2)).andExpect(jsonPath("$.data.granted").doesNotExist())
                .andExpect(jsonPath("$.data.creditsAvailable").doesNotExist()).andExpect(jsonPath("$.data.members[0].distributed").doesNotExist());
        call(agentA, agency, get("/api/partner/dashboard?days=5"), null).andExpect(status().isBadRequest());
    }

    @Test void teamAccountGiftGoesOutFirstAndPendingDistributionsCanBeCompleted() throws Exception {
        long a = partner("甲服务商", agentA);
        call(agentA, agency, post("/api/partner/tenants"), "{\"uid\":" + uid(shopOwner) + "}").andExpect(status().isOk());
        // 团队账户：平台赠送 300、充值 1000。
        TenantContext.runAs(agency, () -> billing.grantCredits(300, "agency-gift-1", operator));
        TenantContext.runAs(agency, () -> billing.creditFromPartner(1000, "agency-topup-1", "测试", operator));
        call(agentA, agency, get("/api/partner/me"), null).andExpect(jsonPath("$.data.account.available").value(1300))
                .andExpect(jsonPath("$.data.account.gift").value(300));

        // 分 500 给商户：先扣赠送 300，再扣充值 200；商户收到赠送 300、充值 200。重复提交不重复扣。
        String toShop = "{\"requestKey\":\"acct-shop-01\",\"credits\":500,\"note\":\"开业支持\"}";
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), toShop).andExpect(status().isOk());
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), toShop).andExpect(status().isOk());
        assertThat(owner.queryForMap("SELECT gift_available, purchased_available FROM billing_accounts WHERE tenant_id=?", shop))
                .containsEntry("gift_available", 300L).containsEntry("purchased_available", 200L);
        assertThat(owner.queryForMap("SELECT gift_available, purchased_available FROM billing_accounts WHERE tenant_id=?", agency))
                .containsEntry("gift_available", 0L).containsEntry("purchased_available", 800L);
        // 同一个请求换数量会被拒绝；超过余额拒绝。
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), toShop.replace("500", "600")).andExpect(status().isBadRequest());
        call(agentA, agency, post("/api/partner/tenants/" + shop + "/credits"), "{\"requestKey\":\"acct-shop-02\",\"credits\":5000}")
                .andExpect(jsonPath("$.code").value(9003));
        call(agentA, agency, get("/api/partner/distributions"), null).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].gift").value(300)).andExpect(jsonPath("$.data.items[0].purchased").value(200))
                .andExpect(jsonPath("$.data.items[0].note").value("开业支持")).andExpect(jsonPath("$.data.items[0].actorName").value("甲老板"))
                .andExpect(jsonPath("$.data.items[0].source").value("ACCOUNT"));

        // 未到账的一笔可以补上：模拟入账前中断（只扣了团队账户、记了分配），补上后到账且不重复扣。
        TenantContext.runAs(agency, () -> billing.debitForPartner(50, "partner-transfer:" + a + ":acct-pending-01", "分配给商户「冰雪时光」", agentA));
        owner.update("INSERT INTO partner_distributions(partner_id, request_key, source, recipient_type, recipient_tenant_id, credits, gift_credits, purchased_credits, actor_user_id) "
                + "VALUES (?, ?, 'ACCOUNT', 'TENANT', ?, 50, 0, 50, ?)", a, "partner-transfer:" + a + ":acct-pending-01", shop, agentA);
        long pending = owner.queryForObject("SELECT id FROM partner_distributions WHERE status='PENDING'", Long.class);
        call(agentA, agency, post("/api/partner/distributions/" + pending + "/retry"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"));
        call(agentA, agency, post("/api/partner/distributions/" + pending + "/retry"), null).andExpect(jsonPath("$.data.status").value("DONE"));
        assertThat(owner.queryForObject("SELECT purchased_available FROM billing_accounts WHERE tenant_id=?", Long.class, shop)).isEqualTo(250);
        assertThat(owner.queryForObject("SELECT purchased_available FROM billing_accounts WHERE tenant_id=?", Long.class, agency)).isEqualTo(750);
        call(agentA, agency, get("/api/partner/dashboard?days=7"), null).andExpect(jsonPath("$.data.transferred").value(550));
    }

    @Test void crmPersistsFollowUpsRejectsStaleEditsAndKeepsPartnersIsolated() throws Exception {
        long a=partner("甲服务商",agentA), b=partner("乙服务商",agentB);
        call(agentA,agency,post("/api/partner/tenants"),"{\"uid\":"+uid(shopOwner)+"}").andExpect(status().isOk());
        String profile="{\"contactName\":\"李经理\",\"contactPhone\":\"13800000000\",\"industry\":\"餐饮\",\"region\":\"杭州\",\"stage\":\"ACTIVE\",\"version\":0}";
        call(agentA,agency,put("/api/partner/tenants/"+shop+"/profile"),profile).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        call(agentA,agency,put("/api/partner/tenants/"+shop+"/profile"),profile).andExpect(status().isConflict());
        call(agentB,agencyB,get("/api/partner/tenants/"+shop+"/crm"),null).andExpect(status().isNotFound());
        String next=java.time.Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String follow="{\"requestKey\":\"follow-0001\",\"channel\":\"WECHAT\",\"content\":\"已完成首次培训，预约回访\",\"stage\":\"ACTIVE\",\"nextFollowUpAt\":\""+next+"\",\"version\":1}";
        call(agentA,agency,post("/api/partner/tenants/"+shop+"/follow-ups"),follow).andExpect(status().isOk()).andExpect(jsonPath("$.data.followUps.total").value(1)).andExpect(jsonPath("$.data.profile.version").value(2));
        call(agentA,agency,post("/api/partner/tenants/"+shop+"/follow-ups"),follow).andExpect(status().isOk()).andExpect(jsonPath("$.data.followUps.total").value(1));
        call(agentA,agency,get("/api/partner/follow-ups/due"),null).andExpect(jsonPath("$.data[0].tenantId").value(shop));
        // 成员看商户档案要有「商户」权限。
        call(agentA,agency,post("/api/partner/team"),member("13800005555","周晓","{\"tenants\":false,\"codes\":true,\"distribute\":false,\"finance\":false,\"team\":false}",null)).andExpect(status().isOk());
        long staff=owner.queryForObject("SELECT id FROM users WHERE phone='13800005555'",Long.class);
        call(staff,agency,get("/api/partner/tenants/"+shop+"/crm"),null).andExpect(status().isForbidden());
        call(agentA,agency,put("/api/partner/team/"+staff),"{\"permissions\":"+MEMBER_PERMS+"}").andExpect(status().isOk());
        call(staff,agency,get("/api/partner/tenants/"+shop+"/crm"),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.profile.contactName").value("李经理"));
        platform(put("/api/platform/partners/assignments/"+shop),"{\"partnerId\":"+b+"}").andExpect(status().isOk());
        call(agentB,agencyB,get("/api/partner/tenants/"+shop+"/crm"),null).andExpect(jsonPath("$.data.profile.version").value(0)).andExpect(jsonPath("$.data.followUps.total").value(0));
        assertThat(owner.queryForObject("SELECT count(*) FROM partner_follow_ups WHERE partner_id=?",Long.class,a)).isEqualTo(1);
    }

    @Test void rolesEnforceFinanceReviewerAndSupportBoundariesAndAuditChanges() throws Exception {
        platform(put("/api/platform/security/roles"),"{\"uid\":"+uid(shopOwner)+",\"roles\":[\"REVIEWER\"]}").andExpect(status().isOk());
        platform(put("/api/platform/security/roles"),"{\"uid\":"+uid(cafeOwner)+",\"roles\":[\"FINANCE\"]}").andExpect(status().isOk());
        platform(put("/api/platform/security/roles"),"{\"uid\":"+uid(bakeryOwner)+",\"roles\":[\"SUPPORT\"]}").andExpect(status().isOk());
        call(shopOwner,shop,get("/api/platform/information"),null).andExpect(status().isOk());
        call(shopOwner,shop,get("/api/billing/operations/orders"),null).andExpect(status().isForbidden());
        call(bakeryOwner,bakery,get("/api/platform/users"),null).andExpect(status().isOk());
        call(bakeryOwner,bakery,post("/api/platform/users/"+shopOwner+"/credits"),"{\"requestKey\":\"support-gift\",\"credits\":100,\"note\":\"客服赠送测试\"}").andExpect(status().isForbidden());
        call(cafeOwner,cafe,get("/api/platform/users"),null).andExpect(status().isForbidden());
        call(cafeOwner,cafe,get("/api/billing/operations/orders"),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.page.total").value(0));
        call(cafeOwner,cafe,put("/api/platform/security/roles"),"{\"uid\":"+uid(cafeOwner)+",\"roles\":[\"ADMIN\"]}").andExpect(status().isForbidden());
        platform(get("/api/platform/security/operations"),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(5));
        platform(put("/api/platform/security/roles"),"{\"uid\":"+uid(shopOwner)+",\"roles\":[]}").andExpect(status().isOk());
        call(shopOwner,shop,get("/api/platform/information"),null).andExpect(status().isForbidden());
    }

    @Test void orderReportMergesTenantScopesAndFinanceCanConfirmOnlyVerifiedManualReceipts() throws Exception {
        platform(put("/api/platform/security/roles"),"{\"uid\":"+uid(cafeOwner)+",\"roles\":[\"FINANCE\"]}").andExpect(status().isOk());
        String manual=UUID.randomUUID().toString(),expired=UUID.randomUUID().toString(),online=UUID.randomUUID().toString();
        owner.update("INSERT INTO billing_orders(id,tenant_id,request_key,kind,amount_minor,credits,payment_method,expires_at,created_at) VALUES (?,?,'manual-order','TOPUP',50000,2500,'MANUAL',now()+interval '1 day',now()-interval '1 minute')",manual,shop);
        owner.update("INSERT INTO billing_orders(id,tenant_id,request_key,kind,amount_minor,credits,payment_method,expires_at,created_at) VALUES (?,?,'expired-order','TOPUP',100000,5000,'MANUAL',now()-interval '1 minute',now()-interval '2 minutes')",expired,bakery);
        owner.update("INSERT INTO billing_orders(id,tenant_id,request_key,kind,amount_minor,credits,payment_method,expires_at,created_at) VALUES (?,?,'online-order','TOPUP',50000,2500,'WECHAT',now()+interval '1 day',now())",online,cafe);
        call(cafeOwner,cafe,get("/api/billing/operations/orders?size=1&page=1"),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.page.total").value(3)).andExpect(jsonPath("$.data.page.items[0].id").value(manual)).andExpect(jsonPath("$.data.pendingCount").value(2)).andExpect(jsonPath("$.data.pendingMinor").value(100000));
        call(cafeOwner,cafe,get("/api/billing/operations/orders?state=EXPIRED"),null).andExpect(jsonPath("$.data.page.total").value(1)).andExpect(jsonPath("$.data.page.items[0].id").value(expired));
        call(cafeOwner,cafe,get("/api/billing/operations/orders").param("keyword","冰雪时光"),null).andExpect(jsonPath("$.data.page.total").value(1));
        String receipt="{\"receivedAmountMinor\":50000,\"receiptReference\":\"bank-confirmed-0001\"}";
        call(cafeOwner,cafe,post("/api/billing/operations/tenants/"+shop+"/orders/"+manual+"/confirm"),receipt).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PAID"));
        call(cafeOwner,cafe,post("/api/billing/operations/tenants/"+shop+"/orders/"+manual+"/confirm"),receipt).andExpect(status().isOk());
        call(cafeOwner,cafe,post("/api/billing/operations/tenants/"+cafe+"/orders/"+online+"/confirm"),receipt).andExpect(status().isBadRequest());
        assertThat(owner.queryForObject("SELECT purchased_available FROM billing_accounts WHERE tenant_id=?",Long.class,shop)).isEqualTo(2500);
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_ledger WHERE type='PURCHASE'",Long.class)).isEqualTo(1);
        call(cafeOwner,cafe,get("/api/billing/operations/orders?state=PAID&paymentMethod=MANUAL"),null).andExpect(jsonPath("$.data.paidMinor").value(50000)).andExpect(jsonPath("$.data.page.total").value(1));
        assertThat(TenantContext.get()).isNull();
    }

    private static String member(String phone, String name, String perms, Long cap) {
        return "{\"phone\":\"" + phone + "\",\"name\":\"" + name + "\",\"permissions\":" + perms + (cap == null ? "" : ",\"monthlyCap\":" + cap) + "}";
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
