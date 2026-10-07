package com.wuyao.growth.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账户体系：管理员与店员、店员的门店范围、停用与移除，以及声音样本归商户后按门店开放。
 * 全部经真实的 HTTP 接口、登录流程和数据库。
 */
@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN",
        // 本机 .env 不能把这组测试指向真实的模型或存储桶。
        "growth.ai.writer.url=", "growth.voice.sample-storage=minio"})
@AutoConfigureMockMvc
@Testcontainers
class StaffAccountsIntegrationTest {
    private static final String STAFF_PHONE = "13900000002";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("staff_test").withUsername("growth_owner").withPassword("test_owner_password");

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
    @Autowired JwtService jwt;
    /** Never the real provider: a session can only go on air with a voice that is usable. */
    @MockitoBean com.wuyao.growth.voice.VoiceProvider voiceProvider;
    private final ObjectMapper json = new ObjectMapper();
    JdbcTemplate owner;
    Long tenantA;
    Long tenantB;
    Long bossA;
    String boss;
    String otherBoss;
    long west;
    long east;

    @BeforeEach
    void setUp() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE sms_codes, tenants RESTART IDENTITY CASCADE");
        tenantA = owner.queryForObject("INSERT INTO tenants(name) VALUES ('甲商户') RETURNING id", Long.class);
        tenantB = owner.queryForObject("INSERT INTO tenants(name) VALUES ('乙商户') RETURNING id", Long.class);
        // 没有写角色的旧账号就是管理员。
        bossA = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13900000001', '甲管理员') RETURNING id", Long.class, tenantA);
        Long bossB = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13900000009', '乙管理员') RETURNING id", Long.class, tenantB);
        boss = jwt.issueAccessToken(bossA, tenantA, "13900000001", 0);
        otherBoss = jwt.issueAccessToken(bossB, tenantB, "13900000009", 0);
        west = api(post("/api/stores"), boss, Map.of("name", "城西店")).path("id").asLong();
        east = api(post("/api/stores"), boss, Map.of("name", "城东店")).path("id").asLong();
        when(voiceProvider.configured()).thenReturn(true);
        when(voiceProvider.code()).thenReturn("stub-voice");
        when(voiceProvider.builtInVoices()).thenReturn(List.of("voice-a"));
        when(voiceProvider.supportsVoice(anyString())).thenReturn(true);
    }

    @Test
    void theOwnerEntersEveryStoreWithoutBeingListedAnywhereAndSeesWhoBelongsToTheMerchant() throws Exception {
        assertThat(owner.queryForObject("SELECT count(*) FROM store_members", Long.class)).as("管理员不需要门店成员记录").isZero();
        assertThat(api(get("/api/auth/me"), boss, null).path("role").asText()).isEqualTo("OWNER");
        assertThat(api(get("/api/stores"), boss, null)).extracting(store -> store.path("name").asText()).containsExactly("城西店", "城东店");
        api(post("/api/stores/" + east + "/products"), boss, product("蜂蜜"));

        JsonNode members = api(get("/api/team/members"), boss, null);
        assertThat(members).hasSize(1);
        assertThat(members.get(0).path("role").asText()).isEqualTo("OWNER");
        assertThat(members.get(0).path("allStores").asBoolean()).isTrue();
        assertThat(members.get(0).path("self").asBoolean()).isTrue();
        // 另一个商户看不到这边的任何人。
        assertThat(api(get("/api/team/members"), otherBoss, null)).extracting(member -> member.path("name").asText()).containsExactly("乙管理员");
    }

    @Test
    void aClerkAddedByPhoneLandsInTheOwnersMerchantAndCanOnlyWorkInTheStoresGivenToThem() throws Exception {
        JsonNode added = api(post("/api/team/members"), boss, Map.of("phone", STAFF_PHONE, "name", " 小李 ", "storeIds", List.of(west)));
        long clerkId = added.path("id").asLong();
        assertThat(added.path("name").asText()).isEqualTo("小李");
        assertThat(added.path("role").asText()).isEqualTo("STAFF");
        assertThat(added.path("allStores").asBoolean()).isFalse();
        assertThat(added.path("storeIds")).extracting(JsonNode::asLong).containsExactly(west);

        // 他用自己的手机号验证码登录：进的是管理员的商户，不会新开一个商户。
        JsonNode session = login(STAFF_PHONE);
        String clerk = session.path("accessToken").asText();
        assertThat(session.path("user").path("tenantId").asLong()).isEqualTo(tenantA);
        assertThat(session.path("user").path("userId").asLong()).isEqualTo(clerkId);
        assertThat(session.path("user").path("role").asText()).isEqualTo("STAFF");
        assertThat(owner.queryForObject("SELECT count(*) FROM tenants", Long.class)).isEqualTo(2);

        // 只看得到、进得去分配给他的门店。
        assertThat(api(get("/api/stores"), clerk, null)).extracting(store -> store.path("id").asLong()).containsExactly(west);
        long honey = api(post("/api/stores/" + west + "/products"), clerk, product("蜂蜜")).path("id").asLong();
        assertThat(api(get("/api/products/" + honey), clerk, null).path("name").asText()).isEqualTo("蜂蜜");
        refused(get("/api/stores/" + east), clerk, null, 403, 1403);
        refused(post("/api/stores/" + east + "/products"), clerk, product("越界"), 403, 1403);
        refused(get("/api/stores/" + east + "/knowledge-sets"), clerk, null, 403, 1403);
        refused(post("/api/stores/" + east + "/live-sessions"), clerk, Map.of("name", "越界场次"), 403, 1403);

        // 商户级的事只有管理员能做；本店的资料店员可以改，但门店归哪个品牌不由他定。
        long tea = api(post("/api/brands"), boss, Map.of("name", "青岚茶事")).path("id").asLong();
        long grill = api(post("/api/brands"), boss, Map.of("name", "王记烧烤")).path("id").asLong();
        refused(post("/api/stores"), clerk, Map.of("name", "私自开店"), 403, 1403);
        refused(delete("/api/stores/" + west), clerk, null, 403, 1403);
        refused(post("/api/brands"), clerk, Map.of("name", "私自建品牌"), 403, 1403);
        refused(put("/api/brands/" + tea), clerk, Map.of("name", "改名", "version", 0), 403, 1403);
        refused(post("/api/brands/" + grill + "/default"), clerk, null, 403, 1403);
        refused(delete("/api/brands/" + grill), clerk, null, 403, 1403);
        refused(get("/api/team/members"), clerk, null, 403, 1403);
        refused(post("/api/team/members"), clerk, Map.of("phone", "13900000003", "name", "同伙", "storeIds", List.of(west)), 403, 1403);
        refused(put("/api/team/members/" + clerkId), clerk, Map.of("name", "小李", "storeIds", List.of(west, east)), 403, 1403);
        assertThat(api(get("/api/brands"), clerk, null)).as("品牌资料所有成员都能看").hasSize(2);
        JsonNode edited = api(put("/api/stores/" + west), clerk, Map.of("name", "城西店", "businessHours", "每日 9:00–21:00", "version", 0));
        assertThat(edited.path("businessHours").asText()).isEqualTo("每日 9:00–21:00");
        assertThat(edited.path("brandId").asLong()).isEqualTo(tea);
        refused(put("/api/stores/" + west), clerk, Map.of("name", "城西店", "version", edited.path("version").asLong(), "brandId", grill), 403, 1403);

        // 管理员调整范围后立即生效：加上城东店、去掉城西店。
        JsonNode moved = api(put("/api/team/members/" + clerkId), boss, Map.of("name", "小李", "storeIds", List.of(east)));
        assertThat(moved.path("storeIds")).extracting(JsonNode::asLong).containsExactly(east);
        refused(get("/api/products/" + honey), clerk, null, 403, 1403);
        assertThat(api(get("/api/stores"), clerk, null)).extracting(store -> store.path("id").asLong()).containsExactly(east);
        assertThat(api(get("/api/team/members"), boss, null)).extracting(member -> member.path("name").asText()).containsExactly("甲管理员", "小李");
    }

    @Test
    void aPhoneBelongsToOneMerchantAndTheOwnersOwnAccountIsNotEditedHere() throws Exception {
        long clerkId = api(post("/api/team/members"), boss, Map.of("phone", STAFF_PHONE, "name", "小李", "storeIds", List.of())).path("id").asLong();

        assertThat(refused(post("/api/team/members"), boss, Map.of("phone", STAFF_PHONE, "name", "重复", "storeIds", List.of()), 409, 1409))
                .contains("已经是本商户的成员");
        assertThat(refused(post("/api/team/members"), boss, Map.of("phone", "13900000009", "name", "乙管理员", "storeIds", List.of()), 409, 1409))
                .contains("已经注册了其他商户");
        assertThat(refused(post("/api/team/members"), otherBoss, Map.of("phone", STAFF_PHONE, "name", "挖人", "storeIds", List.of()), 409, 1409))
                .contains("已经注册了其他商户");
        refused(post("/api/team/members"), boss, Map.of("phone", "12345", "name", "号码不对", "storeIds", List.of()), 400, 1400);
        refused(post("/api/team/members"), boss, Map.of("phone", "13900000004", "name", " ", "storeIds", List.of()), 400, 1400);
        // 门店必须是本商户营业中的门店；失败时人也不会被加进来。
        long foreignStore = api(post("/api/stores"), otherBoss, Map.of("name", "乙的门店")).path("id").asLong();
        refused(post("/api/team/members"), boss, Map.of("phone", "13900000004", "name", "小王", "storeIds", List.of(foreignStore)), 400, 1400);
        assertThat(owner.queryForObject("SELECT count(*) FROM users WHERE phone = '13900000004'", Long.class)).isZero();

        // 别的商户够不着这个店员；管理员自己的账号不在这里改。
        refused(put("/api/team/members/" + clerkId), otherBoss, Map.of("name", "改名", "storeIds", List.of()), 404, 1404);
        refused(post("/api/team/members/" + clerkId + "/disable"), otherBoss, null, 404, 1404);
        refused(delete("/api/team/members/" + clerkId), otherBoss, null, 404, 1404);
        refused(put("/api/team/members/" + bossA), boss, Map.of("name", "改自己", "storeIds", List.of()), 400, 1400);
        refused(post("/api/team/members/" + bossA + "/disable"), boss, null, 400, 1400);
        refused(delete("/api/team/members/" + bossA), boss, null, 400, 1400);
        assertThat(owner.queryForObject("SELECT status || ':' || role FROM users WHERE id = ?", String.class, bossA)).isEqualTo("ACTIVE:OWNER");
    }

    @Test
    void disablingAClerkCutsOffEverySessionAtOnceAndRemovingOneFreesThePhone() throws Exception {
        long clerkId = api(post("/api/team/members"), boss, Map.of("phone", STAFF_PHONE, "name", "小李", "storeIds", List.of(west))).path("id").asLong();
        JsonNode session = login(STAFF_PHONE);
        String clerk = session.path("accessToken").asText();
        api(get("/api/stores/" + west), clerk, null);

        assertThat(api(post("/api/team/members/" + clerkId + "/disable"), boss, null).path("status").asText()).isEqualTo("DISABLED");
        // 已经签发的访问令牌、刷新令牌和新的登录都不再有效。
        refused(get("/api/stores/" + west), clerk, null, 401, 1401);
        refused(post("/api/auth/refresh"), null, Map.of("refreshToken", session.path("refreshToken").asText()), 401, 2005);
        seedCode(STAFF_PHONE, "222222");
        assertThat(refused(post("/api/auth/login"), null, Map.of("phone", STAFF_PHONE, "code", "222222"), 401, 1401)).contains("已停用");
        assertThat(refused(post("/api/team/members"), boss, Map.of("phone", STAFF_PHONE, "name", "小李", "storeIds", List.of()), 409, 1409))
                .contains("已停用");

        // 重新启用后要重新登录，原来的门店范围还在。
        assertThat(api(post("/api/team/members/" + clerkId + "/enable"), boss, null).path("storeIds")).extracting(JsonNode::asLong).containsExactly(west);
        refused(get("/api/stores/" + west), clerk, null, 401, 1401);
        String again = login(STAFF_PHONE).path("accessToken").asText();
        api(get("/api/stores/" + west), again, null);

        // 移除：他建的资料留着，账号进不来，门店范围清空，手机号可以再用。
        long honey = api(post("/api/stores/" + west + "/products"), again, product("蜂蜜")).path("id").asLong();
        api(delete("/api/team/members/" + clerkId), boss, null);
        refused(get("/api/stores/" + west), again, null, 401, 1401);
        assertThat(api(get("/api/team/members"), boss, null)).hasSize(1);
        assertThat(api(get("/api/products/" + honey), boss, null).path("name").asText()).isEqualTo("蜂蜜");
        assertThat(owner.queryForObject("SELECT count(*) FROM store_members WHERE user_id = ?", Long.class, clerkId)).isZero();
        assertThat(owner.queryForMap("SELECT status, phone FROM users WHERE id = ?", clerkId))
                .containsEntry("status", "REMOVED").containsEntry("phone", null);
        refused(put("/api/team/members/" + clerkId), boss, Map.of("name", "小李", "storeIds", List.of(west)), 404, 1404);

        // 同一个手机号现在是个没注册过的号码：自己登录就是新开一个商户，当管理员。
        JsonNode fresh = login(STAFF_PHONE);
        assertThat(fresh.path("user").path("role").asText()).isEqualTo("OWNER");
        assertThat(fresh.path("user").path("tenantId").asLong()).isNotIn(tenantA, tenantB);
        assertThat(api(get("/api/stores"), fresh.path("accessToken").asText(), null)).isEmpty();
    }

    @Test
    void aVoiceBelongsToTheMerchantAndSpeaksOnlyInTheStoresTheOwnerOpensItTo() throws Exception {
        api(post("/api/team/members"), boss, Map.of("phone", STAFF_PHONE, "name", "小李", "storeIds", List.of(east)));
        String clerk = login(STAFF_PHONE).path("accessToken").asText();
        long voice = sample(tenantA, west);
        String host = "sample:" + voice;
        long westProduct = api(post("/api/stores/" + west + "/products"), boss, product("蜂蜜")).path("id").asLong();
        long eastProduct = api(post("/api/stores/" + east + "/products"), boss, product("蜂蜜")).path("id").asLong();

        // 起初只开放给上传它的城西店：城东店看不到，也不能用它开播。
        assertThat(api(get("/api/stores/" + west + "/voice-samples"), boss, null).get(0).path("storeIds")).extracting(JsonNode::asLong).containsExactly(west);
        assertThat(api(get("/api/stores/" + east + "/voice-samples"), clerk, null)).isEmpty();
        refused(get("/api/voice-samples/" + voice + "/download-url"), clerk, null, 403, 1403);
        long eastSession = api(post("/api/stores/" + east + "/live-sessions"), clerk, liveSession(eastProduct, host)).path("id").asLong();
        assertThat(refused(post("/api/live-sessions/" + eastSession + "/start"), clerk, null, 400, 1400)).contains("当前门店");

        // 上传、改名、删除、调整范围都只有管理员能做，哪怕是能用这个声音的店员。
        refused(post("/api/stores/" + east + "/voice-samples/upload-url"), clerk, Map.of("name", "我的声音", "mimeType", "audio/wav", "consent", true), 403, 1403);
        refused(put("/api/voice-samples/" + voice + "/stores"), clerk, Map.of("storeIds", List.of(west, east)), 403, 1403);

        JsonNode opened = api(put("/api/voice-samples/" + voice + "/stores"), boss, Map.of("storeIds", List.of(east, west)));
        assertThat(opened.path("storeIds")).extracting(JsonNode::asLong).containsExactly(west, east);
        assertThat(owner.queryForObject("SELECT granted_by FROM voice_sample_stores WHERE sample_id = ? AND store_id = ?", Long.class, voice, east))
                .as("记下是谁把它开放给这家店的").isEqualTo(bossA);
        assertThat(api(get("/api/stores/" + east + "/voice-samples"), clerk, null)).extracting(sample -> sample.path("id").asLong()).containsExactly(voice);
        refused(patch("/api/voice-samples/" + voice), clerk, Map.of("name", "改名"), 403, 1403);
        refused(delete("/api/voice-samples/" + voice), clerk, null, 403, 1403);
        api(post("/api/live-sessions/" + eastSession + "/start"), clerk, null);

        // 城东店正在用它直播：不能把城东店去掉，也不能删；城西店没在用，可以去掉。
        assertThat(refused(put("/api/voice-samples/" + voice + "/stores"), boss, Map.of("storeIds", List.of(west)), 400, 1400)).contains("越界不了的场次");
        assertThat(refused(delete("/api/voice-samples/" + voice), boss, null, 400, 1400)).contains("越界不了的场次");
        assertThat(refused(put("/api/voice-samples/" + voice + "/stores"), boss, Map.of("storeIds", List.of()), 400, 1400)).contains("至少保留一家门店");
        api(put("/api/voice-samples/" + voice + "/stores"), boss, Map.of("storeIds", List.of(east)));
        long westSession = api(post("/api/stores/" + west + "/live-sessions"), boss, liveSession(westProduct, host)).path("id").asLong();
        assertThat(refused(post("/api/live-sessions/" + westSession + "/start"), boss, null, 400, 1400)).contains("当前门店");

        // 别的商户的门店开放不过去：接口拒绝，直接改库也被外键挡住。
        long foreignStore = api(post("/api/stores"), otherBoss, Map.of("name", "乙的门店")).path("id").asLong();
        refused(put("/api/voice-samples/" + voice + "/stores"), boss, Map.of("storeIds", List.of(east, foreignStore)), 404, 1404);
        refused(put("/api/voice-samples/" + voice + "/stores"), otherBoss, Map.of("storeIds", List.of(foreignStore)), 404, 1404);
        assertThatThrownBy(() -> owner.update("INSERT INTO voice_sample_stores(tenant_id,sample_id,store_id) VALUES (?,?,?)", tenantB, voice, foreignStore))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_voice_sample_stores_sample");
        assertThat(owner.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class "
                + "WHERE oid = 'public.voice_sample_stores'::regclass", Boolean.class)).isTrue();
    }

    @Test
    void theMigrationKeepsEveryExistingAccountAnOwnerAndEveryVoiceInTheStoreItWasUploadedFrom() throws Exception {
        String migration = new ClassPathResource("db/migration/V41__staff_accounts.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try {
                // 迁移由不能绕过行级安全的表属主执行，和生产一致。
                statement.execute("CREATE ROLE staff_v41_owner NOLOGIN NOSUPERUSER NOBYPASSRLS");
                statement.execute("CREATE SCHEMA staff_v41_upgrade AUTHORIZATION staff_v41_owner");
                statement.execute("SET ROLE staff_v41_owner");
                statement.execute("SET search_path TO staff_v41_upgrade");
                statement.execute("CREATE TABLE tenants(id BIGINT PRIMARY KEY)");
                statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL)");
                statement.execute("CREATE TABLE stores(id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL)");
                statement.execute("CREATE TABLE store_members(id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, store_id BIGINT NOT NULL, "
                        + "user_id BIGINT NOT NULL, role VARCHAR(30) NOT NULL DEFAULT 'STAFF')");
                statement.execute("CREATE TABLE voice_samples(id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, store_id BIGINT NOT NULL, "
                        + "status VARCHAR(30) NOT NULL, consent_by BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT '2026-01-01T00:00:00Z')");
                for (String table : List.of("stores", "store_members", "voice_samples")) {
                    statement.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
                    statement.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
                    statement.execute("CREATE POLICY tenant_isolation ON " + table + " USING "
                            + "(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint) "
                            + "WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)");
                }
                statement.execute("INSERT INTO tenants VALUES (1), (2)");
                statement.execute("INSERT INTO users VALUES (11, 1), (12, 1), (21, 2)");
                for (int tenant : List.of(1, 2)) {
                    statement.execute("SELECT set_config('app.tenant_id','" + tenant + "',false)");
                    int base = tenant * 100;
                    statement.execute("INSERT INTO stores VALUES (" + (base + 1) + "," + tenant + "), (" + (base + 2) + "," + tenant + ")");
                    statement.execute("INSERT INTO store_members(id,tenant_id,store_id,user_id,role) VALUES "
                            + "(" + (base + 1) + "," + tenant + "," + (base + 1) + "," + (tenant * 10 + 1) + ",'OWNER'),"
                            + "(" + (base + 2) + "," + tenant + "," + (base + 2) + "," + (tenant * 10 + 1) + ",'OWNER')");
                    statement.execute("INSERT INTO voice_samples(id,tenant_id,store_id,status,consent_by) VALUES "
                            + "(" + (base + 1) + "," + tenant + "," + (base + 1) + ",'READY'," + (tenant * 10 + 1) + "),"
                            + "(" + (base + 2) + "," + tenant + "," + (base + 2) + ",'UPLOADED'," + (tenant * 10 + 1) + "),"
                            + "(" + (base + 3) + "," + tenant + "," + (base + 1) + ",'DELETED'," + (tenant * 10 + 1) + ")");
                }
                statement.execute("SELECT set_config('app.tenant_id','999',false)");

                statement.execute(migration);

                try (var rows = statement.executeQuery("SELECT current_setting('app.tenant_id'), "
                        + "(SELECT string_agg(id || ':' || role, ',' ORDER BY id) FROM users), "
                        + "(SELECT count(*) FROM information_schema.columns WHERE table_schema='staff_v41_upgrade' "
                        + " AND table_name='store_members' AND column_name='role')")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).as("迁移不改动会话原有的租户设置").isEqualTo("999");
                    assertThat(rows.getString(2)).isEqualTo("11:OWNER,12:OWNER,21:OWNER");
                    assertThat(rows.getInt(3)).isZero();
                }
                for (int tenant : List.of(1, 2)) {
                    statement.execute("SELECT set_config('app.tenant_id','" + tenant + "',false)");
                    int base = tenant * 100;
                    try (var rows = statement.executeQuery("SELECT (SELECT count(*) FROM store_members), "
                            + "(SELECT string_agg(sample_id || '>' || store_id || ' by ' || granted_by, ',' ORDER BY sample_id) FROM voice_sample_stores), "
                            + "(SELECT bool_and(created_at = '2026-01-01T00:00:00Z'::timestamptz) FROM voice_sample_stores)")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).as("管理员不再需要门店成员记录").isZero();
                        assertThat(rows.getString(2)).as("每个未删除的样本只开放给上传时所在的门店")
                                .isEqualTo((base + 1) + ">" + (base + 1) + " by " + (tenant * 10 + 1) + ","
                                        + (base + 2) + ">" + (base + 2) + " by " + (tenant * 10 + 1));
                        assertThat(rows.getBoolean(3)).isTrue();
                    }
                }
                try (var rows = statement.executeQuery("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class "
                        + "WHERE oid = 'staff_v41_upgrade.voice_sample_stores'::regclass")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isTrue();
                }
            } finally {
                statement.execute("RESET ROLE");
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA IF EXISTS staff_v41_upgrade CASCADE");
                statement.execute("DROP ROLE IF EXISTS staff_v41_owner");
            }
        }
    }

    private static Map<String, Object> product(String name) {
        return Map.of("name", name, "type", "PHYSICAL", "category", "食品", "price", 69, "saleUnit", "罐");
    }

    private static Map<String, Object> liveSession(long product, String hostVoice) {
        return Map.of("name", "越界不了的场次", "productIds", List.of(product),
                "config", Map.of("voiceRoles", List.of(Map.of("id", hostVoice, "role", "host"))));
    }

    /** A cloned voice that is ready, as the upload and clone flow would leave it: open to the store it came from. */
    private long sample(Long tenant, long store) {
        Long id = owner.queryForObject("INSERT INTO voice_samples(tenant_id,store_id,name,storage_key,mime_type,status,"
                + "provider_code,provider_voice_id,consent_at,consent_by,consent_text) VALUES (?,?,'店主声音',?,'audio/wav','READY',"
                + "'stub-voice','voice-clone-1',now(),?,'同意') RETURNING id", Long.class, tenant, store, "t" + tenant + "/voice-samples/x", bossA);
        owner.update("INSERT INTO voice_sample_stores(tenant_id,sample_id,store_id,granted_by) VALUES (?,?,?,?)", tenant, id, store, bossA);
        return id;
    }

    /** Signs in the way a person does: the code they were sent, then the login request. */
    private JsonNode login(String phone) throws Exception {
        seedCode(phone, "111111");
        return api(post("/api/auth/login"), null, Map.of("phone", phone, "code", "111111"));
    }

    private void seedCode(String phone, String code) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        owner.update("INSERT INTO sms_codes(phone,code_hash,expires_at) VALUES (?,?,now()+interval '5 minutes')", phone, hash);
    }

    private JsonNode api(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        var response = mvc.perform(prepared(request, token, body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    /** @return the message the caller is shown */
    private String refused(MockHttpServletRequestBuilder request, String token, Object body, int http, int code) throws Exception {
        var response = mvc.perform(prepared(request, token, body)).andExpect(status().is(http))
                .andExpect(jsonPath("$.code").value(code)).andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("message").asText();
    }

    private MockHttpServletRequestBuilder prepared(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(new LinkedHashMap<>((Map<?, ?>) body)));
        return request;
    }
}
