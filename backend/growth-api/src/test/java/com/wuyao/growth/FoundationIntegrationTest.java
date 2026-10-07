package com.wuyao.growth;

import com.wuyao.growth.asset.*;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.storage.MinioObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.iam.dto.AuthDtos;
import com.wuyao.growth.iam.service.AuthService;
import com.wuyao.growth.iam.service.SmsSender;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.live.player.LivePlayerService;
import com.wuyao.growth.live.player.LivePlayerWebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.CloseStatus;
import io.minio.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN"})
@AutoConfigureMockMvc
@Testcontainers
class FoundationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("growth_test").withUsername("growth_owner").withPassword("test_owner_password");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
            .withEnv("MINIO_ROOT_USER", "testadmin").withEnv("MINIO_ROOT_PASSWORD", "testadmin123")
            .withCommand("server /data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "growth_app");
        registry.add("spring.datasource.password", () -> "growth_dev_local");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("growth.jwt.secret", () -> "test-only-random-signing-secret-0123456789abcdef");
        registry.add("growth.storage.endpoint", FoundationIntegrationTest::minioEndpoint);
        registry.add("growth.storage.access-key", () -> "testadmin");
        registry.add("growth.storage.secret-key", () -> "testadmin123");
        registry.add("growth.storage.bucket", () -> "test-assets");
    }

    @Autowired AuthService auth;
    @MockitoBean SmsSender smsSender;
    /** Never the real provider: a session can only start with a voice that is usable. */
    @MockitoBean com.wuyao.growth.voice.VoiceProvider voiceProvider;
    @Autowired JwtService jwt;
    @Autowired TaskService tasks;
    @Autowired TaskRepository taskRepository;
    @Autowired AssetService assets;
    @Autowired AssetRepository assetRepository;
    @Autowired AssetProbeHandler probe;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    @Autowired LivePlayerService livePlayers;
    @Autowired LivePlayerWebSocketHandler playerSocketHandler;
    JdbcTemplate owner;
    MinioClient minio;
    Long tenantA;
    Long tenantB;

    static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    @BeforeEach
    void setUp() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tasks, assets, refresh_tokens, sms_codes, users, tenants RESTART IDENTITY CASCADE");
        tenantA = owner.queryForObject("INSERT INTO tenants(name) VALUES ('A') RETURNING id", Long.class);
        tenantB = owner.queryForObject("INSERT INTO tenants(name) VALUES ('B') RETURNING id", Long.class);
        when(voiceProvider.configured()).thenReturn(true);
        when(voiceProvider.code()).thenReturn("stub-voice");
        when(voiceProvider.builtInVoices()).thenReturn(List.of("voice-a"));
        minio = MinioClient.builder().endpoint(minioEndpoint()).credentials("testadmin", "testadmin123").build();
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket("test-assets").build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket("test-assets").build());
        }
    }

    @Test
    void wrongCodesPersistAndLockOutTheCorrectCode() {
        seedCode("13800000001", "123456");
        for (int i = 0; i < 6; i++) {
            assertThat(code(() -> login("13800000001", "999999"))).isEqualTo(2003);
        }
        assertThat(owner.queryForObject("SELECT attempts FROM sms_codes", Integer.class)).isEqualTo(5);
        assertThat(code(() -> login("13800000001", "123456"))).isEqualTo(2003);
        assertThat(owner.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void aCodeIsConsumedOnlyOnceUnderConcurrentLogin() throws Exception {
        seedCode("13800000001", "123456");
        var outcomes = concurrent(() -> code(() -> login("13800000001", "123456")));
        assertThat(outcomes).containsExactlyInAnyOrder(200, 2003);
        assertThat(owner.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void consumingTheLatestCodeDoesNotReactivateOlderCodes() {
        seedCode("13800000001", "123456");
        seedCode("13800000001", "654321");
        assertThat(code(() -> login("13800000001", "654321"))).isEqualTo(200);
        assertThat(code(() -> login("13800000001", "123456"))).isEqualTo(2003);
    }

    @Test
    void concurrentSmsRequestsRespectThePhoneRateLimit() throws Exception {
        var outcomes = concurrent(() -> code(() -> {
            auth.sendCode("13800000001", "127.0.0.1");
            return null;
        }));
        assertThat(outcomes).containsExactlyInAnyOrder(200, 2001);
        assertThat(owner.queryForObject("SELECT count(*) FROM sms_codes", Integer.class)).isEqualTo(1);
    }

    @Test
    void passwordAccountWithoutPhoneCanLoginRestoreAndRefresh() throws Exception {
        Long userId = seedPasswordAccount();
        var result = mvc.perform(post("/api/auth/password-login").contentType("application/json")
                        .content("{\"account\":\"IntegrationUser\",\"password\":\"integration-password\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.user.name").value("IntegrationUser"))
                .andExpect(jsonPath("$.data.user.phone").doesNotExist())
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
                .andReturn();
        var tokens = new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("data");
        String access = tokens.get("accessToken").asText();
        assertThat(jwt.parse(access, "access").userId()).isEqualTo(userId);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("IntegrationUser"));
        assertThat(auth.refresh(tokens.get("refreshToken").asText(), null, null, null).user().userId())
                .isEqualTo(userId);
        verifyNoInteractions(smsSender);
    }

    @Test
    void passwordFailuresPersistAndLockExpires() {
        Long userId = seedPasswordAccount();
        for (int i = 0; i < 5; i++) {
            assertThat(code(() -> passwordLogin("wrong-password"))).isEqualTo(2009);
        }
        assertThat(owner.queryForObject("SELECT password_failed_attempts FROM users WHERE id=?", Integer.class, userId))
                .isEqualTo(5);
        assertThat(code(() -> passwordLogin("integration-password"))).isEqualTo(2009);
        owner.update("UPDATE users SET password_locked_until=now()-interval '1 second' WHERE id=?", userId);
        assertThat(passwordLogin("integration-password").user().userId()).isEqualTo(userId);
        assertThat(owner.queryForObject("SELECT password_failed_attempts FROM users WHERE id=?", Integer.class, userId))
                .isZero();
    }

    @Test
    void unknownDisabledAndMalformedPasswordLoginsDoNotCreateAccounts() throws Exception {
        assertThat(code(() -> passwordLogin("integration-password"))).isEqualTo(2009);
        assertThat(owner.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        Long userId = seedPasswordAccount();
        owner.update("UPDATE users SET status='DISABLED' WHERE id=?", userId);
        assertThat(code(() -> passwordLogin("integration-password"))).isEqualTo(2009);
        mvc.perform(post("/api/auth/password-login").contentType("application/json")
                        .content("{\"account\":\"IntegrationUser\",\"password\":\"short\"}"))
                .andExpect(jsonPath("$.code").value(1400));
        assertThat(owner.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isZero();
    }

    @Test
    void concurrentWrongPasswordsCannotLoseFailureCounts() throws Exception {
        Long userId = seedPasswordAccount();
        for (int i = 0; i < 3; i++) assertThat(code(() -> passwordLogin("wrong-password"))).isEqualTo(2009);
        assertThat(concurrent(() -> code(() -> passwordLogin("wrong-password"))))
                .containsExactly(2009, 2009);
        assertThat(owner.queryForObject("SELECT password_failed_attempts FROM users WHERE id=?", Integer.class, userId))
                .isEqualTo(5);
        assertThat(code(() -> passwordLogin("integration-password"))).isEqualTo(2009);
    }

    private Long seedPasswordAccount() {
        return owner.queryForObject("INSERT INTO users(tenant_id,username,name,password_hash) VALUES (?,?,?,?) RETURNING id",
                Long.class, tenantA, "IntegrationUser", "IntegrationUser",
                new BCryptPasswordEncoder(12).encode("integration-password"));
    }

    @Test
    void storeProductKnowledgeAndLiveSessionPersistThroughTheirApis() throws Exception {
        seedPasswordAccount();
        String token = passwordLogin("integration-password").accessToken();
        long store = apiData(post("/api/stores"), token, Map.of("name", "联调门店")).path("id").asLong();
        long product = apiData(post("/api/stores/" + store + "/products"), token, Map.of(
                "name", "蜂蜜", "type", "PHYSICAL", "category", "食品",
                "price", 69, "saleUnit", "罐",
                "faqs", List.of(Map.of("question", "保质期多久", "answer", "12 个月"))))
                .path("id").asLong();
        assertThat(apiData(get("/api/stores/" + store + "/products?keyword=69"), token, null)
                .path("total").asInt()).isEqualTo(1);
        long set = apiData(post("/api/stores/" + store + "/knowledge-sets"), token,
                Map.of("name", "门店规则", "kind", "FAQ")).path("id").asLong();
        long entry = apiData(post("/api/knowledge-sets/" + set + "/entries"), token,
                Map.of("question", "营业时间", "answer", "每天 9 点至 18 点"))
                .path("id").asLong();
        assertThat(apiData(get("/api/stores/" + store + "/knowledge-context"), token, null)
                .path("entries").size()).isZero();
        apiData(post("/api/knowledge-sets/" + set + "/publish"), token, null);
        Map<String, Object> configuration = new LinkedHashMap<>(Map.of(
                "tone", Map.of("opening", "直接报价", "pain", List.of("需求场景代入", "顾虑问题拆解"),
                        "detail", List.of("核心特点讲解", "售后与保障")),
                "urgency", 2.75, "antiRepeat", false, "dailyHours", 4, "rotateRoles", false,
                "rotationSelection", List.of("builtin:voice-a"),
                "voiceRoles", List.of(Map.of("id", "builtin:voice-a", "role", "host"))));
        var createdSession = apiData(post("/api/stores/" + store + "/live-sessions"), token,
                Map.of("name", "联调场次", "roomId", "123456", "productIds", List.of(product),
                        "config", configuration));
        long session = createdSession.path("id").asLong();
        String path = "/api/live-sessions/" + session;
        var json = new ObjectMapper();
        // Each HTTP request has its own transaction: GET must hydrate the typed configuration from JSONB.
        assertThat(apiData(get(path), token, null).path("config")).isEqualTo(json.valueToTree(configuration));
        long originalSessionVersion = createdSession.path("version").asLong();
        configuration.put("urgency", 4.25);
        var updatedSession = apiData(patch(path), token,
                Map.of("version", originalSessionVersion, "config", configuration));
        assertThat(updatedSession.path("version").asLong()).isGreaterThan(originalSessionVersion);
        assertThat(apiData(get(path), token, null).path("config")).isEqualTo(json.valueToTree(configuration));
        mvc.perform(patch(path).header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("version", originalSessionVersion, "name", "过期覆盖"))))
                .andExpect(jsonPath("$.code").value(1409));
        assertThat(apiData(get(path), token, null).path("name").asText()).isEqualTo("联调场次");

        var firstQa = apiData(post(path + "/qa"), token, Map.of("question", "本场优惠", "answer", "买二送一"));
        String firstQaPath = path + "/qa/" + firstQa.path("id").asLong();
        long originalQaVersion = firstQa.path("version").asLong();
        var editedQa = apiData(put(firstQaPath), token,
                Map.of("question", "本场优惠怎么用", "answer", "买二送一，仅限今天", "version", originalQaVersion));
        assertThat(editedQa.path("version").asLong()).isGreaterThan(originalQaVersion);
        var restoredQa = apiData(get(path + "/qa"), token, null).get(0);
        assertThat(restoredQa.path("question").asText()).isEqualTo("本场优惠怎么用");
        assertThat(restoredQa.path("answer").asText()).isEqualTo("买二送一，仅限今天");
        assertThat(restoredQa.path("version")).isEqualTo(editedQa.path("version"));
        mvc.perform(put(firstQaPath).header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("question", "本场优惠", "answer", "过期修改",
                                "version", originalQaVersion))))
                .andExpect(jsonPath("$.code").value(1409));

        var disposableQa = apiData(post(path + "/qa"), token, Map.of("question", "临时问题", "answer", "待删除"));
        String disposableQaPath = path + "/qa/" + disposableQa.path("id").asLong();
        long deleteVersion = disposableQa.path("version").asLong();
        mvc.perform(delete(disposableQaPath).param("version", String.valueOf(deleteVersion + 1))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1409));
        assertThat(apiData(get(path + "/qa"), token, null).size()).isEqualTo(2);
        apiData(delete(disposableQaPath).param("version", String.valueOf(deleteVersion)), token, null);
        assertThat(apiData(get(path + "/qa"), token, null).size()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM live_session_qa WHERE id=?", Integer.class,
                disposableQa.path("id").asLong())).isZero();
        apiData(post(path + "/qa"), token, Map.of("question", "如何保存", "answer", "常温避光",
                "persistMode", "PRODUCT_FAQ", "targetId", product));
        apiData(post(path + "/qa"), token, Map.of("question", "停车问题", "answer", "门口可停车",
                "persistMode", "STORE_KNOWLEDGE", "targetId", set));
        assertThat(apiData(get("/api/products/" + product + "/faqs"), token, null).size()).isEqualTo(2);
        assertThat(apiData(get("/api/knowledge-sets/" + set + "/entries"), token, null)
                .path("total").asInt()).isEqualTo(2);
        assertThat(apiData(get("/api/stores/" + store + "/live-sessions"), token, null).size()).isEqualTo(1);
        var started = apiData(post(path + "/start"), token, null);
        assertThat(started.path("status").asText()).isEqualTo("LIVE");
        // 3 本场问答 + 2 商品问答 + 1 已发布门店规则；沉淀到门店的新草稿不会自动发布。
        assertThat(started.path("knowledge").size()).isEqualTo(6);
        assertThat(started.path("knowledge").get(0).path("priority").asInt()).isEqualTo(1);
        assertThat(started.path("knowledge").get(5).path("priority").asInt()).isEqualTo(3);
        apiData(patch("/api/knowledge-entries/" + entry), token, Map.of("answer", "次日停业"));
        apiData(post(path + "/pause"), token, null);
        var resumed = apiData(post(path + "/resume"), token, null);
        assertThat(resumed.path("knowledge")).isEqualTo(started.path("knowledge"));
        assertThat(apiData(post(path + "/end"), token, null).path("status").asText()).isEqualTo("ENDED");
        assertThat(owner.queryForObject("SELECT count(*) FROM live_session_knowledge_snapshots WHERE session_id=?",
                Integer.class, session)).isEqualTo(6);
        mvc.perform(post(path + "/start").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1409));
    }

    @Test
    void storeMembershipAndTenantIsolationProtectAllNewBusinessResources() throws Exception {
        Long user = seedPasswordAccount();
        String token = jwt.issueAccessToken(user, tenantA, null);
        long store = apiData(post("/api/stores"), token, Map.of("name", "第一家店")).path("id").asLong();
        long otherStore = apiData(post("/api/stores"), token, Map.of("name", "第二家店")).path("id").asLong();
        long product = apiData(post("/api/stores/" + store + "/products"), token, Map.of(
                "name", "团购券", "type", "VOUCHER", "category", "餐饮", "price", 19.9, "saleUnit", "张"))
                .path("id").asLong();
        long session = apiData(post("/api/stores/" + store + "/live-sessions"), token,
                Map.of("name", "场次", "productIds", List.of(product))).path("id").asLong();
        long set = apiData(post("/api/stores/" + store + "/knowledge-sets"), token,
                Map.of("name", "规则")).path("id").asLong();
        String stranger = jwt.issueAccessToken(999L, tenantA, null);
        String differentTenant = jwt.issueAccessToken(999L, tenantB, null);
        for (String path : List.of("/api/stores/" + store, "/api/products/" + product,
                "/api/live-sessions/" + session, "/api/knowledge-sets/" + set)) {
            mvc.perform(get(path).header("Authorization", "Bearer " + stranger))
                    .andExpect(jsonPath("$.code").value(1403));
            mvc.perform(get(path).header("Authorization", "Bearer " + differentTenant))
                    .andExpect(jsonPath("$.code").value(1404));
        }
        mvc.perform(post("/api/stores/" + otherStore + "/live-sessions")
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "name", "禁止跨店选品", "productIds", List.of(product)))))
                .andExpect(jsonPath("$.code").value(1403));
        // 身份隔离也必须由真实数据库保证。
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "growth_app", "growth_dev_local")) {
            connection.createStatement().execute("SELECT set_config('app.tenant_id', '" + tenantB + "', false)");
            for (String table : List.of("stores", "products", "knowledge_sets", "live_sessions")) {
                try (var result = connection.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                }
            }
        }
    }

    private com.fasterxml.jackson.databind.JsonNode apiData(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String token, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(new ObjectMapper().writeValueAsString(body));
        var response = mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse();
        return new ObjectMapper().readTree(response.getContentAsString()).path("data");
    }

    @Test
    void voiceUploadReturnsPersistedIdAndSampleLifecycleRetainsConsentAudit() throws Exception {
        Long user = seedPasswordAccount();
        String token = jwt.issueAccessToken(user, tenantA, null);
        long store = apiData(post("/api/stores"), token, Map.of("name", "声音样本门店")).path("id").asLong();
        String collection = "/api/stores/" + store + "/voice-samples";
        var ticket = apiData(post(collection + "/upload-url"), token,
                Map.of("name", "店主声音", "mimeType", "audio/wav", "consent", true));
        // Hibernate merge may return a different instance; the API must expose its generated ID.
        assertThat(ticket.path("sample").hasNonNull("id")).isTrue();
        long id = ticket.path("sample").path("id").asLong();
        assertThat(id).isPositive();
        String samplePath = "/api/voice-samples/" + id;
        String key = ticket.path("sample").path("storageKey").asText();
        assertThat(key).satisfiesAnyOf(value -> assertThat(value).startsWith("t" + tenantA + "/voice-samples/"),
                value -> assertThat(value).startsWith("cos/t" + tenantA + "/voice-samples/"));
        assertThat(ticket.path("sample").path("consentBy").asLong()).isEqualTo(user);
        assertThat(ticket.path("sample").path("consentAt").asText()).isNotBlank();
        assertThat(ticket.path("sample").path("consentText").asText())
                .isEqualTo("我确认这是本人声音，或已获得声音所有者授权用于声音克隆与直播播报。");
        var auditBefore = owner.queryForMap("SELECT consent_at, consent_by, consent_text, storage_key FROM voice_samples WHERE id=?", id);
        assertThat(owner.queryForObject("SELECT tenant_id FROM voice_samples WHERE id=?", Long.class, id)).isEqualTo(tenantA);
        assertThat(owner.queryForObject("SELECT store_id FROM voice_samples WHERE id=?", Long.class, id)).isEqualTo(store);
        var listed = apiData(get(collection), token, null);
        assertThat(listed.size()).isEqualTo(1);
        assertThat(listed.get(0).path("id").asLong()).isEqualTo(id);
        assertThat(listed.get(0).path("status").asText()).isEqualTo("PENDING_UPLOAD");

        byte[] wav = com.wuyao.growth.live.audio.PcmAudio.wav(new byte[24000 * 2], 24000);
        var putRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create(ticket.path("uploadUrl").asText()))
                .header("Content-Type", "audio/wav")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(wav)).build();
        var uploaded = java.net.http.HttpClient.newHttpClient().send(putRequest, java.net.http.HttpResponse.BodyHandlers.discarding());
        assertThat(uploaded.statusCode()).isEqualTo(200);
        var confirmed = apiData(post(samplePath + "/confirm"), token, Map.of("sizeBytes", wav.length));
        assertThat(confirmed.path("id").asLong()).isEqualTo(id);
        assertThat(confirmed.path("status").asText()).isEqualTo("UPLOADED");
        assertThat(apiData(get(samplePath + "/download-url"), token, null).path("downloadUrl").asText()).isNotBlank();
        assertThat(apiData(patch(samplePath), token, Map.of("name", "修改后的声音")).path("name").asText())
                .isEqualTo("修改后的声音");
        assertThat(apiData(get(collection), token, null).get(0).path("name").asText()).isEqualTo("修改后的声音");

        apiData(delete(samplePath), token, null);
        assertThat(apiData(get(collection), token, null).size()).isZero();
        assertThat(owner.queryForObject("SELECT status FROM voice_samples WHERE id=?", String.class, id)).isEqualTo("DELETED");
        assertThat(owner.queryForMap("SELECT consent_at, consent_by, consent_text, storage_key FROM voice_samples WHERE id=?", id))
                .isEqualTo(auditBefore);
        assertThatThrownBy(() -> minio.statObject(StatObjectArgs.builder().bucket("test-assets").object(key).build()))
                .isInstanceOf(io.minio.errors.ErrorResponseException.class)
                .satisfies(error -> assertThat(((io.minio.errors.ErrorResponseException) error).errorResponse().code()).isEqualTo("NoSuchKey"));
        mvc.perform(get(samplePath + "/download-url").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1404));
    }

    @Test
    void voiceConsentMembershipAndDatabaseRlsPreventUnauthorizedAccess() throws Exception {
        Long user = seedPasswordAccount();
        String token = jwt.issueAccessToken(user, tenantA, null);
        long store = apiData(post("/api/stores"), token, Map.of("name", "隔离测试门店")).path("id").asLong();
        String collection = "/api/stores/" + store + "/voice-samples";
        mvc.perform(post(collection + "/upload-url").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"name\":\"未授权声音\",\"mimeType\":\"audio/wav\",\"consent\":false}"))
                .andExpect(jsonPath("$.code").value(1400));
        assertThat(owner.queryForObject("SELECT count(*) FROM voice_samples", Integer.class)).isZero();
        long id = apiData(post(collection + "/upload-url"), token,
                Map.of("name", "授权声音", "mimeType", "audio/wav", "consent", true)).path("sample").path("id").asLong();
        assertThat(id).isPositive();
        String stranger = jwt.issueAccessToken(999L, tenantA, null);
        String otherTenant = jwt.issueAccessToken(999L, tenantB, null);
        for (var identity : Map.of(stranger, 1403, otherTenant, 1404).entrySet()) {
            mvc.perform(get(collection).header("Authorization", "Bearer " + identity.getKey()))
                    .andExpect(jsonPath("$.code").value(identity.getValue()));
            mvc.perform(post(collection + "/upload-url").header("Authorization", "Bearer " + identity.getKey())
                            .contentType("application/json").content("{\"name\":\"声音\",\"mimeType\":\"audio/wav\",\"consent\":true}"))
                    .andExpect(jsonPath("$.code").value(identity.getValue()));
            mvc.perform(patch("/api/voice-samples/" + id).header("Authorization", "Bearer " + identity.getKey())
                            .contentType("application/json").content("{\"name\":\"越权修改\"}"))
                    .andExpect(jsonPath("$.code").value(identity.getValue()));
            mvc.perform(delete("/api/voice-samples/" + id).header("Authorization", "Bearer " + identity.getKey()))
                    .andExpect(jsonPath("$.code").value(identity.getValue()));
        }
        assertThat(owner.queryForObject("SELECT name FROM voice_samples WHERE id=?", String.class, id)).isEqualTo("授权声音");
        assertThat(owner.queryForObject("SELECT count(*) FROM voice_samples", Integer.class)).isEqualTo(1);
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "growth_app", "growth_dev_local")) {
            connection.createStatement().execute("SELECT set_config('app.tenant_id', '" + tenantA + "', false)");
            try (var rows = connection.createStatement().executeQuery("SELECT count(*) FROM voice_samples")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1);
            }
            connection.createStatement().execute("SELECT set_config('app.tenant_id', '" + tenantB + "', false)");
            try (var rows = connection.createStatement().executeQuery("SELECT count(*) FROM voice_samples")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero();
            }
            assertThat(connection.createStatement().executeUpdate("UPDATE voice_samples SET name='越权' WHERE id=" + id)).isZero();
        }
    }

    @Test
    void playerPairingScopesTokenAndRequiresRealHeartbeatWithoutChangingDraftVersion() throws Exception {
        Long user = seedPasswordAccount();
        String token = jwt.issueAccessToken(user, tenantA, null);
        long store = apiData(post("/api/stores"), token, Map.of("name", "播报测试门店")).path("id").asLong();
        long session = apiData(post("/api/stores/" + store + "/live-sessions"), token,
                Map.of("name", "声音测试场次")).path("id").asLong();
        String path = "/api/live-sessions/" + session + "/player";
        var pairing = apiData(post(path + "/pairing"), token, null);
        String playerToken = pairing.path("token").asText();
        assertThat(playerToken).isNotBlank();
        assertThat(owner.queryForObject("select token_hash from live_player_pairings", String.class))
                .hasSize(64).isNotEqualTo(playerToken);
        assertThat(apiData(get(path + "/status"), token, null).path("connected").asBoolean()).isFalse();
        long version = apiData(get("/api/live-sessions/" + session), token, null).path("version").asLong();
        assertThat(livePlayers.authenticate(playerToken).sessionId()).isEqualTo(session);
        assertThat(TenantContext.get()).isNull();
        assertThatThrownBy(() -> livePlayers.authenticate(playerToken + "tampered")).isInstanceOf(BizException.class);
        // Owner access is still required for controls even if somebody knows the numeric session ID.
        mvc.perform(post(path + "/pairing").header("Authorization", "Bearer " + jwt.issueAccessToken(999L, tenantA, null)))
                .andExpect(jsonPath("$.code").value(1403));
        mvc.perform(get(path + "/status").header("Authorization", "Bearer " + jwt.issueAccessToken(999L, tenantB, null)))
                .andExpect(jsonPath("$.code").value(1404));
        // Opening the socket does not invent a playback-ready heartbeat.
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getUri()).thenReturn(java.net.URI.create("ws://localhost/api/player/ws?token=" + playerToken));
        when(socket.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(socket.isOpen()).thenReturn(true);
        playerSocketHandler.afterConnectionEstablished(socket);
        assertThat(apiData(get(path + "/status"), token, null).path("connected").asBoolean()).isFalse();
        playerSocketHandler.handleMessage(socket, new TextMessage("{\"type\":\"heartbeat\",\"deviceType\":\"Android Chrome\"}"));
        var connected = apiData(get(path + "/status"), token, null);
        assertThat(connected.path("connected").asBoolean()).isTrue();
        assertThat(connected.path("playerPaired").asBoolean()).isTrue();
        assertThat(connected.path("playerLastHeartbeatAt").asText()).isNotBlank();
        assertThat(apiData(get("/api/live-sessions/" + session), token, null).path("version").asLong()).isEqualTo(version);
        var command = Map.of("id", "audio-1", "mode", "APPEND", "text", "仅测试提示音",
                "audioUrl", "/api/player/test-audio.wav", "durationMillis", 1, "pauseOffsetsMillis", List.of());
        assertThat(apiData(post(path + "/commands"), token, command).path("enqueued").asBoolean()).isTrue();
        assertThat(apiData(post(path + "/commands"), token, command).path("enqueued").asBoolean()).isFalse();
        assertThat(apiData(get(path + "/status"), token, null).path("queueLength").asInt()).isEqualTo(1);
        playerSocketHandler.handleMessage(socket, new TextMessage("{\"type\":\"ack\",\"id\":\"audio-1\"}"));
        assertThat(apiData(get(path + "/status"), token, null).path("queueLength").asInt()).isZero();
        var badCommand = Map.of("id", "audio-2", "mode", "APPEND", "text", "外部地址",
                "audioUrl", "http://127.0.0.1/private", "durationMillis", 1000, "pauseOffsetsMillis", List.of());
        mvc.perform(post(path + "/commands").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(new ObjectMapper().writeValueAsString(badCommand)))
                .andExpect(jsonPath("$.code").value(1400));
        apiData(post(path + "/revoke"), token, null);
        assertThatThrownBy(() -> livePlayers.authenticate(playerToken)).isInstanceOf(BizException.class);
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(apiData(get(path + "/status"), token, null).path("connected").asBoolean()).isFalse();
        mvc.perform(get("/api/player/test-audio.wav")).andExpect(status().isOk())
                .andExpect(content().contentType("audio/wav"));
    }

    private AuthDtos.TokenPair passwordLogin(String password) {
        return auth.loginWithPassword("IntegrationUser", password, "127.0.0.1", "integration-test", null);
    }

    @Test
    void sentCodeCanLoginOnceAndIsNeverReturnedBySendEndpoint() throws Exception {
        mvc.perform(post("/api/auth/send-code").contentType("application/json")
                        .content("{\"phone\":\"13800000001\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.developmentMode").value(false))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(60))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.data.code").doesNotExist());
        var sent = ArgumentCaptor.forClass(String.class);
        verify(smsSender).sendLoginCode(eq("13800000001"), sent.capture());
        assertThat(sent.getValue()).matches("[0-9]{6}");
        assertThat(owner.queryForObject("SELECT code_hash FROM sms_codes", String.class))
                .hasSize(64).isNotEqualTo(sent.getValue());
        String loginBody = "{\"phone\":\"13800000001\",\"code\":\"" + sent.getValue() + "\"}";
        mvc.perform(post("/api/auth/login").contentType("application/json").content(loginBody))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        mvc.perform(post("/api/auth/login").contentType("application/json").content(loginBody))
                .andExpect(jsonPath("$.code").value(2003));
    }

    @Test
    void failedSendRollsBackCodeAndAllowsRetry() throws Exception {
        doThrow(BizException.of(ErrorCode.SMS_SEND_FAILED, "短信发送失败")).doNothing()
                .when(smsSender).sendLoginCode(anyString(), anyString());
        mvc.perform(post("/api/auth/send-code").contentType("application/json")
                        .content("{\"phone\":\"13800000001\"}"))
                .andExpect(jsonPath("$.code").value(2008));
        assertThat(owner.queryForObject("SELECT count(*) FROM sms_codes", Integer.class)).isZero();
        assertThat(auth.sendCode("13800000001", "127.0.0.1").developmentMode()).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM sms_codes", Integer.class)).isEqualTo(1);
    }

    @Test
    void developmentSendIsExplicitAndExpiredCodesAreRejected() throws Exception {
        when(smsSender.developmentMode()).thenReturn(true);
        mvc.perform(post("/api/auth/send-code").contentType("application/json")
                        .content("{\"phone\":\"13800000001\"}"))
                .andExpect(jsonPath("$.data.developmentMode").value(true));
        var sent = ArgumentCaptor.forClass(String.class);
        verify(smsSender).sendLoginCode(eq("13800000001"), sent.capture());
        owner.update("UPDATE sms_codes SET expires_at=now()-interval '1 second'");
        assertThat(code(() -> login("13800000001", sent.getValue()))).isEqualTo(2004);
    }

    @Test
    void refreshRotationHasExactlyOneWinner() throws Exception {
        seedCode("13800000001", "123456");
        var pair = login("13800000001", "123456");
        try (Connection lock = ownerConnection(); var pool = Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);
            lock.createStatement().execute("SELECT id FROM refresh_tokens FOR UPDATE");
            var first = pool.submit(() -> code(() -> auth.refresh(pair.refreshToken(), null, null, null)));
            var second = pool.submit(() -> code(() -> auth.refresh(pair.refreshToken(), null, null, null)));
            try {
                awaitDatabaseWaiters("refresh_tokens", 2);
            } finally {
                lock.commit();
            }
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 2005);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM refresh_tokens WHERE status='ACTIVE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentIdempotentSubmissionsReturnTheSameTask() throws Exception {
        try (Connection lock = ownerConnection(); var pool = Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);
            lock.createStatement().execute("LOCK TABLE tasks IN SHARE MODE");
            var first = pool.submit(() -> submit("same-key").getId());
            var second = pool.submit(() -> submit("same-key").getId());
            try {
                awaitDatabaseWaiters("tasks", 2);
            } finally {
                lock.commit();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks", Integer.class)).isEqualTo(1);
        var other = TenantContext.runAs(tenantB, () -> tasks.submit("TEST", "TEST", Map.of(), "same-key", null));
        assertThat(other.getTenantId()).isEqualTo(tenantB);
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks", Integer.class)).isEqualTo(2);
    }

    @Test
    void taskInsertionRollsBackWithItsBusinessTransaction() {
        TenantContext.runAs(tenantA, () -> transactions.execute(status -> {
            tasks.submit("TEST", "TEST", Map.of(), "rolled-back", null);
            status.setRollbackOnly();
            return null;
        }));
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks", Integer.class)).isZero();
    }

    @Test
    void concurrentWorkersClaimDifferentRows() throws Exception {
        submit("one");
        submit("two");
        var ids = concurrent(() -> tasks.claim("TEST", 1).getFirst().getId());
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks WHERE status='RUNNING' AND attempts=1", Integer.class)).isEqualTo(2);
    }

    @Test
    void expiredAndStaleWorkersCannotRenewOrOverwriteTheReplacement() {
        submit("lease");
        Task old = tasks.claim("TEST", 1).getFirst();
        expire(old.getId());
        assertThat(tasks.renew(old.getId(), old.getAttempts())).isFalse();
        assertThat(tasks.succeed(old.getId(), old.getAttempts(), Map.of())).isFalse();
        assertThat(tasks.reclaimExpired()).isEqualTo(1);
        assertThat(tasks.claim("TEST", 1)).isEmpty(); // 回收也必须退避
        owner.update("UPDATE tasks SET run_after=now()-interval '1 second' WHERE id=?", old.getId());
        Task replacement = tasks.claim("TEST", 1).getFirst();
        assertThat(replacement.getAttempts()).isEqualTo(2);
        assertThat(tasks.fail(old.getId(), old.getAttempts(), "OLD", "stale")).isFalse();
        assertThat(tasks.succeed(replacement.getId(), replacement.getAttempts(), Map.of("winner", "new"))).isTrue();
        assertThat(tasks.fail(old.getId(), old.getAttempts(), "OLD", "late failure")).isFalse();
        Task stored = taskRepository.findById(old.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(stored.getErrorCode()).isNull();
        assertThat(stored.getResult()).containsEntry("winner", "new");
    }

    @Test
    void expiredFinalAttemptBecomesFailedAndCannotBeClaimedAgain() {
        Task task = submit("last-attempt");
        owner.update("UPDATE tasks SET status='RUNNING', attempts=max_attempts, lease_expires_at=now()-interval '1 second' WHERE id=?", task.getId());
        assertThat(tasks.reclaimExpired()).isEqualTo(1);
        Task stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(stored.getAttempts()).isEqualTo(stored.getMaxAttempts());
        assertThat(stored.getFinishedAt()).isNotNull();
        assertThat(tasks.claim("TEST", 1)).isEmpty();
    }

    @Test
    void heartbeatExtendsOnlyTheCurrentLease() {
        submit("heartbeat");
        Task task = tasks.claim("TEST", 1).getFirst();
        owner.update("UPDATE tasks SET lease_expires_at=now()+interval '10 seconds' WHERE id=?", task.getId());
        assertThat(tasks.renew(task.getId(), task.getAttempts() + 1)).isFalse();
        assertThat(tasks.renew(task.getId(), task.getAttempts())).isTrue();
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getLeaseExpiresAt()).isAfter(Instant.now().plusSeconds(60));
    }

    @Test
    void missingUploadCannotBecomeReadyOrSubmitAProbe() {
        var ticket = ticket();
        assertThat(code(() -> TenantContext.runAs(tenantA,
                () -> assets.confirmUpload(ticket.assetId(), new AssetDtos.ConfirmRequest(null, null), null)))).isEqualTo(3002);
        assertThat(owner.queryForObject("SELECT status FROM assets WHERE id=?", String.class, ticket.assetId())).isEqualTo("PENDING");
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks", Integer.class)).isZero();
    }

    @Test
    void confirmationUsesStorageSizeAndDoesNotTrustClientHashes() throws Exception {
        var ticket = ticket();
        byte[] content = "actual file".getBytes(StandardCharsets.UTF_8);
        minio.putObject(PutObjectArgs.builder().bucket("test-assets").object(ticket.storageKey())
                .stream(new ByteArrayInputStream(content), content.length, -1).contentType("image/png").build());
        assertThat(code(() -> TenantContext.runAs(tenantA,
                () -> assets.confirmUpload(ticket.assetId(), new AssetDtos.ConfirmRequest(999L, null), null)))).isEqualTo(3002);
        var view = TenantContext.runAs(tenantA, () -> assets.confirmUpload(ticket.assetId(),
                new AssetDtos.ConfirmRequest((long) content.length, "a".repeat(64)), null));
        assertThat(view.status()).isEqualTo("READY");
        assertThat(view.sizeBytes()).isEqualTo(content.length);
        assertThat(owner.queryForObject("SELECT sha256 FROM assets WHERE id=?", String.class, ticket.assetId())).isNull();
        TenantContext.runAs(tenantA, () -> assets.confirmUpload(ticket.assetId(), new AssetDtos.ConfirmRequest(null, null), null));
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks", Integer.class)).isEqualTo(1);
        Task task = tasks.claim("DEFAULT", 1).getFirst();
        assertThat(TenantContext.runAs(tenantA, () -> probe.handle(task)))
                .containsEntry("objectVerified", true).containsEntry("probed", false);
    }

    @Test
    void tenantIsolationProtectsBothReadsAndWrites() throws Exception {
        var ticket = ticket();
        String otherToken = jwt.issueAccessToken(123L, tenantB, "13800000002");
        mvc.perform(get("/api/assets").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(post("/api/assets/" + ticket.assetId() + "/confirm")
                        .header("Authorization", "Bearer " + otherToken).contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(3001));
        assertThat(owner.queryForObject("SELECT status FROM assets WHERE id=?", String.class, ticket.assetId())).isEqualTo("PENDING");
        assertThat(TenantContext.get()).isNull();
        assertThat(TenantContext.runAs(tenantA, () -> assets.list(0, 20)).total()).isEqualTo(1);
    }

    @Test
    void invalidUploadRequestsReturnBusinessValidationErrors() throws Exception {
        String token = jwt.issueAccessToken(123L, tenantA, "13800000001");
        mvc.perform(post("/api/assets/upload-url").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"name\":\"missing type\"}"))
                .andExpect(jsonPath("$.code").value(1400));
        mvc.perform(post("/api/assets/1/confirm").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"sizeBytes\":-1,\"sha256\":\"fake\"}"))
                .andExpect(jsonPath("$.code").value(1400));
        mvc.perform(get("/api/assets?size=100000").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1400));
    }

    @Test
    void storageConfigurationFailureIsNotReportedAsAMissingObject() {
        var wrongBucket = new MinioObjectStorage(minioEndpoint(), "testadmin", "testadmin123", "does-not-exist");
        assertThat(code(() -> wrongBucket.stat("missing"))).isEqualTo(1503);
    }

    private AssetDtos.UploadTicket ticket() {
        return TenantContext.runAs(tenantA, () -> assets.presignUpload(new AssetDtos.PresignRequest("test", "IMAGE", "image/png"), null));
    }

    private Task submit(String key) {
        return TenantContext.runAs(tenantA, () -> tasks.submit("TEST", "TEST", Map.of("key", key), key, null));
    }

    private void expire(Long id) {
        owner.update("UPDATE tasks SET lease_expires_at=now()-interval '1 second' WHERE id=?", id);
    }

    private AuthDtos.TokenPair login(String phone, String code) {
        return auth.login(phone, code, "127.0.0.1", "integration-test", null);
    }

    private void seedCode(String phone, String code) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
            owner.update("INSERT INTO sms_codes(phone,code_hash,expires_at) VALUES (?,?,now()+interval '5 minutes')", phone, hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int code(Supplier<?> action) {
        try {
            action.get();
            return 200;
        } catch (BizException e) {
            return e.getErrorCode().getCode();
        }
    }

    private Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void awaitDatabaseWaiters(String table, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(owner.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename='growth_app' AND wait_event_type='Lock' AND query LIKE ?",
                        Integer.class, "%" + table + "%")).isGreaterThanOrEqualTo(count));
    }

    private <T> List<T> concurrent(Callable<T> action) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<T> synchronizedAction = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return action.call();
            };
            var first = pool.submit(synchronizedAction);
            var second = pool.submit(synchronizedAction);
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }
}
