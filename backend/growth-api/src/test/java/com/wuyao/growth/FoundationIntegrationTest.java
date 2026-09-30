package com.wuyao.growth;

import com.wuyao.growth.asset.*;
import com.wuyao.growth.creative.image.*;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.storage.MinioObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.iam.dto.AuthDtos;
import com.wuyao.growth.iam.service.AuthService;
import com.wuyao.growth.iam.service.SmsSender;
import com.wuyao.growth.common.web.ErrorCode;
import io.minio.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"growth.worker.enabled=false", "spring.data.redis.client-type=jedis", "logging.level.root=WARN",
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

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());


    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "growth_app");
        registry.add("spring.datasource.password", () -> "growth_dev_local");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("growth.jwt.secret", () -> "test-only-random-signing-secret-0123456789abcdef");
        registry.add("growth.storage.endpoint", FoundationIntegrationTest::minioEndpoint);
        registry.add("growth.storage.access-key", () -> "testadmin");
        registry.add("growth.storage.secret-key", () -> "testadmin123");
        registry.add("growth.storage.bucket", () -> "test-assets");
    }

    @Autowired ImageCreationService imageCreations;
    @Autowired com.wuyao.growth.common.gateway.ImageModelProperties imageConfig;
    @Autowired ImagePlanHandler imagePlanHandler;
    @Autowired ImageRenderHandler imageRenderHandler;
    @Autowired ImageRenderer imageRenderer;
    @Autowired com.wuyao.growth.common.storage.ObjectStorage imageStorage;
    @MockitoBean AiGateway imageGateway;
    @Autowired AuthService auth;
    @MockitoBean SmsSender smsSender;
    @Autowired JwtService jwt;
    @Autowired TaskService tasks;
    @Autowired TaskRepository taskRepository;
    @Autowired com.wuyao.growth.common.ratelimit.TenantRateLimiter imageRateLimiter;
    @Autowired StringRedisTemplate redis;
    @Autowired AssetService assets;
    @Autowired AssetRepository assetRepository;
    @Autowired AssetProbeHandler probe;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    JdbcTemplate owner;
    MinioClient minio;
    Long tenantA;
    Long tenantB;

    static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    @BeforeEach
    void setUp() throws Exception {
        imageConfig.setQualities(List.of("480P", "720P", "1080P", "4K"));
        imageConfig.getGenerator().setProtocol(com.wuyao.growth.common.gateway.ImageModelProperties.Protocol.BRIDGE);
        imageConfig.getGenerator().setModel("");
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        owner.execute("TRUNCATE tasks, assets, refresh_tokens, sms_codes, users, tenants RESTART IDENTITY CASCADE");
        tenantA = owner.queryForObject("INSERT INTO tenants(name) VALUES ('A') RETURNING id", Long.class);
        tenantB = owner.queryForObject("INSERT INTO tenants(name) VALUES ('B') RETURNING id", Long.class);
        minio = MinioClient.builder().endpoint(minioEndpoint()).credentials("testadmin", "testadmin123").build();
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket("test-assets").build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket("test-assets").build());
        }
        // Database IDs restart per test, so durable image keys must be isolated too.
        for (var object : minio.listObjects(ListObjectsArgs.builder().bucket("test-assets").recursive(true).build())) {
            minio.removeObject(RemoveObjectArgs.builder().bucket("test-assets").object(object.get().objectName()).build());
        }
    }

    @Test
    void newTenantsReceiveDefaultImageQuota() {
        assertThat(owner.queryForObject("SELECT concurrent_limit FROM tenant_quotas WHERE tenant_id=?",
                Integer.class, tenantA)).isEqualTo(20);
    }

    @Test
    void globalImageConcurrencyLimitIsSharedAcrossTenants() {
        assertThat(imageRateLimiter.tryAcquireImageGeneration(tenantA, 20, 1)).isTrue();
        assertThat(imageRateLimiter.tryAcquireImageGeneration(tenantB, 20, 1)).isFalse();
        assertThat(imageRateLimiter.getGlobalActiveCount()).isEqualTo(1);

        imageRateLimiter.releaseImageGeneration(tenantA);
        assertThat(imageRateLimiter.tryAcquireImageGeneration(tenantB, 20, 1)).isTrue();
        imageRateLimiter.releaseImageGeneration(tenantB);
        assertThat(imageRateLimiter.getGlobalActiveCount()).isZero();
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
        var image = new java.awt.image.BufferedImage(2, 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        byte[] content = encoded.toByteArray();
        minio.putObject(PutObjectArgs.builder().bucket("test-assets").object(ticket.storageKey())
                .stream(new ByteArrayInputStream(content), content.length, -1).contentType("image/png").build());
        assertThat(code(() -> TenantContext.runAs(tenantA,
                () -> assets.confirmUpload(ticket.assetId(), new AssetDtos.ConfirmRequest(999L, null), null)))).isEqualTo(3002);
        var view = TenantContext.runAs(tenantA, () -> assets.confirmUpload(ticket.assetId(),
                new AssetDtos.ConfirmRequest((long) content.length, "a".repeat(64)), null));
        assertThat(view.status()).isEqualTo("READY");
        assertThat(view.sizeBytes()).isEqualTo(content.length);
        assertThat(owner.queryForObject("SELECT sha256 FROM assets WHERE id=?", String.class, ticket.assetId()))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        TenantContext.runAs(tenantA, () -> assets.confirmUpload(ticket.assetId(), new AssetDtos.ConfirmRequest(null, null), null));
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks", Integer.class)).isEqualTo(1);
        Task task = tasks.claim("DEFAULT", 1).getFirst();
        assertThat(TenantContext.runAs(tenantA, () -> probe.handle(task)))
                .containsEntry("objectVerified", true).containsEntry("probed", true);
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

    private ImageDtos.Create imageRequest(String key, String workflow, int count, List<ImageDtos.Reference> refs) {
        return new ImageDtos.Create(key, workflow, "夏日新品", refs, "3:4", "480P", count, "LOCAL", "POSTER", "朋友圈", "帮我搭配", null);
    }

    private void enableImageModels() {
        when(imageGateway.configured(any())).thenReturn(true);
    }

    private void planImageCount(int count) {
        planImageCount(count, "", "");
    }

    private void planImageCount(int count, String headline, String caption) {
        var specs = java.util.stream.IntStream.range(0, count).mapToObj(n -> Map.of(
                "role", "场景" + n, "prompt", "夏日自然光，保持商品外观", "headline", headline, "caption", caption)).toList();
        when(imageGateway.invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.TEXT_CREATIVE)))
                .thenReturn(new ProviderResult(true, "TEST", null,
                        Map.of("summary", "一组清爽的夏日图片", "visualDirection", "浅绿背景，自然光", "question", "", "items", specs), null, null));
    }

    private ProviderResult imageResult() {
        byte[] bytes = imageRenderer.png(new java.awt.image.BufferedImage(480, 640, java.awt.image.BufferedImage.TYPE_INT_RGB));
        return new ProviderResult(true, "TEST", "remote-job", Map.of("status", "SUCCEEDED",
                "imageBase64", Base64.getEncoder().encodeToString(bytes), "_model", "fixture-image"), null, null);
    }

    private Task runImageTask(String queue, TaskHandler handler) {
        Task task = tasks.claim(queue, 1).getFirst();
        Map<String, Object> result = TenantContext.runAs(task.getTenantId(), () -> handler.handle(task));
        assertThat(tasks.succeed(task.getId(), task.getAttempts(), result)).isTrue();
        return task;
    }

    @Test
    void imageModelsMustBeConfiguredAndCreationIsIdempotentUnderConcurrency() throws Exception {
        var request = imageRequest("image-concurrent", "POSTER", 1, List.of());
        assertThat(code(() -> TenantContext.runAs(tenantA, () -> imageCreations.create(request, null)))).isEqualTo(4001);
        enableImageModels();
        var ids = concurrent(() -> TenantContext.runAs(tenantA, () -> imageCreations.create(request, null).id()));
        assertThat(ids.getFirst()).isEqualTo(ids.getLast());
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(ids.getFirst()).quality())).isEqualTo("4K");
        assertThat(owner.queryForObject("select count(*) from image_creations", Integer.class)).isEqualTo(1);
        assertThat(owner.queryForObject("select count(*) from tasks where type='IMAGE_PLAN'", Integer.class)).isEqualTo(1);
        var changed = new ImageDtos.Create(request.requestKey(), "POSTER", "另一条需求", List.of(), "3:4", "480P", 1, "LOCAL", "POSTER", "朋友圈", "帮我搭配", null);
        assertThat(code(() -> TenantContext.runAs(tenantA, () -> imageCreations.create(changed, null)))).isEqualTo(1400);
        assertThat(code(() -> TenantContext.runAs(tenantB, () -> imageCreations.get(ids.getFirst())))).isEqualTo(1404);
        assertThat(TenantContext.runAs(tenantB, () -> imageCreations.history("POSTER", 0, 20).total())).isZero();
    }

    @Test
    void imageWorkflowPollsSavesWorksAndEditsTextUsingOriginalImage() {
        imageConfig.setQualities(List.of("480P"));
        enableImageModels(); planImageCount(1);
        when(imageGateway.invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY)))
                .thenReturn(new ProviderResult(true, "TEST", "remote-job", Map.of("status", "RUNNING"), null, null))
                .thenReturn(imageResult());
        var created = TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("image-roundtrip", "POSTER", 1, List.of()), null));
        runImageTask("DEFAULT", imagePlanHandler);
        runImageTask("IMAGE", imageRenderHandler);
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).status())).isEqualTo("GENERATING");
        owner.update("update tasks set run_after=now() where type='IMAGE_RENDER'");
        runImageTask("IMAGE", imageRenderHandler);
        var done = TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()));
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(owner.queryForObject("select status from image_creations where id=?", String.class, created.id()))
                .isEqualTo("SUCCEEDED");
        assertThat(done.items().getFirst().url()).contains("work.png");
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.history("POSTER", 0, 20).items().getFirst().previewUrl())).contains("work.png");
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.works(0, 20).total())).isEqualTo(1);
        assertThat(TenantContext.runAs(tenantB, () -> imageCreations.works(0, 20).total())).isZero();
        var calls = ArgumentCaptor.forClass(ProviderRequest.class);
        verify(imageGateway, times(3)).invokeReal(calls.capture());
        assertThat(calls.getAllValues().get(1).idempotencyKey()).isEqualTo(calls.getAllValues().get(2).idempotencyKey());
        assertThat(calls.getAllValues().get(2).options().get("operation")).isEqualTo("query");
        clearInvocations(imageGateway);
        var edit = TenantContext.runAs(tenantA, () -> imageCreations.editText(created.id(), done.items().getFirst().id(),
                new ImageDtos.TextEdit("text-edit-version", "Summer", "Fresh today"), null));
        runImageTask("IMAGE", imageRenderHandler);
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(edit.id()).status())).isEqualTo("SUCCEEDED");
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).status())).isEqualTo("SUCCEEDED");
        var editCall = ArgumentCaptor.forClass(ProviderRequest.class);
        verify(imageGateway).invokeReal(editCall.capture());
        assertThat(editCall.getValue().prompt()).contains("Headline: Summer", "Caption: Fresh today");
        var references = (List<?>) editCall.getValue().options().get("references");
        assertThat(references).hasSize(1);
        assertThat(((Map<?, ?>) references.getFirst()).get("dataUrl").toString()).startsWith("data:image/png;base64,");
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.itemSnapshot(edit.items().getFirst().id()).getSpec().editSourceItemId()))
                .isEqualTo(done.items().getFirst().id());
    }

    @Test
    void imageReferenceOwnershipAndFileReadinessAreEnforced() {
        enableImageModels();
        var ticket = TenantContext.runAs(tenantA, () -> assets.presignUpload(new AssetDtos.PresignRequest("商品", "IMAGE", "image/png"), null));
        var req = imageRequest("reference-ready", "PRODUCT_SET", 3, List.of(new ImageDtos.Reference(ticket.assetId(), "SUBJECT")));
        assertThat(code(() -> TenantContext.runAs(tenantA, () -> imageCreations.create(req, null)))).isEqualTo(1400);
        assertThat(code(() -> TenantContext.runAs(tenantB, () -> imageCreations.create(req, null)))).isEqualTo(3001);
        assertThat(code(() -> TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("missing-subject", "PRODUCT_SET", 3, List.of()), null)))).isEqualTo(1400);
    }

    @Test
    void imageSuiteRetriesOnlyFailedItemAndKeepsSuccessfulWorks() {
        imageConfig.setQualities(List.of("480P"));
        enableImageModels(); planImageCount(3);
        var ticket = TenantContext.runAs(tenantA, () -> assets.presignUpload(new AssetDtos.PresignRequest("商品", "IMAGE", "image/png"), null));
        byte[] bytes = imageRenderer.png(new java.awt.image.BufferedImage(480, 640, java.awt.image.BufferedImage.TYPE_INT_RGB));
        imageStorage.put(ticket.storageKey(), bytes, "image/png");
        TenantContext.runAs(tenantA, () -> assets.confirmUpload(ticket.assetId(), new AssetDtos.ConfirmRequest((long)bytes.length, null), null));
        owner.update("delete from tasks where type='ASSET_PROBE'");
        when(imageGateway.invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY)))
                .thenReturn(imageResult()).thenReturn(new ProviderResult(true, "TEST", "failed-job", Map.of("status", "FAILED"), null, null))
                .thenReturn(imageResult());
        var created = TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("suite-partial", "PRODUCT_SET", 3,
                List.of(new ImageDtos.Reference(ticket.assetId(), "SUBJECT"))), null));
        runImageTask("DEFAULT", imagePlanHandler);
        for (int n=0; n<3; n++) runImageTask("IMAGE", imageRenderHandler);
        var partial = TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()));
        assertThat(partial.status()).isEqualTo("PARTIAL");assertThat(partial.completed()).isEqualTo(2);
        var failed = partial.items().get(1);
        TenantContext.runAs(tenantA, () -> imageCreations.retry(created.id(), failed.id(), failed.taskId(), null));
        TenantContext.runAs(tenantA, () -> imageCreations.retry(created.id(), failed.id(), failed.taskId(), null));
        assertThat(owner.queryForObject("select count(*) from tasks where type='IMAGE_RENDER' and status='PENDING'", Integer.class)).isEqualTo(1);
        clearInvocations(imageGateway);
        runImageTask("IMAGE", imageRenderHandler);
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).completed())).isEqualTo(3);
        verify(imageGateway, times(1)).invokeReal(any());
    }

    @Test
    void posterVariantReplansWithoutChangingTheTextEditFlow() {
        imageConfig.setQualities(List.of("480P"));
        enableImageModels(); planImageCount(1, "夏日新品", "欢迎到店");
        when(imageGateway.invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY))).thenReturn(imageResult());
        var created = TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("regenerate-source", "POSTER", 1, List.of()), null));
        runImageTask("DEFAULT", imagePlanHandler);runImageTask("IMAGE", imageRenderHandler);
        var original = TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()));
        clearInvocations(imageGateway);
        var revised = TenantContext.runAs(tenantA, () -> imageCreations.regenerate(created.id(), original.items().getFirst().id(), "regenerate-once", "LAYOUT", null));
        var repeated = TenantContext.runAs(tenantA, () -> imageCreations.regenerate(created.id(), original.items().getFirst().id(), "regenerate-once", "LAYOUT", null));
        assertThat(repeated.id()).isEqualTo(revised.id());
        assertThat(code(() -> TenantContext.runAs(tenantA, () -> imageCreations.regenerate(created.id(), original.items().getFirst().id(), "regenerate-once", "SCENE", null)))).isEqualTo(1400);
        assertThat(revised.status()).isEqualTo("QUEUED");
        runImageTask("DEFAULT", imagePlanHandler);
        runImageTask("IMAGE", imageRenderHandler);
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(revised.id()).status())).isEqualTo("SUCCEEDED");
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(revised.id()).items().getFirst().headline())).isEqualTo("夏日新品");
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(revised.id()).items().getFirst().caption())).isEqualTo("欢迎到店");
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).items().getFirst().url())).contains("work.png");
        var thread = TenantContext.runAs(tenantA, () -> imageCreations.thread(revised.id()));
        assertThat(thread).extracting(ImageDtos.View::id).containsExactly(created.id(), revised.id());
        assertThat(thread).allSatisfy(version -> assertThat(version.items().getFirst().url()).contains("work.png"));
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.rename(revised.id(), "  夏日活动海报  "))).isEqualTo("夏日活动海报");
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.history("POSTER", 0, 20).items().getFirst().title())).isEqualTo("夏日活动海报");
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.works(0, 20).items())).anyMatch(work -> work.creationId().equals(revised.id()) && work.title().equals("夏日活动海报"));
        assertThat(code(() -> TenantContext.runAs(tenantB, () -> imageCreations.thread(revised.id())))).isEqualTo(1404);
        assertThat(code(() -> TenantContext.runAs(tenantB, () -> imageCreations.rename(revised.id(), "越权改名")))).isEqualTo(1404);
        verify(imageGateway, times(1)).invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY));
        verify(imageGateway, times(1)).invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.TEXT_CREATIVE
                && r.prompt().contains("\"variation\":\"LAYOUT\"") && r.prompt().contains("\"previousPoster\"")));
    }

    @Test
    void repeatedPosterReferenceProvidesHistoryAndWarnsWithoutExtraGeneration() {
        imageConfig.setQualities(List.of("480P"));
        enableImageModels(); planImageCount(1);
        var ticket = TenantContext.runAs(tenantA, () -> assets.presignUpload(
                new AssetDtos.PresignRequest("招牌饮品", "IMAGE", "image/png"), null));
        byte[] reference = imageRenderer.png(new java.awt.image.BufferedImage(480, 640, java.awt.image.BufferedImage.TYPE_INT_RGB));
        imageStorage.put(ticket.storageKey(), reference, "image/png");
        TenantContext.runAs(tenantA, () -> assets.confirmUpload(ticket.assetId(),
                new AssetDtos.ConfirmRequest((long) reference.length, null), null));
        owner.update("delete from tasks where type='ASSET_PROBE'");
        when(imageGateway.invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY))).thenReturn(imageResult());
        var refs=List.of(new ImageDtos.Reference(ticket.assetId(), "SUBJECT"));
        var first=TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("poster-first", "POSTER", 1, refs), null));
        runImageTask("DEFAULT", imagePlanHandler);runImageTask("IMAGE", imageRenderHandler);
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(first.id()).items().getFirst().similarityWarning())).isFalse();
        clearInvocations(imageGateway);
        var second=TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("poster-second", "POSTER", 1, refs), null));
        runImageTask("DEFAULT", imagePlanHandler);runImageTask("IMAGE", imageRenderHandler);
        verify(imageGateway).invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.TEXT_CREATIVE
                && r.prompt().contains("recentPostersToAvoid")));
        verify(imageGateway, times(1)).invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY));
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(second.id()).items().getFirst().similarityWarning())).isTrue();
    }

    @Test
    void olderPostersWithoutReferenceFingerprintsStillProvideLayoutHistory() {
        imageConfig.setQualities(List.of("480P"));
        enableImageModels(); planImageCount(1);
        when(imageGateway.invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY))).thenReturn(imageResult());
        var first=TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("poster-legacy", "POSTER", 1, List.of()), null));
        runImageTask("DEFAULT", imagePlanHandler);runImageTask("IMAGE", imageRenderHandler);
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(first.id()).status())).isEqualTo("SUCCEEDED");
        clearInvocations(imageGateway);
        TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("poster-next", "POSTER", 1, List.of()), null));
        runImageTask("DEFAULT", imagePlanHandler);
        verify(imageGateway).invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.TEXT_CREATIVE
                && r.prompt().contains("recentPostersToAvoid")));
        verify(imageGateway, never()).invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY));
    }

    @Test
    void imagePlansAreFencedAfterLeaseExpiryAndExhaustionIsVisible() {
        enableImageModels(); planImageCount(1);
        var created = TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("plan-fencing", "POSTER", 1, List.of()), null));
        var task = tasks.claim("DEFAULT", 1).getFirst();
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.beginPlan(created.id(), task))).isTrue();
        expire(task.getId());
        TenantContext.runAs(tenantA, () -> {imageCreations.savePlan(created.id(), task,
                new ImageDtos.Plan("过期结果", "", List.of(new ImageDtos.Spec("主图", "提示", "", ""))));return null;});
        assertThat(owner.queryForObject("select count(*) from image_items", Integer.class)).isZero();
        owner.update("update tasks set attempts=max_attempts where id=?", task.getId());
        tasks.reclaimExpired();
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).status())).isEqualTo("INTERRUPTED");
    }

    @Test
    void persistedBackgroundIsRecoveredWithoutResubmittingToProvider() {
        imageConfig.setQualities(List.of("480P"));
        enableImageModels(); planImageCount(1);
        var created = TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("image-recover", "POSTER", 1, List.of()), null));
        runImageTask("DEFAULT", imagePlanHandler);
        var item = TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).items().getFirst());
        imageStorage.put("t"+tenantA+"/generated/"+item.id()+"/0/background.png",
                imageRenderer.png(new java.awt.image.BufferedImage(480, 640, java.awt.image.BufferedImage.TYPE_INT_RGB)), "image/png");
        clearInvocations(imageGateway);
        runImageTask("IMAGE", imageRenderHandler);
        verify(imageGateway, never()).invokeReal(any());
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).status())).isEqualTo("SUCCEEDED");
    }

    private Task submit(String key) {
        return TenantContext.runAs(tenantA, () -> tasks.submit("TEST", "TEST", Map.of("key", key), key, null));
    }
    @Test
    void claudeDirectorPromptIsPersistedAndReusedDuringImagePolling() {
        imageConfig.setQualities(List.of("480P"));
        enableImageModels();
        String prompt="Subject: tea; Lighting: studio lighting; Color: warm palette; Composition: asymmetric; Depth: deep focus; Quality: professional photography, sharp focus, 8K; Avoid: blurry, watermark";
        when(imageGateway.invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.TEXT_CREATIVE)))
                .thenReturn(new ProviderResult(true,"TEST",null,Map.of(
                        "summary", "茶饮摄影方案", "visualDirection", "studio lighting", "question", "",
                        "items", List.of(Map.of("role", "主图", "prompt", prompt, "headline", "", "caption", "")),
                        "_model", "claude-sonnet-4-6-ab"),null,null));
        when(imageGateway.invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY)))
                .thenReturn(new ProviderResult(true,"TEST","refined-job",Map.of("status","RUNNING"),null,null))
                .thenReturn(imageResult());
        var created=TenantContext.runAs(tenantA,()->imageCreations.create(imageRequest("refined-roundtrip","POSTER",1,List.of()),null));
        runImageTask("DEFAULT",imagePlanHandler);runImageTask("IMAGE",imageRenderHandler);
        owner.update("update tasks set run_after=now() where type='IMAGE_RENDER'");
        runImageTask("IMAGE",imageRenderHandler);
        var result=TenantContext.runAs(tenantA,()->imageCreations.get(created.id()));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        var item=TenantContext.runAs(tenantA,()->imageCreations.itemSnapshot(result.items().getFirst().id()));
        assertThat(item.getSpec().prompt()).contains("夏日自然光");
        verify(imageGateway,times(1)).invokeReal(argThat(r -> r != null && r.alias()==ModelAlias.TEXT_CREATIVE && r.tenantId().equals(tenantA)));
        var calls=ArgumentCaptor.forClass(ProviderRequest.class);
        verify(imageGateway,times(3)).invokeReal(calls.capture());
        var imageCalls=calls.getAllValues().stream().filter(r->r.alias()==ModelAlias.IMAGE_PRIMARY).toList();
        assertThat(imageCalls).hasSize(2);
        assertThat(imageCalls.getFirst().prompt()).startsWith(prompt).isEqualTo(imageCalls.getLast().prompt());
    }

    @Test
    void synchronousTimeoutIsNotAutomaticallyResubmittedAndExplicitRetryGetsANewGeneration() {
        imageConfig.setQualities(List.of("480P"));
        imageConfig.getGenerator().setProtocol(com.wuyao.growth.common.gateway.ImageModelProperties.Protocol.OPENAI);
        enableImageModels(); planImageCount(1);
        when(imageGateway.invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY)))
                .thenThrow(new IllegalStateException("test transport timeout")).thenReturn(imageResult());
        var created = TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("sync-timeout", "POSTER", 1, List.of()), null));
        runImageTask("DEFAULT", imagePlanHandler);
        clearInvocations(imageGateway);
        var task=runImageTask("IMAGE", imageRenderHandler);
        TenantContext.runAs(tenantA, () -> imageRenderHandler.handle(task));
        var failed=TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).items().getFirst());
        assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.error()).contains("中转站记录");
        verify(imageGateway,times(1)).invokeReal(any());
        TenantContext.runAs(tenantA, () -> imageCreations.retry(created.id(),failed.id(),failed.taskId(),null));
        runImageTask("IMAGE", imageRenderHandler);
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).status())).isEqualTo("SUCCEEDED");
        verify(imageGateway,times(2)).invokeReal(any());
    }

    @Test
    void synchronousIntentSurvivesLeaseReclaimWithoutSendingAgain() {
        imageConfig.getGenerator().setProtocol(com.wuyao.growth.common.gateway.ImageModelProperties.Protocol.OPENAI);
        enableImageModels(); planImageCount(1);
        var created = TenantContext.runAs(tenantA, () -> imageCreations.create(imageRequest("sync-crash", "POSTER", 1, List.of()), null));
        runImageTask("DEFAULT", imagePlanHandler);
        var item=TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).items().getFirst());
        var task=tasks.claim("IMAGE",1).getFirst();
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.reserveSynchronousSubmission(item.id(),task))).isTrue();
        expire(task.getId());tasks.reclaimExpired();owner.update("update tasks set run_after=now() where id=?",task.getId());
        clearInvocations(imageGateway);
        runImageTask("IMAGE",imageRenderHandler);
        verify(imageGateway,never()).invokeReal(any());
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).items().getFirst().error())).contains("上次提交状态尚未确认");
    }

    @Test
    void gptImage2AutomaticallyChoosesHighestQualityForEachRatio() {
        enableImageModels();imageConfig.getGenerator().setProtocol(com.wuyao.growth.common.gateway.ImageModelProperties.Protocol.OPENAI);
        imageConfig.getGenerator().setModel("gpt-image-2");
        var req=new ImageDtos.Create("too-large-square","POSTER","新品",List.of(),"1:1","4K",1,"LOCAL","POSTER","朋友圈","自动",null);
        var square=TenantContext.runAs(tenantA,()->imageCreations.create(req,null));
        assertThat(square.quality()).isEqualTo("1080P");
        var wide=new ImageDtos.Create("wide-highest","POSTER","新品",List.of(),"16:9","480P",1,"LOCAL","POSTER","朋友圈","自动",null);
        assertThat(TenantContext.runAs(tenantA,()->imageCreations.create(wide,null)).quality()).isEqualTo("4K");
        assertThat(owner.queryForObject("select count(*) from tasks",Integer.class)).isEqualTo(2);
        assertThat(imageCreations.capabilities().get("qualityRatios").toString()).contains("4K=[9:16, 16:9]");
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
