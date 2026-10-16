package com.wuyao.growth.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.live.player.LivePlayerService;
import com.wuyao.growth.live.player.PlayerTokenService;
import com.wuyao.growth.voice.VoiceProvider;
import com.wuyao.growth.voice.VoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.crypto.bcrypt.BCrypt.gensalt;
import static org.springframework.security.crypto.bcrypt.BCrypt.hashpw;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 系统设置里的账号注销：店员只注销自己，商户里最后一个管理员注销时整个商户一起关闭。
 * 全部经真实的 HTTP 接口、登录流程和数据库。
 */
@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN",
        // 本机 .env 不能把这组测试指向真实的模型或存储桶。
        "growth.ai.writer.url=", "growth.voice.sample-storage=minio"})
@AutoConfigureMockMvc
@Testcontainers
class AccountClosureIntegrationTest {
    private static final String BOSS = "13900000021";
    private static final String CLERK = "13900000022";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("closure_test").withUsername("growth_owner").withPassword("test_owner_password");

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
    @Autowired LivePlayerService players;
    @Autowired PlayerTokenService playerTokens;
    @Autowired VoiceService voices;
    @MockitoBean VoiceProvider voiceProvider;
    @MockitoBean ObjectStorage storage;
    private final ObjectMapper json = new ObjectMapper();
    JdbcTemplate owner;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE sms_codes, tenants RESTART IDENTITY CASCADE");
    }

    @Test
    void aClerkClosesOnlyTheirOwnAccountAndCanSignUpAgainLater() throws Exception {
        String boss = login(BOSS).path("accessToken").asText();
        api(post("/api/team/members"), boss, Map.of("phone", CLERK, "name", "小李", "storeIds", List.of()));
        JsonNode clerkSession = login(CLERK);
        String clerk = clerkSession.path("accessToken").asText();
        Long clerkId = clerkSession.path("user").path("userId").asLong();
        Long tenant = clerkSession.path("user").path("tenantId").asLong();
        owner.update("INSERT INTO real_name_applications(tenant_id,user_id,legal_name_ciphertext,id_number_ciphertext,"
                + "legal_name_masked,id_number_masked) VALUES (?,?,'\\x00','\\x00','李*','1101**********0011')", tenant, clerkId);

        JsonNode preview = api(get("/api/auth/account/closure"), clerk, null);
        assertThat(preview.path("wholeTenant").asBoolean()).isFalse();
        assertThat(preview.path("otherMembers").asInt()).isZero();
        assertThat(preview.path("byCode").asBoolean()).isTrue();
        assertThat(preview.path("losses").toString()).contains("实名资料");
        assertThat(preview.hasNonNull("blockedReason")).isFalse();

        // 只拿到登录态、收不到短信的人注销不了；没勾选"已了解后果"也不行。
        refused(post("/api/auth/account/closure"), clerk, Map.of("confirmed", true), 400, 1400);
        seedCode(CLERK, "222222");
        refused(post("/api/auth/account/closure"), clerk, Map.of("code", "333333", "confirmed", true), 401, 2003);
        refused(post("/api/auth/account/closure"), clerk, Map.of("code", "222222", "confirmed", false), 400, 1400);
        api(post("/api/auth/account/closure"), clerk, Map.of("code", "222222", "confirmed", true));

        refused(get("/api/auth/account"), clerk, null, 401, 1401);
        refused(post("/api/auth/refresh"), null, Map.of("refreshToken", clerkSession.path("refreshToken").asText()), 401, 2005);
        Map<String, Object> erased = owner.queryForMap("SELECT phone, name, status FROM users WHERE id = ?", clerkId);
        assertThat(erased.get("phone")).isNull();
        assertThat(erased.get("name")).isEqualTo("已注销用户");
        assertThat(erased.get("status")).isEqualTo("REMOVED");
        assertThat(owner.queryForObject("SELECT count(*) FROM real_name_applications", Long.class)).isZero();

        // 管理员和商户都不受影响；手机号已经释放，同一个号码再登录就是一个新商户的管理员。
        assertThat(owner.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenant)).isEqualTo("ACTIVE");
        api(get("/api/auth/account"), boss, null);
        JsonNode again = login(CLERK);
        assertThat(again.path("user").path("tenantId").asLong()).isNotEqualTo(tenant);
        assertThat(again.path("user").path("role").asText()).isEqualTo("OWNER");
    }

    @Test
    void theLastOwnerClosesTheWholeMerchantAndTakesItsPublicPagesDown() throws Exception {
        JsonNode bossSession = login(BOSS);
        String boss = bossSession.path("accessToken").asText();
        Long tenant = bossSession.path("user").path("tenantId").asLong();
        Long bossId = bossSession.path("user").path("userId").asLong();
        api(post("/api/team/members"), boss, Map.of("phone", CLERK, "name", "小李", "storeIds", List.of()));
        String clerk = login(CLERK).path("accessToken").asText();

        Long store = owner.queryForObject("INSERT INTO stores(tenant_id,name,created_by) VALUES (?, '西湖店', ?) RETURNING id",
                Long.class, tenant, bossId);
        owner.update("INSERT INTO products(tenant_id,store_id,name,type,category,price,sale_unit,online_status) "
                + "VALUES (?,?,'龙井','PHYSICAL','茶叶',88,'盒','PUBLISHED')", tenant, store);
        Long info = owner.queryForObject("INSERT INTO information_publications(tenant_id,submitted_by,category,title,"
                + "merchant_name,region,content) VALUES (?,?,'notice','开业','西湖店','杭州','正文') RETURNING id",
                Long.class, tenant, bossId);
        owner.update("INSERT INTO billing_accounts(tenant_id,purchased_available) VALUES (?, 50)", tenant);
        assertThat(publicApi("/api/public/stores/" + store + "/products")).hasSize(1);
        assertThat(publicApi("/api/public/information/" + info).path("title").asText()).isEqualTo("开业");

        JsonNode preview = api(get("/api/auth/account/closure"), boss, null);
        assertThat(preview.path("wholeTenant").asBoolean()).isTrue();
        assertThat(preview.path("otherMembers").asInt()).isEqualTo(1);
        assertThat(preview.path("losses").toString()).contains("50 烟火");

        seedCode(BOSS, "444444");
        api(post("/api/auth/account/closure"), boss, Map.of("code", "444444", "confirmed", true));

        assertThat(owner.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenant)).isEqualTo("CLOSED");
        assertThat(owner.queryForList("SELECT status FROM users WHERE tenant_id = ?", String.class, tenant))
                .containsOnly("REMOVED").hasSize(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM users WHERE tenant_id = ? AND phone IS NOT NULL", Long.class, tenant))
                .isZero();
        refused(get("/api/auth/account"), boss, null, 401, 1401);
        refused(get("/api/auth/account"), clerk, null, 401, 1401);

        assertThat(owner.queryForObject("SELECT status FROM stores WHERE id = ?", String.class, store)).isEqualTo("ARCHIVED");
        assertThat(publicApi("/api/public/stores/" + store + "/products")).isEmpty();
        publicRefused("/api/public/stores/" + store, 404);
        publicRefused("/api/public/information/" + info, 404);
    }

    @Test
    void aMerchantOnAirCannotBeClosedAndTheCodeIsNotUsedUp() throws Exception {
        JsonNode bossSession = login(BOSS);
        String boss = bossSession.path("accessToken").asText();
        Long tenant = bossSession.path("user").path("tenantId").asLong();
        Long store = owner.queryForObject("INSERT INTO stores(tenant_id,name,created_by) VALUES (?, '西湖店', ?) RETURNING id",
                Long.class, tenant, bossSession.path("user").path("userId").asLong());
        Long live = owner.queryForObject("INSERT INTO live_sessions(tenant_id,store_id,name,status) VALUES (?,?,'晚场','LIVE') RETURNING id",
                Long.class, tenant, store);

        assertThat(api(get("/api/auth/account/closure"), boss, null).path("blockedReason").asText()).contains("晚场");
        seedCode(BOSS, "555555");
        assertThat(refused(post("/api/auth/account/closure"), boss, Map.of("code", "555555", "confirmed", true), 409, 1409))
                .contains("晚场");

        owner.update("UPDATE live_sessions SET status = 'ENDED' WHERE id = ?", live);
        api(post("/api/auth/account/closure"), boss, Map.of("code", "555555", "confirmed", true));
        assertThat(owner.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenant)).isEqualTo("CLOSED");
    }

    @Test
    void closingTheMerchantVoidsPlayerPairingsAndDeletesItsVoices() throws Exception {
        JsonNode bossSession = login(BOSS);
        String boss = bossSession.path("accessToken").asText();
        Long tenant = bossSession.path("user").path("tenantId").asLong();
        Long bossId = bossSession.path("user").path("userId").asLong();
        Long store = owner.queryForObject("INSERT INTO stores(tenant_id,name,created_by) VALUES (?, '西湖店', ?) RETURNING id",
                Long.class, tenant, bossId);
        Long session = owner.queryForObject("INSERT INTO live_sessions(tenant_id,store_id,name,created_by) VALUES (?,?,'晚场',?) RETURNING id",
                Long.class, tenant, store, bossId);
        // 桌面端或安卓端配对后拿到的令牌，和控制台发出的一样。
        var issued = playerTokens.issue(tenant, session);
        owner.update("INSERT INTO live_player_pairings(tenant_id,session_id,token_hash,expires_at,created_by) VALUES (?,?,?,?,?)",
                tenant, session, issued.hash(), java.sql.Timestamp.from(issued.expiresAt()), bossId);
        String player = issued.value();
        assertThat(players.authenticate(player).sessionId()).isEqualTo(session);
        String key = "t" + tenant + "/voice-samples/boss";
        Long voice = owner.queryForObject("INSERT INTO voice_samples(tenant_id,store_id,name,storage_key,mime_type,status,"
                + "provider_code,provider_voice_id,consent_at,consent_by,consent_text) VALUES (?,?,'店主声音',?,'audio/wav','READY',"
                + "'stub-voice','voice-clone-1',now(),?,'同意') RETURNING id", Long.class, tenant, store, key, bossId);
        owner.update("INSERT INTO voice_sample_stores(tenant_id,sample_id,store_id,granted_by) VALUES (?,?,?,?)", tenant, voice, store, bossId);

        assertThat(api(get("/api/auth/account/closure"), boss, null).path("losses").toString()).contains("1 个声音样本");
        seedCode(BOSS, "666666");
        api(post("/api/auth/account/closure"), boss, Map.of("code", "666666", "confirmed", true));

        // 播放端拿着的令牌立即作废。
        assertThatThrownBy(() -> players.authenticate(player)).isInstanceOf(BizException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM live_player_pairings WHERE tenant_id = ? AND revoked_at IS NULL",
                Long.class, tenant)).isZero();

        // 声音马上不能再用，删除排进了任务队列。
        assertThat(owner.queryForObject("SELECT status FROM voice_samples WHERE id = ?", String.class, voice)).isEqualTo("DELETING");
        assertThat(owner.queryForObject("SELECT count(*) FROM voice_sample_stores WHERE sample_id = ?", Long.class, voice)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks WHERE type = 'VOICE_SAMPLE_PURGE' AND tenant_id = ?",
                Long.class, tenant)).isEqualTo(1);

        // 任务执行时删掉文件、试听音频和远端音色；再执行一次不会重复调用供应商。
        when(voiceProvider.code()).thenReturn("stub-voice");
        when(voiceProvider.configured()).thenReturn(true);
        when(storage.stat(anyString())).thenReturn(Optional.empty());
        TenantContext.runAs(tenant, () -> { voices.purge(voice); voices.purge(voice); return null; });
        verify(storage).delete(key);
        verify(storage).delete(key + "-preview-v1.wav");
        verify(voiceProvider, times(1)).deleteVoice("voice-clone-1");
        Map<String, Object> purged = owner.queryForMap("SELECT status, provider_voice_id FROM voice_samples WHERE id = ?", voice);
        assertThat(purged.get("status")).isEqualTo("DELETED");
        assertThat(purged.get("provider_voice_id")).isNull();
    }

    @Test
    void anOwnerWithAnotherOwnerBesideThemClosesOnlyThemselves() throws Exception {
        JsonNode bossSession = login(BOSS);
        Long tenant = bossSession.path("user").path("tenantId").asLong();
        owner.update("INSERT INTO users(tenant_id, username, name, password_hash) VALUES (?, 'partner', '合伙人', ?)",
                tenant, hashpw("partner-secret-1", gensalt(4)));
        String partner = api(post("/api/auth/password-login"), null,
                Map.of("account", "partner", "password", "partner-secret-1")).path("accessToken").asText();

        // 没绑手机号的账号用当前密码确认。
        JsonNode preview = api(get("/api/auth/account/closure"), partner, null);
        assertThat(preview.path("wholeTenant").asBoolean()).isFalse();
        assertThat(preview.path("byCode").asBoolean()).isFalse();
        refused(post("/api/auth/account/closure"), partner, Map.of("currentPassword", "wrong-guess", "confirmed", true), 401, 2009);
        api(post("/api/auth/account/closure"), partner, Map.of("currentPassword", "partner-secret-1", "confirmed", true));

        assertThat(owner.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenant)).isEqualTo("ACTIVE");
        assertThat(owner.queryForObject("SELECT username FROM users WHERE name = '已注销用户'", String.class)).isNull();
        refused(post("/api/auth/password-login"), null, Map.of("account", "partner", "password", "partner-secret-1"), 401, 2009);
        // 剩下的这位成了最后一个管理员，再注销就会关闭商户。
        assertThat(api(get("/api/auth/account/closure"), bossSession.path("accessToken").asText(), null)
                .path("wholeTenant").asBoolean()).isTrue();
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

    private JsonNode publicApi(String path) throws Exception {
        return api(get(path), null, null);
    }

    private void publicRefused(String path, int http) throws Exception {
        mvc.perform(get(path)).andExpect(status().is(http));
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
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(new HashMap<>((Map<?, ?>) body)));
        return request;
    }
}
