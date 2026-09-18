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
    @Autowired JwtService jwt;
    @Autowired TaskService tasks;
    @Autowired TaskRepository taskRepository;
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
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tasks, assets, refresh_tokens, sms_codes, users, tenants RESTART IDENTITY CASCADE");
        tenantA = owner.queryForObject("INSERT INTO tenants(name) VALUES ('A') RETURNING id", Long.class);
        tenantB = owner.queryForObject("INSERT INTO tenants(name) VALUES ('B') RETURNING id", Long.class);
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
