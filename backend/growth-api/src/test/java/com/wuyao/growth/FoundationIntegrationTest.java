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
import com.wuyao.growth.video.*;
import io.minio.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.core.io.ClassPathResource;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"growth.worker.enabled=false", "spring.data.redis.client-type=jedis", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN", "spring.config.import=", "growth.image.max-output-pixels=0",
        "management.endpoint.health.show-details=always", "growth.video.health.snapshot-cache-seconds=0",
        "growth.video.provider.api-key=test-only-video-key"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(FoundationIntegrationTest.HttpTestServer.class)
@Testcontainers
class FoundationIntegrationTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class HttpTestServer {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory> testHttpProtocol() {
            // NIO's Selector/Pipe loopback fails on this Windows JDK; NIO2 still serves real TCP HTTP.
            return factory -> factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
        }
    }
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
    @Autowired VideoWorkflowService videoWorkflows;
    @Autowired VideoWorkflowRepository videoWorkflowRepository;
    @Autowired com.wuyao.growth.common.metrics.VideoMetrics videoMetrics;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired VideoWorkflowObservations videoObservations;
    @org.springframework.boot.test.web.server.LocalServerPort int httpPort;
    @Autowired VideoProviderGateway videoProvider;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired VideoProviderJobRepository videoProviderJobs;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate appJdbc;
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
    void concurrentVideoRetriesResumeOneExistingJobAndReacquireExactlyOnePermit() throws Exception {
        var created = createVideo("video-concurrent-retry");
        var submit = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> {
            videoWorkflows.saveSubmission(created.id(), submit,
                    new VideoProviderGateway.SubmitResult("onlyrouter", "job-original", "RUNNING"));
            return null;
        });
        tasks.succeed(submit.getId(), submit.getAttempts(), Map.of());
        var current = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        TenantContext.runAs(tenantA, () -> { videoWorkflows.markFailed(created.id(), "VIDEO_TIMEOUT", "poll timed out"); return null; });
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
        assertThat(imageRateLimiter.getVideoGlobalActiveCount()).isZero();
        owner.update("update video_workflows set created_at=now()-interval '1 day', poll_round=360 where id=?", created.id());
        owner.update("update tasks set status='FAILED', lease_expires_at=NULL where id=?", current.taskId());

        var responses = concurrent(() -> TenantContext.runAs(tenantA,
                () -> videoWorkflows.retry(created.id(), current.taskId(), null)));

        assertThat(responses).extracting(VideoDtos.View::taskId).containsOnly(responses.getFirst().taskId());
        assertThat(responses.getFirst().taskId()).isNotEqualTo(current.taskId());
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);
        assertThat(imageRateLimiter.getVideoGlobalActiveCount()).isEqualTo(1);
        assertThat(owner.queryForObject("select retry_round from video_workflows where id=?", Integer.class, created.id())).isEqualTo(1);
        assertThat(owner.queryForObject("select count(*) from tasks where idempotency_key like 'video-poll-%-retry-1-%'", Integer.class)).isEqualTo(1);
        var poll = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        var provider = mock(VideoProviderGateway.class);
        when(provider.poll(eq("job-original"), anyString())).thenReturn(
                new VideoProviderGateway.PollResult("SUCCEEDED", "https://media.example/fresh.mp4", null));

        TenantContext.runAs(tenantA, () -> new VideoPollHandler(videoWorkflows, provider, videoWorkflowRepository).handle(poll));

        assertThat(TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()).status())).isEqualTo("IMPORTING");
        var imported = tasks.claim("MEDIA_CPU", 1).getFirst();
        assertThat(imported.getIdempotencyKey()).isEqualTo("video-import-" + created.id() + "-retry-1");
        String key = "t" + tenantA + "/generated-video/" + created.id() + "/retry-1/output.mp4";
        imageStorage.put(key, new byte[]{0, 1, 2, 3}, "video/mp4");
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveImportedVideo(created.id(), imported, key, 4, "video/mp4"); return null; });
        tasks.succeed(imported.getId(), imported.getAttempts(), Map.of());
        var beforeQa = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        assertThat(beforeQa.outputUrl()).isNull();
        assertThat(TenantContext.runAs(tenantA, () -> assetRepository.findById(beforeQa.outputAssetId()).orElseThrow().getStatus())).isEqualTo("PROCESSING");
        var qa = tasks.claim("MEDIA_CPU", 1).getFirst();
        assertThat(qa.getIdempotencyKey()).isEqualTo("video-qa-" + created.id() + "-retry-1");
        completeVideoQa(created.id(), qa, false);
        var delivered = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        assertThat(delivered.status()).isEqualTo("SUCCEEDED");
        assertThat(delivered.outputUrl()).isNotNull();
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
        assertThat(imageRateLimiter.getVideoGlobalActiveCount()).isZero();
        verify(provider, never()).submit(anyString(), anyString());
        assertThat(owner.queryForObject("select count(*) from video_provider_jobs where workflow_id=?", Integer.class, created.id())).isEqualTo(1);
        // A late old failure cannot destroy the delivered object or release another workflow's permit.
        TenantContext.runAs(tenantA, () -> { videoWorkflows.markFailed(created.id(), submit, "VIDEO_SUBMIT_FAILED", "late"); return videoWorkflows.cancel(created.id()); });
        assertThat(imageStorage.stat(key)).isPresent();
    }

    @Test
    void confirmedProviderFailureCreatesANewPaidGenerationAndRetainsJobHistory() {
        var created = createVideo("video-new-generation");
        var initial = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveSubmission(created.id(), initial,
                new VideoProviderGateway.SubmitResult("onlyrouter", "job-failed", "FAILED")); return null; });
        tasks.succeed(initial.getId(), initial.getAttempts(), Map.of());
        var failed = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        assertThat(failed.retryMayCharge()).isTrue();
        assertThat(failed.retryHint()).contains("可能再次计费");
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
        TenantContext.runAs(tenantA, () -> videoWorkflows.retry(created.id(), failed.taskId(), null));
        var retry = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        var provider = mock(VideoProviderGateway.class);
        when(provider.buildSubmitRequest(any())).thenReturn(Map.of("prompt", "旋转"));
        when(provider.submit(anyString(), eq("video-" + created.id() + "-submit-generation-1")))
                .thenReturn(new VideoProviderGateway.SubmitResult("onlyrouter", "job-new", "RUNNING"));

        TenantContext.runAs(tenantA, () -> new VideoSubmitHandler(videoWorkflows, provider, videoWorkflowRepository).handle(retry));
        tasks.succeed(retry.getId(), retry.getAttempts(), Map.of());

        var poll = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.savePoll(created.id(), poll,
                new VideoProviderGateway.PollResult("RUNNING", null, null)); return null; });
        assertThat(owner.queryForObject("select count(*) from video_provider_jobs where workflow_id=?", Integer.class, created.id())).isEqualTo(2);
        assertThat(owner.queryForObject("select status from video_provider_jobs where provider_job_id='job-failed'", String.class)).isEqualTo("FAILED");
        assertThat(owner.queryForObject("select poll_round from video_provider_jobs where provider_job_id='job-new'", Integer.class)).isEqualTo(1);
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);
        verify(provider).submit(anyString(), eq("video-" + created.id() + "-submit-generation-1"));
    }

    @Test
    void aVideoRetryRolledBackByItsOuterTransactionReturnsTheNewRedisPermit() {
        var created = createVideo("video-retry-rollback");
        TenantContext.runAs(tenantA, () -> { videoWorkflows.markFailed(created.id(), "VIDEO_TIMEOUT", "unsent"); return null; });
        int taskCount = owner.queryForObject("select count(*) from tasks", Integer.class);
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();

        TenantContext.runAs(tenantA, () -> transactions.execute(status -> {
            var resumed = videoWorkflows.retry(created.id(), created.taskId(), null);
            assertThat(resumed.status()).isEqualTo("QUEUED");
            assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);
            status.setRollbackOnly();
            return null;
        }));

        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
        assertThat(imageRateLimiter.getVideoGlobalActiveCount()).isZero();
        var reloaded = TenantContext.runAs(tenantA, () -> videoWorkflowRepository.findById(created.id()).orElseThrow());
        assertThat(reloaded.getStatus()).isEqualTo("FAILED");
        assertThat(reloaded.getTaskId()).isEqualTo(created.taskId());
        assertThat(reloaded.getRetryRound()).isZero();
        assertThat(reloaded.isVideoConcurrencyPermitHeld()).isFalse();
        assertThat(owner.queryForObject("select count(*) from tasks", Integer.class)).isEqualTo(taskCount);
    }

    @Test
    void aRolledBackVideoTerminalTransitionKeepsItsPermitUntilTheStateCommits() {
        var created = createVideo("video-terminal-rollback");
        TenantContext.runAs(tenantA, () -> transactions.execute(status -> {
            videoWorkflows.markFailed(created.id(), "VIDEO_TIMEOUT", "timeout");
            assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);
            status.setRollbackOnly();
            return null;
        }));
        assertThat(TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()).status())).isEqualTo("QUEUED");
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);
        TenantContext.runAs(tenantA, () -> videoWorkflows.cancel(created.id()));
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
    }

    @Test
    void videoRetryCannotExceedTheTenantPermitLimit() {
        var failed = createVideo("video-retry-limit-failed");
        TenantContext.runAs(tenantA, () -> { videoWorkflows.markFailed(failed.id(), "VIDEO_TIMEOUT", "unsent"); return null; });
        createVideo("video-retry-limit-active1");
        createVideo("video-retry-limit-active2");
        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> videoWorkflows.retry(failed.id(), failed.taskId(), null)))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(2);
        assertThat(TenantContext.runAs(tenantA, () -> videoWorkflowRepository.findById(failed.id()).orElseThrow().getRetryRound())).isZero();
    }

    @Test
    void reclaimedVideoTaskResumesItsOriginalJobWithoutDoubleAcquiring() {
        var created = createVideo("video-interrupted-retry");
        var submit = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveSubmission(created.id(), submit,
                new VideoProviderGateway.SubmitResult("onlyrouter", "job-interrupted", "RUNNING")); return null; });
        tasks.succeed(submit.getId(), submit.getAttempts(), Map.of());
        var poll = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        owner.update("update tasks set attempts=max_attempts, lease_expires_at=now()-interval '1 minute' where id=?", poll.getId());
        assertThat(tasks.reclaimExpired()).isEqualTo(1);
        var interrupted = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        assertThat(interrupted.status()).isEqualTo("INTERRUPTED");
        TenantContext.runAs(tenantA, () -> videoWorkflows.retry(created.id(), interrupted.taskId(), null));
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);
        assertThat(imageRateLimiter.getVideoGlobalActiveCount()).isEqualTo(1);
        assertThat(TenantContext.runAs(tenantA, () -> videoWorkflowRepository.findById(created.id()).orElseThrow().getProviderJobId())).isEqualTo("job-interrupted");
    }

    @Test
    void aDefinitelyUnsentVideoCanBeRetriedAndSubmittedSuccessfully() {
        var created = createVideo("video-unsent-retry");
        TenantContext.runAs(tenantA, () -> { videoWorkflows.markFailed(created.id(), "VIDEO_TIMEOUT", "unsent timeout"); return null; });
        owner.update("update tasks set status='FAILED' where id=?", created.taskId());
        TenantContext.runAs(tenantA, () -> videoWorkflows.retry(created.id(), created.taskId(), null));
        var task = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        var provider = mock(VideoProviderGateway.class);
        when(provider.buildSubmitRequest(any())).thenReturn(Map.of("prompt", "旋转"));
        when(provider.submit(anyString(), eq("video-" + created.id() + "-submit")))
                .thenReturn(new VideoProviderGateway.SubmitResult("onlyrouter", "job-unsent", "RUNNING"));
        TenantContext.runAs(tenantA, () -> new VideoSubmitHandler(videoWorkflows, provider, videoWorkflowRepository).handle(task));
        assertThat(TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()).status())).isEqualTo("GENERATING");
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);
        verify(provider).submit(anyString(), eq("video-" + created.id() + "-submit"));
    }

    @Test
    void aDispatchedVideoWithNoKnownProviderJobCannotBeManuallyResubmitted() {
        var created = createVideo("video-unknown-retry");
        var task = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        String original = TenantContext.runAs(tenantA,
                () -> videoWorkflows.prepareSubmissionRequest(created.id(), task, Map.of("prompt", "旋转")).orElseThrow());
        TenantContext.runAs(tenantA, () -> { videoWorkflows.markFailed(created.id(), task, "VIDEO_SUBMIT_FAILED", "response lost"); return null; });
        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> videoWorkflows.retry(created.id(), created.taskId(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("避免重复计费");
        assertThat(owner.queryForObject("select provider_submit_body from video_workflows where id=?", String.class, created.id())).isEqualTo(original);
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
    }

    @Test
    void videoCancelEndpointReturnsCancelledAndRemainsTerminalWithoutReleasingTwice() throws Exception {
        Long user = owner.queryForObject("INSERT INTO users(tenant_id,phone) VALUES (?,?) RETURNING id", Long.class, tenantA, "13800000001");
        Long other = owner.queryForObject("INSERT INTO users(tenant_id,phone) VALUES (?,?) RETURNING id", Long.class, tenantB, "13800000002");
        var created = createVideo("video-http-cancel");
        var submit = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        String path = "/api/video/workflows/" + created.id();
        String token = jwt.issueAccessToken(user, tenantA, "13800000001");
        String otherToken = jwt.issueAccessToken(other, tenantB, "13800000002");
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);

        mvc.perform(post(path + "/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/cancel").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(post(path + "/cancel").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.stage").value("CANCELLED"))
                .andExpect(jsonPath("$.data.retryable").value(false));
        assertThat(taskRepository.findById(submit.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
        assertThat(imageRateLimiter.getVideoGlobalActiveCount()).isZero();
        assertThat(owner.queryForObject("SELECT video_concurrency_permit_held FROM video_workflows WHERE id=?", Boolean.class, created.id())).isFalse();

        // A stale callback must not revive the workflow or persist a supplier job.
        TenantContext.runAs(tenantA, () -> {
            assertThat(videoWorkflows.beginSubmit(created.id(), submit)).isFalse();
            videoWorkflows.saveSubmission(created.id(), submit, new VideoProviderGateway.SubmitResult("test", "late-job", "RUNNING"));
            return null;
        });
        assertThat(owner.queryForObject("SELECT count(*) FROM video_provider_jobs WHERE workflow_id=?", Integer.class, created.id())).isZero();
        createVideo("video-active-after-cancel");
        mvc.perform(post(path + "/cancel").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.stage").value("CANCELLED"));
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);
        assertThat(imageRateLimiter.getVideoGlobalActiveCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBMIT", "POLL"})
    void providerCancellationIsPersistedWithTheCanonicalSpellingInBothFields(String phase) {
        var created = createVideo("video-provider-cancel-" + phase);
        var submit = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> {
            videoWorkflows.saveSubmission(created.id(), submit, new VideoProviderGateway.SubmitResult("test", "job-cancel", phase.equals("SUBMIT") ? "CANCELLED" : "RUNNING"));
            return null;
        });
        if (phase.equals("POLL")) {
            tasks.succeed(submit.getId(), submit.getAttempts(), Map.of());
            var poll = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
            TenantContext.runAs(tenantA, () -> {
                videoWorkflows.savePoll(created.id(), poll, new VideoProviderGateway.PollResult("CANCELLED", null, "provider canceled"));
                return null;
            });
        }
        var current = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        assertThat(current.providerStatus()).isEqualTo("CANCELLED");
        // Provider cancellation keeps the existing generation-failure policy.
        assertThat(current.status()).isEqualTo("FAILED");
        assertThat(current.errorCode()).isEqualTo("VIDEO_PROVIDER_GENERATION_FAILED");
        assertThat(current.retryable()).isTrue();
        assertThat(owner.queryForObject("SELECT status FROM video_provider_jobs WHERE workflow_id=?", String.class, created.id())).isEqualTo("CANCELLED");
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
        assertThat(imageRateLimiter.getVideoGlobalActiveCount()).isZero();
    }

    @Test
    void videoRetryEndpointUsesJwtIdentityValidatesTaskIdAndPreservesRls() throws Exception {
        Long user = owner.queryForObject("INSERT INTO users(tenant_id,phone) VALUES (?,?) RETURNING id", Long.class, tenantA, "13800000001");
        Long other = owner.queryForObject("INSERT INTO users(tenant_id,phone) VALUES (?,?) RETURNING id", Long.class, tenantB, "13800000002");
        var created = createVideo("video-http-retry");
        TenantContext.runAs(tenantA, () -> { videoWorkflows.markFailed(created.id(), "VIDEO_TIMEOUT", "unsent"); return null; });
        String path = "/api/video/workflows/" + created.id() + "/retry";
        String token = jwt.issueAccessToken(user, tenantA, "13800000001");
        String otherToken = jwt.issueAccessToken(other, tenantB, "13800000002");
        String body = "{\"taskId\":" + created.taskId() + "}";
        mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", "Bearer " + otherToken).contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.status").value("QUEUED"));
        Long newTask = owner.queryForObject("select task_id from video_workflows where id=?", Long.class, created.id());
        assertThat(owner.queryForObject("select created_by from tasks where id=?", Long.class, newTask)).isEqualTo(user);
        assertThat(imageRateLimiter.getVideoActiveCount(tenantB)).isZero();
        assertThat(owner.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid='public.video_workflows'::regclass", Boolean.class)).isTrue();
        assertThat(owner.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid='public.video_provider_jobs'::regclass", Boolean.class)).isTrue();
    }

    @Test
    void qaFailureDeletesUnpublishedVideoAndRetryReimportsOriginalProviderJob() {
        var created = createVideo("video-qa-cleanup-retry");
        var submit = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveSubmission(created.id(), submit,
                new VideoProviderGateway.SubmitResult("onlyrouter", "job-qa", "RUNNING")); return null; });
        tasks.succeed(submit.getId(), submit.getAttempts(), Map.of());
        var poll = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.savePoll(created.id(), poll,
                new VideoProviderGateway.PollResult("SUCCEEDED", "https://media.example/video.mp4", null)); return null; });
        var imported = tasks.claim("MEDIA_CPU", 1).getFirst();
        String key = "t" + tenantA + "/generated-video/" + created.id() + "/output.mp4";
        imageStorage.put(key, new byte[]{0, 1}, "video/mp4");
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveImportedVideo(created.id(), imported, key, 2, "video/mp4"); return null; });
        tasks.succeed(imported.getId(), imported.getAttempts(), Map.of());
        var candidate = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        var qa = tasks.claim("MEDIA_CPU", 1).getFirst();
        completeVideoQa(created.id(), qa, true);
        var failed = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("VIDEO_ASSET_INVALID");
        assertThat(failed.outputAssetId()).isNull();
        assertThat(imageStorage.stat(key)).isEmpty();
        assertThat(TenantContext.runAs(tenantA, () -> assetRepository.findById(candidate.outputAssetId()).orElseThrow().getStatus())).isEqualTo("INVALID");
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
        var recovered = TenantContext.runAs(tenantA, () -> videoWorkflows.retry(created.id(), failed.taskId(), null));
        assertThat(recovered.status()).isEqualTo("GENERATING");
        assertThat(TenantContext.runAs(tenantA, () -> videoWorkflowRepository.findById(created.id()).orElseThrow().getProviderJobId())).isEqualTo("job-qa");
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);
    }

    @Test
    void cancelCleansTheVideoCandidateAndALateImportCannotRestoreIt() {
        var created = createVideo("video-cancel-cleanup");
        var submit = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveSubmission(created.id(), submit,
                new VideoProviderGateway.SubmitResult("onlyrouter", "job-cancel", "RUNNING")); return null; });
        tasks.succeed(submit.getId(), submit.getAttempts(), Map.of());
        var poll = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.savePoll(created.id(), poll,
                new VideoProviderGateway.PollResult("SUCCEEDED", "https://media.example/video.mp4", null)); return null; });
        var imported = tasks.claim("MEDIA_CPU", 1).getFirst();
        String key = "t" + tenantA + "/generated-video/" + created.id() + "/output.mp4";
        imageStorage.put(key, new byte[]{0, 1}, "video/mp4");
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveImportedVideo(created.id(), imported, key, 2, "video/mp4"); return null; });
        var candidate = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        owner.update("update assets set created_at=now()-interval '3 hours' where id=?", candidate.outputAssetId());
        assets.cleanupAbandonedUploads();
        assertThat(imageStorage.stat(key)).isPresent();
        assertThat(TenantContext.runAs(tenantA, () -> assetRepository.findById(candidate.outputAssetId()))).isPresent();

        TenantContext.runAs(tenantA, () -> videoWorkflows.cancel(created.id()));

        assertThat(imageStorage.stat(key)).isEmpty();
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
        assertThat(TenantContext.runAs(tenantA, () -> assetRepository.findById(candidate.outputAssetId()).orElseThrow().getStatus())).isEqualTo("INVALID");
        // Simulate a download that finishes after cancel's first deletion.
        imageStorage.put(key, new byte[]{0, 1}, "video/mp4");
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveImportedVideo(created.id(), imported, key, 2, "video/mp4"); return null; });
        assertThat(imageStorage.stat(key)).isEmpty();
        assertThat(TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()).status())).isEqualTo("CANCELLED");
    }

    @Test
    void reservedVideoImportKeysAreDurableAndRecoveredWithoutAnAssetRow() {
        var created = createVideo("video-durable-import-key");
        var imported = prepareVideoImport(created.id(), "job-reserved");
        String key = TenantContext.runAs(tenantA, () -> transactions.execute(status -> {
            String reserved = videoWorkflows.reserveImportKey(created.id(), imported).orElseThrow();
            assertThat(owner.queryForObject("select pending_cleanup_keys::text from video_workflows where id=?", String.class, created.id())).contains(reserved);
            status.setRollbackOnly();
            return reserved;
        }));
        assertThat(owner.queryForObject("select pending_cleanup_keys::text from video_workflows where id=?", String.class, created.id())).contains(key);
        assertThat(TenantContext.runAs(tenantA, () -> assetRepository.findByStorageKey(key))).isEmpty();
        // Model a crash after PUT but before creating an Asset: terminal cleanup uses the reserved key.
        imageStorage.put(key, new byte[]{0, 1}, "video/mp4");
        assertThatThrownBy(() -> TenantContext.runAs(tenantB, () -> { videoWorkflows.cleanupImportObject(created.id(), key); return null; }))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(imageStorage.stat(key)).isPresent();
        TenantContext.runAs(tenantA, () -> videoWorkflows.cancel(created.id()));
        assertThat(imageStorage.stat(key)).isEmpty();
        assertThat(TenantContext.runAs(tenantA, () -> videoWorkflowRepository.findById(created.id()).orElseThrow().getPendingCleanupKeys())).isEmpty();
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
    }

    @Test
    void aPublishedVideoSurvivesLaterStatusChangesAndEvenAMissingAssetRecord() {
        var created = createVideo("video-published-protection");
        var imported = prepareVideoImport(created.id(), "job-published");
        String key = TenantContext.runAs(tenantA, () -> videoWorkflows.reserveImportKey(created.id(), imported).orElseThrow());
        imageStorage.put(key, new byte[]{0, 1}, "video/mp4");
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveImportedVideo(created.id(), imported, key, 2, "video/mp4"); return null; });
        tasks.succeed(imported.getId(), imported.getAttempts(), Map.of());
        var qa = tasks.claim("MEDIA_CPU", 1).getFirst();
        completeVideoQa(created.id(), qa, false);
        var delivered = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        assertThat(owner.queryForObject("select output_published_at is not null from video_workflows where id=?", Boolean.class, created.id())).isTrue();
        assertThat(TenantContext.runAs(tenantA, () -> videoWorkflowRepository.findById(created.id()).orElseThrow().getPendingCleanupKeys())).isEmpty();
        owner.update("update video_workflows set status='FAILED' where id=?", created.id());
        owner.update("delete from assets where id=?", delivered.outputAssetId());

        TenantContext.runAs(tenantA, () -> {
            videoWorkflows.cancel(created.id());
            videoWorkflows.markFailed(created.id(), "VIDEO_IMPORT_FAILED", "late failure");
            videoWorkflows.cleanupImportObject(created.id(), key);
            videoWorkflows.saveImportedVideo(created.id(), imported, key, 2, "video/mp4");
            return null;
        });

        assertThat(imageStorage.stat(key)).isPresent();
        assertThat(owner.queryForObject("select output_storage_key from video_workflows where id=?", String.class, created.id())).isEqualTo(key);
        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> videoWorkflows.retry(created.id(), qa.getId(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("已通过校验并交付");
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
    }

    @Test
    void videoV22CorrectsAllCancellationFieldsAcrossTenantsUnderForcedRls() throws Exception {
        String migration = new ClassPathResource("db/migration/V22__video_cancelled_status.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection connection = ownerConnection(); var statement = connection.createStatement()) {
            try {
                statement.execute("CREATE ROLE video_v22_owner NOLOGIN NOSUPERUSER NOBYPASSRLS");
                statement.execute("CREATE SCHEMA video_v22_upgrade AUTHORIZATION video_v22_owner");
                statement.execute("SET ROLE video_v22_owner");
                statement.execute("SET search_path TO video_v22_upgrade");
                statement.execute("CREATE TABLE tenants(id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO tenants VALUES (1), (2)");
                statement.execute("""
                        CREATE TABLE video_workflows (
                            id BIGINT PRIMARY KEY, tenant_id BIGINT, status VARCHAR(32), stage VARCHAR(32),
                            provider_status VARCHAR(32), updated_at TIMESTAMPTZ NOT NULL DEFAULT '2020-01-01T00:00:00Z'
                        )
                        """);
                statement.execute("""
                        CREATE TABLE video_provider_jobs (
                            id BIGINT PRIMARY KEY, tenant_id BIGINT, workflow_id BIGINT, status VARCHAR(32),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT '2020-01-01T00:00:00Z'
                        )
                        """);
                for (String table : List.of("video_workflows", "video_provider_jobs")) {
                    statement.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
                    statement.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
                    statement.execute("CREATE POLICY tenant_isolation ON " + table + " USING "
                            + "(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint) "
                            + "WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)");
                }
                for (int tenant : List.of(1, 2)) {
                    statement.execute("SELECT set_config('app.tenant_id','" + tenant + "',false)");
                    int base = tenant * 10;
                    statement.execute("INSERT INTO video_workflows(id,tenant_id,status,stage,provider_status) VALUES "
                            + "(" + (base + 1) + "," + tenant + ",'CANCELED','CANCELED','CANCELED'),"
                            + "(" + (base + 2) + "," + tenant + ",'CANCELLED','CANCELLED','CANCELLED'),"
                            + "(" + (base + 3) + "," + tenant + ",'GENERATING','POLL','CANCELED'),"
                            + "(" + (base + 4) + "," + tenant + ",'FAILED','CANCELED','FAILED'),"
                            + "(" + (base + 5) + "," + tenant + ",'SUCCEEDED','DONE','SUCCEEDED'),"
                            + "(" + (base + 6) + "," + tenant + ",'QUEUED','SUBMIT',NULL)");
                    statement.execute("INSERT INTO video_provider_jobs(id,tenant_id,workflow_id,status) "
                            + "SELECT id,tenant_id,id,COALESCE(provider_status,'QUEUED') FROM video_workflows");
                }
                statement.execute("SELECT set_config('app.tenant_id','999',false)");
                statement.execute(migration);
                statement.execute(migration); // Reapplying the correction must be harmless.
                try (var rows = statement.executeQuery("SELECT current_setting('app.tenant_id')")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("999");
                }
                List<List<String>> expected = List.of(
                        List.of("CANCELLED", "CANCELLED", "CANCELLED", "CANCELLED"),
                        List.of("CANCELLED", "CANCELLED", "CANCELLED", "CANCELLED"),
                        List.of("GENERATING", "POLL", "CANCELLED", "CANCELLED"),
                        List.of("FAILED", "CANCELLED", "FAILED", "FAILED"),
                        List.of("SUCCEEDED", "DONE", "SUCCEEDED", "SUCCEEDED"),
                        Arrays.asList("QUEUED", "SUBMIT", null, "QUEUED"));
                for (int tenant : List.of(1, 2)) {
                    statement.execute("SELECT set_config('app.tenant_id','" + tenant + "',false)");
                    try (var rows = statement.executeQuery("""
                            SELECT w.tenant_id,w.status,w.stage,w.provider_status,j.status,
                                   w.updated_at='2020-01-01T00:00:00Z'::timestamptz
                                   AND j.updated_at='2020-01-01T00:00:00Z'::timestamptz
                            FROM video_workflows w JOIN video_provider_jobs j ON j.workflow_id=w.id
                            ORDER BY w.id
                            """)) {
                        for (var statuses : expected) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getInt(1)).isEqualTo(tenant);
                            assertThat(Arrays.asList(rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5)))
                                    .isEqualTo(statuses);
                            assertThat(rows.getBoolean(6)).isTrue();
                        }
                        assertThat(rows.next()).isFalse();
                    }
                }
                try (var rows = statement.executeQuery("SELECT count(*),bool_and(relrowsecurity AND relforcerowsecurity) "
                        + "FROM pg_class WHERE oid IN ('video_v22_upgrade.video_workflows'::regclass,'video_v22_upgrade.video_provider_jobs'::regclass)")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                    assertThat(rows.getBoolean(2)).isTrue();
                }
            } finally {
                statement.execute("RESET ROLE");
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA IF EXISTS video_v22_upgrade CASCADE");
                statement.execute("DROP ROLE IF EXISTS video_v22_owner");
            }
        }
    }

    @Test
    void videoV20BackfillsPublicationForEveryTenantUnderForcedRls() throws Exception {
        String migration = new ClassPathResource("db/migration/V20__video_output_publication.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection connection = ownerConnection(); var statement = connection.createStatement()) {
            try {
                statement.execute("CREATE ROLE video_v20_owner NOLOGIN NOSUPERUSER NOBYPASSRLS");
                statement.execute("CREATE SCHEMA video_v20_upgrade AUTHORIZATION video_v20_owner");
                statement.execute("SET ROLE video_v20_owner");
                statement.execute("SET search_path TO video_v20_upgrade");
                statement.execute("CREATE TABLE tenants(id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO tenants VALUES (1), (2)");
                statement.execute("CREATE TABLE video_workflows(id BIGINT PRIMARY KEY, tenant_id BIGINT, status TEXT, updated_at TIMESTAMPTZ, progress INT)");
                statement.execute("ALTER TABLE video_workflows ENABLE ROW LEVEL SECURITY");
                statement.execute("ALTER TABLE video_workflows FORCE ROW LEVEL SECURITY");
                statement.execute("CREATE POLICY tenant_isolation ON video_workflows USING "
                        + "(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint) "
                        + "WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)");
                statement.execute("SELECT set_config('app.tenant_id','1',false)");
                statement.execute("INSERT INTO video_workflows VALUES (11,1,'SUCCEEDED',now(),100), (12,1,'FAILED',now(),94), (13,1,'FAILED',now(),100)");
                statement.execute("SELECT set_config('app.tenant_id','2',false)");
                statement.execute("INSERT INTO video_workflows VALUES (21,2,'SUCCEEDED',now(),100), (22,2,'QA',now(),94)");
                statement.execute(migration);
                for (String tenant : List.of("1", "2")) {
                    statement.execute("SELECT set_config('app.tenant_id','" + tenant + "',false)");
                    try (var rows = statement.executeQuery("SELECT count(*), count(output_published_at), "
                            + "bool_and(output_published_at IS NULL OR output_published_at=updated_at) FROM video_workflows")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isEqualTo(tenant.equals("1") ? 3 : 2);
                        assertThat(rows.getInt(2)).isEqualTo(tenant.equals("1") ? 2 : 1);
                        assertThat(rows.getBoolean(3)).isTrue();
                    }
                }
                try (var rows = statement.executeQuery("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class "
                        + "WHERE oid='video_v20_upgrade.video_workflows'::regclass")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isTrue();
                }
            } finally {
                statement.execute("RESET ROLE");
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA IF EXISTS video_v20_upgrade CASCADE");
                statement.execute("DROP ROLE IF EXISTS video_v20_owner");
            }
        }
    }

    private Task prepareVideoImport(Long id, String jobId) {
        var submit = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveSubmission(id, submit,
                new VideoProviderGateway.SubmitResult("onlyrouter", jobId, "RUNNING")); return null; });
        tasks.succeed(submit.getId(), submit.getAttempts(), Map.of());
        var poll = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.savePoll(id, poll,
                new VideoProviderGateway.PollResult("SUCCEEDED", "https://media.example/video.mp4", null)); return null; });
        tasks.succeed(poll.getId(), poll.getAttempts(), Map.of());
        return tasks.claim("MEDIA_CPU", 1).getFirst();
    }

    private VideoDtos.View createVideo(String requestKey) {
        return TenantContext.runAs(tenantA, () -> videoWorkflows.create(new VideoDtos.Create(requestKey, "旋转", List.of(), null,
                "SEEDANCE_2_0_MINI", "auto", 5, "480p"), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_CONTENT", "WRAPPED_SIZE", "UNKNOWN_SIZE_VALID", "NETWORK", "NETWORK_FINAL", "HTTP404", "MISSING_URL"})
    void importRetryClassificationCommitsWorkflowStateAndKeepsRedisPermitsConsistent(String failure) throws Exception {
        var created = createVideo("import-class-" + failure.toLowerCase(Locale.ROOT));
        var importing = prepareVideoImport(created.id(), "import-job-" + failure);
        if (failure.equals("NETWORK_FINAL")) {
            owner.update("update tasks set attempts=3 where id=?", importing.getId());
            importing.setAttempts(3);
        }
        owner.update("update video_workflows set provider_result_url=? where id=?",
                failure.equals("MISSING_URL") ? null : "https://example.com/output.mp4", created.id());
        var http = mock(org.apache.hc.client5.http.classic.HttpClient.class);
        if (failure.startsWith("NETWORK")) {
            when(http.execute(any(org.apache.hc.client5.http.classic.methods.HttpGet.class),
                    any(org.apache.hc.core5.http.io.HttpClientResponseHandler.class))).thenThrow(new java.io.IOException("connection reset"));
        } else {
            var response = mock(org.apache.hc.core5.http.ClassicHttpResponse.class);
            when(response.getCode()).thenReturn(failure.equals("HTTP404") ? 404 : 200);
            var entity = mock(org.apache.hc.core5.http.HttpEntity.class);
            when(entity.getContentLength()).thenReturn(failure.equals("WRAPPED_SIZE") || failure.equals("UNKNOWN_SIZE_VALID") ? -1L : 32L);
            when(entity.getContentType()).thenReturn(failure.equals("INVALID_CONTENT") ? "text/html" : "video/mp4");
            when(entity.getContent()).thenAnswer(invocation -> new ByteArrayInputStream(new byte[32]));
            when(response.getEntity()).thenReturn(entity);
            when(http.execute(any(org.apache.hc.client5.http.classic.methods.HttpGet.class),
                    any(org.apache.hc.core5.http.io.HttpClientResponseHandler.class))).thenAnswer(invocation ->
                    ((org.apache.hc.core5.http.io.HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response));
        }
        // Only replace HTTP transport; lifecycle methods still run through the real transactional Spring service.
        var downloader = new VideoWorkflowService(videoWorkflowRepository, videoProviderJobs, assetRepository, assets, imageStorage,
                tasks, new ObjectMapper(), imageRateLimiter, http, mock(VideoMediaProbe.class), videoMetrics);
        org.springframework.test.util.ReflectionTestUtils.setField(downloader, "maxProviderBytes", failure.equals("WRAPPED_SIZE") ? 8L : 1024L);
        var service = mock(VideoWorkflowService.class, org.mockito.AdditionalAnswers.delegatesTo(videoWorkflows));
        doAnswer(invocation -> downloader.importUrl(invocation.getArgument(0), invocation.getArgument(1)))
                .when(service).importUrl(any(VideoWorkflow.class), anyString());
        var handler = new VideoImportHandler(service, videoWorkflowRepository);
        Throwable error = catchThrowable(() -> TenantContext.runAs(tenantA, () -> handler.handle(importing)));
        if (failure.equals("UNKNOWN_SIZE_VALID")) {
            assertThat(error).isNull();
            var current = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
            assertThat(current.status()).isEqualTo("QA");
            var output = TenantContext.runAs(tenantA, () -> assetRepository.findById(current.outputAssetId()).orElseThrow());
            assertThat(output.getStatus()).isEqualTo("PROCESSING");
            assertThat(imageStorage.stat(output.getStorageKey()).orElseThrow().sizeBytes()).isEqualTo(32);
            assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);
            verify(service, never()).cleanupImportObject(anyLong(), anyString());
            return;
        }
        var cleanupKey = ArgumentCaptor.forClass(String.class);
        verify(service).cleanupImportObject(eq(created.id()), cleanupKey.capture());
        assertThat(imageStorage.stat(cleanupKey.getValue())).isEmpty();
        assertThat(owner.queryForObject("select pending_cleanup_keys::text from video_workflows where id=?", String.class, created.id())).isEqualTo("[]");
        var current = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        if (failure.startsWith("NETWORK")) {
            boolean exhausted = failure.equals("NETWORK_FINAL");
            assertThat(error).isInstanceOf(VideoImportException.class).isNotInstanceOf(NonRetryableTaskException.class);
            assertThat(current.status()).isEqualTo(exhausted ? "FAILED" : "IMPORTING");
            assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(exhausted ? 0 : 1);
            assertThat(imageRateLimiter.getVideoGlobalActiveCount()).isEqualTo(exhausted ? 0 : 1);
            tasks.fail(importing.getId(), importing.getAttempts(), "HANDLER_ERROR", error.getMessage());
            Task waiting = taskRepository.findById(importing.getId()).orElseThrow();
            assertThat(waiting.getStatus()).isEqualTo(exhausted ? TaskStatus.FAILED : TaskStatus.PENDING);
            assertThat(waiting.getAttempts()).isEqualTo(exhausted ? 3 : 1);
            if (!exhausted) assertThat(waiting.getRunAfter()).isAfter(Instant.now().plusSeconds(15));
        } else {
            assertThat(error).isInstanceOf(NonRetryableTaskException.class);
            var permanent = (NonRetryableTaskException) error;
            tasks.failPermanently(importing.getId(), importing.getAttempts(), permanent.errorCode(), permanent.getMessage());
            Task failedTask = taskRepository.findById(importing.getId()).orElseThrow();
            assertThat(failedTask.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(failedTask.getAttempts()).isEqualTo(1);
            assertThat(current.status()).isEqualTo("FAILED");
            assertThat(current.errorCode()).isEqualTo(failure.equals("HTTP404") ? "VIDEO_IMPORT_FAILED" : "VIDEO_IMPORT_REJECTED");
            assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
            assertThat(imageRateLimiter.getVideoGlobalActiveCount()).isZero();
            assertThat(current.retryable()).isEqualTo(failure.equals("HTTP404"));
            if (failure.equals("WRAPPED_SIZE")) assertThat(permanent.errorCode()).isEqualTo("VIDEO_IMPORT_TOO_LARGE");
            if (failure.equals("HTTP404")) {
                assertThat(current.retryMayCharge()).isFalse();
                var restored = TenantContext.runAs(tenantA, () -> videoWorkflows.retry(created.id(), current.taskId(), null));
                assertThat(restored.status()).isEqualTo("GENERATING");
                assertThat(TenantContext.runAs(tenantA, () -> videoWorkflowRepository.findById(created.id()).orElseThrow().getProviderJobId()))
                        .isEqualTo("import-job-HTTP404");
                assertThat(taskRepository.findById(restored.taskId()).orElseThrow().getType()).isEqualTo("VIDEO_POLL");
                assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isEqualTo(1);
            }
        }
    }

    @Test
    void videoQaMetadataAndWarningsSurviveCommitAndAreSerializedByTheJwtScopedApi() throws Exception {
        var created = TenantContext.runAs(tenantA, () -> videoWorkflows.create(new VideoDtos.Create("qa-http-view",
                "旋转", List.of(), null, "SEEDANCE_2_5", "16:9", 5, "1080p"), null));
        var importing = prepareVideoImport(created.id(), "qa-http-job");
        String key = "t" + tenantA + "/generated-video/" + created.id() + "/output.mp4";
        imageStorage.put(key, new byte[]{0, 1, 2, 3}, "video/mp4");
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveImportedVideo(created.id(), importing, key, 4, "video/mp4"); return null; });
        tasks.succeed(importing.getId(), importing.getAttempts(), Map.of());
        completeVideoQa(created.id(), tasks.claim("MEDIA_CPU", 1).getFirst(), false);
        Long user = owner.queryForObject("insert into users(tenant_id, phone) values (?, '13800000001') returning id", Long.class, tenantA);
        mvc.perform(get("/api/video/workflows/" + created.id()).header("Authorization", "Bearer " + jwt.issueAccessToken(user, tenantA, "13800000001")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.resolution").value("1080p"))
                .andExpect(jsonPath("$.data.durationSeconds").value(5))
                .andExpect(jsonPath("$.data.actualWidth").value(640))
                .andExpect(jsonPath("$.data.actualHeight").value(480))
                .andExpect(jsonPath("$.data.actualDurationMs").value(5000))
                .andExpect(jsonPath("$.data.qaWarnings[0].code").value("VIDEO_RESOLUTION_MISMATCH"))
                .andExpect(jsonPath("$.data.qaWarnings[1].code").value("VIDEO_RATIO_MISMATCH"));
        assertThat(imageRateLimiter.getVideoActiveCount(tenantA)).isZero();
        TenantContext.runAs(tenantA, () -> videoWorkflows.cancel(created.id()));
        assertThat(imageStorage.stat(key)).isPresent();
    }

    @Test
    void videoHealthOverRealHttpSeesBothTenantsAndDetectsStageAgeRatherThanCreationAge() throws Exception {
        when(imageGateway.configured(ModelAlias.IMAGE_PRIMARY)).thenReturn(true);
        var a = createVideo("health-tenant-a");
        var b = TenantContext.runAs(tenantB, () -> videoWorkflows.create(new VideoDtos.Create(
                "health-tenant-b", "旋转", List.of(), null, "SEEDANCE_2_0_MINI", "auto", 5, "480p"), null));
        var c = TenantContext.runAs(tenantB, () -> videoWorkflows.create(new VideoDtos.Create(
                "health-tenant-b-qa", "旋转", List.of(), null, "SEEDANCE_2_0_MINI", "auto", 5, "480p"), null));
        tasks.claim("VIDEO_PROVIDER", 1);
        TenantContext.runAs(tenantB, () -> tasks.submit("VIDEO_QA", "MEDIA_CPU", Map.of("workflowId", c.id()), "health-media", null));
        owner.update("update video_workflows set created_at=now()-interval '1 day', attempt_started_at=now() where id=?", a.id());

        var up = readHttpHealth();
        var upVideo = new ObjectMapper().readTree(up.body()).path("components").path("video");
        assertThat(up.statusCode()).isEqualTo(200);
        assertThat(upVideo.path("status").asText()).isEqualTo("UP");
        var details = upVideo.path("details");
        assertThat(details.path("video_provider_configured").asBoolean()).isTrue();
        assertThat(details.path("stuck_workflows").asLong()).isZero();
        assertThat(details.path("queues").path("VIDEO_PROVIDER").path("pending").asLong()).isEqualTo(2);
        assertThat(details.path("queues").path("VIDEO_PROVIDER").path("running").asLong()).isEqualTo(1);
        assertThat(details.path("queues").path("MEDIA_CPU").path("pending").asLong()).isEqualTo(1);
        assertThat(details.path("queues").path("MEDIA_CPU").path("running").asLong()).isZero();
        assertThat(meters.get("video.concurrency.active").gauge().value()).isEqualTo(3);
        assertThat(meters.get("video.concurrency.tenant.max.active").gauge().value()).isEqualTo(2);
        System.out.println("VIDEO_ACTUATOR_HEALTH_HTTP_200 " + upVideo);

        owner.update("update video_workflows set status='GENERATING', stage='POLL', stage_started_at=now()-interval '7201 seconds' where id=?", a.id());
        owner.update("update video_workflows set status='IMPORTING', stage='IMPORT', stage_started_at=now()-interval '7201 seconds' where id=?", b.id());
        owner.update("update video_workflows set status='QA', stage='QA', stage_started_at=now()-interval '7201 seconds' where id=?", c.id());
        assertThat(videoWorkflowRepository.countStuck(tenantA, Instant.now())).isZero(); // FORCE RLS, no context.
        TenantContext.runAs(tenantA, () -> {
            assertThat(videoObservations.snapshot().stuckWorkflows()).isEqualTo(3);
            assertThat(TenantContext.get()).isEqualTo(tenantA);
            return null;
        });
        var down = readHttpHealth();
        var downVideo = new ObjectMapper().readTree(down.body()).path("components").path("video");
        assertThat(down.statusCode()).isEqualTo(503);
        assertThat(downVideo.path("status").asText()).isEqualTo("DOWN");
        assertThat(downVideo.path("details").path("stuck_workflows").asLong()).isEqualTo(3);
        assertThat(downVideo.path("details").path("reasons").toString()).contains("STUCK_WORKFLOWS");
        assertThat(meters.get("video.workflow.stuck").gauge().value()).isEqualTo(3);
        System.out.println("VIDEO_ACTUATOR_HEALTH_HTTP_503 " + downVideo);
    }

    private HttpHealthResponse readHttpHealth() throws Exception {
        var connection = (java.net.HttpURLConnection) java.net.URI.create("http://127.0.0.1:" + httpPort + "/actuator/health").toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        try {
            int status = connection.getResponseCode();
            try (var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                return new HttpHealthResponse(status, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    private record HttpHealthResponse(int statusCode, String body) {}

    @Test
    void configuredVideoPollIntervalReachesTheRealServiceAndTheNextTaskRunAfter() {
        String processValue = System.getenv("VIDEO_POLL_SECONDS");
        int configured = environment.getRequiredProperty("growth.video.poll-seconds", Integer.class);
        if (processValue != null) assertThat(configured).isEqualTo(Integer.parseInt(processValue));
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(videoWorkflows, "pollSeconds")).isEqualTo(configured);
        var created = createVideo("video-configured-poll");
        var submit = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> {
            videoWorkflows.saveSubmission(created.id(), submit, new VideoProviderGateway.SubmitResult("test", "configured-poll-job", "RUNNING"));
            return null;
        });
        tasks.succeed(submit.getId(), submit.getAttempts(), Map.of());
        var poll = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        Instant before = Instant.now();
        TenantContext.runAs(tenantA, () -> {
            videoWorkflows.savePoll(created.id(), poll, new VideoProviderGateway.PollResult("RUNNING", null, null));
            return null;
        });
        var current = TenantContext.runAs(tenantA, () -> videoWorkflows.get(created.id()));
        var waiting = taskRepository.findById(current.taskId()).orElseThrow();
        int delay = Math.min(60, Math.max(5, configured));
        assertThat(waiting.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(waiting.getRunAfter()).isBetween(before.plusSeconds(delay), Instant.now().plusSeconds(delay));
        System.out.printf("VIDEO_POLL_CONFIG_RUNTIME VIDEO_POLL_SECONDS=%s yaml=%d injected=%d next_task_delay_seconds=%d%n",
                processValue == null ? "unset" : processValue, configured,
                org.springframework.test.util.ReflectionTestUtils.getField(videoWorkflows, "pollSeconds"), delay);
    }

    @Test
    void videoStageMetricsFollowRealCommitsAndIncludeTheEntireAsynchronousPollingInterval() {
        long submitBefore = meters.get("video.submit.duration").tag("outcome", "succeeded").timer().count();
        long pollBefore = meters.get("video.poll.duration").tag("outcome", "succeeded").timer().count();
        double pollSecondsBefore = meters.get("video.poll.duration").tag("outcome", "succeeded").timer().totalTime(TimeUnit.SECONDS);
        long roundSamplesBefore = meters.get("video.poll.rounds").tag("outcome", "succeeded").summary().count();
        double roundSumBefore = meters.get("video.poll.rounds").tag("outcome", "succeeded").summary().totalAmount();
        long importBefore = meters.get("video.import.duration").tag("outcome", "succeeded").timer().count();
        long qaBefore = meters.get("video.qa.duration").tag("outcome", "succeeded").timer().count();
        var created = createVideo("metrics-workflow");
        var submit = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        var result = new VideoProviderGateway.SubmitResult("test", "metrics-job", "RUNNING");
        TenantContext.runAs(tenantA, () -> transactions.execute(status -> {
            videoWorkflows.saveSubmission(created.id(), submit, result);
            status.setRollbackOnly();
            return null;
        }));
        assertThat(meters.get("video.submit.duration").tag("outcome", "succeeded").timer().count()).isEqualTo(submitBefore);
        assertThat(owner.queryForObject("select provider_job_id from video_workflows where id=?", String.class, created.id())).isNull();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveSubmission(created.id(), submit, result); return null; });
        tasks.succeed(submit.getId(), submit.getAttempts(), Map.of());
        assertThat(meters.get("video.submit.duration").tag("outcome", "succeeded").timer().count()).isEqualTo(submitBefore + 1);
        owner.update("update video_workflows set stage_started_at=now()-interval '1 hour' where id=?", created.id());
        var firstPoll = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.savePoll(created.id(), firstPoll,
                new VideoProviderGateway.PollResult("RUNNING", null, null)); return null; });
        tasks.succeed(firstPoll.getId(), firstPoll.getAttempts(), Map.of());
        assertThat(meters.get("video.poll.duration").tag("outcome", "succeeded").timer().count()).isEqualTo(pollBefore);
        owner.update("update tasks set run_after=now() where queue='VIDEO_PROVIDER' and status='PENDING'");
        var finalPoll = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        TenantContext.runAs(tenantA, () -> { videoWorkflows.savePoll(created.id(), finalPoll,
                new VideoProviderGateway.PollResult("SUCCEEDED", "https://example.com/video.mp4", null)); return null; });
        tasks.succeed(finalPoll.getId(), finalPoll.getAttempts(), Map.of());
        assertThat(meters.get("video.poll.duration").tag("outcome", "succeeded").timer().count()).isEqualTo(pollBefore + 1);
        assertThat(meters.get("video.poll.duration").tag("outcome", "succeeded").timer().totalTime(TimeUnit.SECONDS) - pollSecondsBefore)
                .isBetween(3599.0, 3620.0);
        assertThat(meters.get("video.poll.rounds").tag("outcome", "succeeded").summary().count()).isEqualTo(roundSamplesBefore + 1);
        assertThat(meters.get("video.poll.rounds").tag("outcome", "succeeded").summary().totalAmount()).isEqualTo(roundSumBefore + 2);
        var importing = tasks.claim("MEDIA_CPU", 1).getFirst();
        String key = "t" + tenantA + "/generated-video/" + created.id() + "/output.mp4";
        imageStorage.put(key, new byte[]{0, 1, 2, 3}, "video/mp4");
        TenantContext.runAs(tenantA, () -> { videoWorkflows.saveImportedVideo(created.id(), importing, key, 4, "video/mp4"); return null; });
        tasks.succeed(importing.getId(), importing.getAttempts(), Map.of());
        completeVideoQa(created.id(), tasks.claim("MEDIA_CPU", 1).getFirst(), false);
        assertThat(meters.get("video.import.duration").tag("outcome", "succeeded").timer().count()).isEqualTo(importBefore + 1);
        assertThat(meters.get("video.qa.duration").tag("outcome", "succeeded").timer().count()).isEqualTo(qaBefore + 1);
    }

    private void completeVideoQa(Long id, Task task, boolean invalid) {
        var mediaProbe = mock(VideoMediaProbe.class);
        if (invalid) when(mediaProbe.probe(anyString(), anyString())).thenThrow(new IllegalStateException("invalid video"));
        else when(mediaProbe.probe(anyString(), anyString())).thenReturn(new VideoMediaProbe.Metadata(640, 480, 5000, "video/mp4"));
        var qaService = new VideoWorkflowService(videoWorkflowRepository, videoProviderJobs, assetRepository, assets, imageStorage,
                tasks, new ObjectMapper(), imageRateLimiter, mock(org.apache.hc.client5.http.classic.HttpClient.class), mediaProbe, videoMetrics);
        TenantContext.runAs(tenantA, () -> transactions.execute(status -> { qaService.completeQa(id, task); return null; }));
    }

    @Test
    void videoDispatchMarkerCommitsBeforeHttpAndSurvivesRollbackAndLeaseReclaim() {
        var request = new VideoDtos.Create("video-durable-dispatch", "旋转", List.of(), null,
                "SEEDANCE_2_0_MINI", "auto", 5, "480p");
        var created = TenantContext.runAs(tenantA, () -> videoWorkflows.create(request, null));
        var task = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        assertThat(TenantContext.runAs(tenantA, () -> videoWorkflows.beginSubmit(created.id(), task))).isTrue();
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("resolution", "480p");
        candidate.put("prompt", "旋转");
        candidate.put("model", "seedance");

        String body = TenantContext.runAs(tenantA, () -> transactions.execute(status -> {
            String reserved = videoWorkflows.prepareSubmissionRequest(created.id(), task, candidate).orElseThrow();
            // This independent connection sees the marker before a provider could be called.
            assertThat(owner.queryForObject("select provider_submit_started_at is not null from video_workflows where id=?",
                    Boolean.class, created.id())).isTrue();
            assertThat(owner.queryForObject("select provider_submit_body from video_workflows where id=?",
                    String.class, created.id())).isEqualTo(reserved);
            status.setRollbackOnly();
            return reserved;
        }));

        var frozen = TenantContext.runAs(tenantA, () -> videoWorkflowRepository.findById(created.id()).orElseThrow());
        assertThat(frozen.getProviderSubmitBody()).isEqualTo(body);
        assertThat(frozen.getProviderSubmitStartedAt()).isNotNull();
        expire(task.getId());
        assertThat(tasks.reclaimExpired()).isEqualTo(1);
        owner.update("update tasks set run_after=now() where id=?", task.getId());
        var retry = tasks.claim("VIDEO_PROVIDER", 1).getFirst();
        assertThat(retry.getAttempts()).isEqualTo(2);
        assertThat(TenantContext.runAs(tenantA, () -> videoWorkflows.prepareSubmissionRequest(created.id(), retry,
                Map.of("model", "changed", "prompt", "changed")))).contains(body);
        var reloaded = TenantContext.runAs(tenantA, () -> videoWorkflowRepository.findById(created.id()).orElseThrow());
        assertThat(reloaded.getProviderSubmitStartedAt()).isEqualTo(frozen.getProviderSubmitStartedAt());
        assertThat(TenantContext.runAs(tenantB, () -> videoWorkflowRepository.findById(created.id()))).isEmpty();
        assertThat(owner.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class "
                + "where oid='public.video_workflows'::regclass", Boolean.class)).isTrue();
    }

    @Test
    void anUnsentExpiredVideoRequestRefreshesRealMinioUrlsAndSubmitsSuccessfully() throws Exception {
        var asset = new Asset();
        asset.setTenantId(tenantA);
        asset.setName("视频参考图");
        asset.setType("IMAGE");
        asset.setStatus("READY");
        asset.setStorageKey("t" + tenantA + "/image/video-reference");
        asset.setMimeType("image/png");
        asset.setSizeBytes(128L);
        var reference = TenantContext.runAs(tenantA, () -> assetRepository.saveAndFlush(asset));
        var request = new VideoDtos.Create("video-refresh", "旋转", List.of(reference.getId()), null,
                "SEEDANCE_2_0_MINI", "auto", 5, "480p");
        var created = TenantContext.runAs(tenantA, () -> videoWorkflows.create(request, null));
        String oldUrl = "https://storage.example/reference?X-Amz-Date=20200101T000000Z&X-Amz-Expires=3600";
        Map<String, Object> expired = Map.of("content", List.of(Map.of("type", "image_url",
                "image_url", Map.of("url", oldUrl))));
        owner.update("update video_workflows set provider_submit_request=cast(? as jsonb) where id=?",
                new ObjectMapper().writeValueAsString(expired), created.id());
        Map<String, Object> fresh = TenantContext.runAs(tenantA, () -> videoProvider.buildSubmitRequest(request));
        var provider = mock(VideoProviderGateway.class);
        when(provider.buildSubmitRequest(request)).thenReturn(fresh);
        when(provider.submit(anyString(), eq("video-" + created.id() + "-submit"))).thenAnswer(invocation -> {
            String body = invocation.getArgument(0);
            assertThat(owner.queryForObject("select provider_submit_body from video_workflows where id=?",
                    String.class, created.id())).isEqualTo(body);
            assertThat(owner.queryForObject("select provider_submit_started_at is not null from video_workflows where id=?",
                    Boolean.class, created.id())).isTrue();
            String url = new ObjectMapper().readTree(body).path("content").get(1).path("image_url").path("url").asText();
            assertThat(url).isNotEqualTo(oldUrl).contains("X-Amz-Expires=21600");
            return new VideoProviderGateway.SubmitResult("onlyrouter", "job-refreshed", "QUEUED");
        });
        var handler = new VideoSubmitHandler(videoWorkflows, provider, videoWorkflowRepository);
        var task = tasks.claim("VIDEO_PROVIDER", 1).getFirst();

        var result = TenantContext.runAs(tenantA, () -> handler.handle(task));

        assertThat(result).containsEntry("status", "SUBMITTED").containsEntry("providerJobId", "job-refreshed");
        var workflow = TenantContext.runAs(tenantA, () -> videoWorkflowRepository.findById(created.id()).orElseThrow());
        assertThat(workflow.getStatus()).isEqualTo("GENERATING");
        assertThat(workflow.getProviderSubmitRequest()).isEqualTo(fresh);
        assertThat(owner.queryForObject("select count(*) from tasks where type='VIDEO_POLL' and queue='VIDEO_PROVIDER'",
                Integer.class)).isEqualTo(1);
        verify(provider, times(1)).submit(eq(workflow.getProviderSubmitBody()), eq("video-" + created.id() + "-submit"));
    }

    @Test
    void videoV18BackfillsLegacySubmissionsWithoutBypassingForcedRls() throws Exception {
        String migration = new ClassPathResource("db/migration/V18__video_submission_lifetime.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (var connection = ownerConnection(); var statement = connection.createStatement()) {
            try {
                statement.execute("CREATE ROLE video_v18_migration_owner NOLOGIN NOSUPERUSER NOBYPASSRLS");
                statement.execute("CREATE SCHEMA video_v18_upgrade AUTHORIZATION video_v18_migration_owner");
                statement.execute("SET ROLE video_v18_migration_owner");
                statement.execute("SET search_path TO video_v18_upgrade");
                statement.execute("CREATE TABLE tenants(id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO tenants VALUES (1), (2)");
                statement.execute("""
                        CREATE TABLE video_workflows (
                            id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                            provider_submit_request JSONB, provider_job_id TEXT,
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT '2020-01-01T00:00:00Z'
                        )
                        """);
                statement.execute("ALTER TABLE video_workflows ENABLE ROW LEVEL SECURITY");
                statement.execute("ALTER TABLE video_workflows FORCE ROW LEVEL SECURITY");
                statement.execute("""
                        CREATE POLICY tenant_isolation ON video_workflows
                        USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
                        WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
                        """);
                statement.execute("SELECT set_config('app.tenant_id', '1', false)");
                statement.execute("""
                        INSERT INTO video_workflows(id, tenant_id, provider_submit_request, provider_job_id) VALUES
                            (101, 1, NULL, NULL), (102, 1, '{}', NULL),
                            (103, 1, '{"prompt":"possibly submitted"}', NULL), (104, 1, NULL, 'job-1')
                        """);
                statement.execute("SELECT set_config('app.tenant_id', '2', false)");
                statement.execute("INSERT INTO video_workflows VALUES (201, 2, '{\"prompt\":\"tenant two\"}', NULL, now())");
                statement.execute("SELECT set_config('app.tenant_id', '999', false)");

                statement.execute(migration);

                try (var result = statement.executeQuery("SELECT current_setting('app.tenant_id')")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("999");
                }
                statement.execute("SELECT set_config('app.tenant_id', '1', false)");
                try (var result = statement.executeQuery("""
                        SELECT count(*) FILTER (WHERE provider_submit_started_at IS NULL),
                               count(*) FILTER (WHERE provider_submit_started_at = updated_at),
                               count(*) FILTER (WHERE provider_submit_body IS NOT NULL)
                        FROM video_workflows
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(2);
                    assertThat(result.getInt(2)).isEqualTo(2);
                    assertThat(result.getInt(3)).isZero();
                }
                statement.execute("SELECT set_config('app.tenant_id', '2', false)");
                try (var result = statement.executeQuery("SELECT id, provider_submit_started_at IS NOT NULL, provider_submit_body "
                        + "FROM video_workflows")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(201L);
                    assertThat(result.getBoolean(2)).isTrue();
                    assertThat(result.getString(3)).isNull();
                    assertThat(result.next()).isFalse();
                }
                try (var result = statement.executeQuery("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class "
                        + "WHERE oid='video_v18_upgrade.video_workflows'::regclass")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                }
            } finally {
                statement.execute("RESET ROLE");
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA IF EXISTS video_v18_upgrade CASCADE");
                statement.execute("DROP ROLE IF EXISTS video_v18_migration_owner");
            }
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
    void rolledBackImageCreationReturnsRedisPermitsAndPersistsNoTasks() {
        enableImageModels();
        TenantContext.runAs(tenantA, () -> transactions.execute(status -> {
            var created = imageCreations.create(imageRequest("permit-rollback", "POSTER", 1, List.of()), null);
            assertThat(created.id()).isNotNull();
            assertThat(imageRateLimiter.getActiveCount(tenantA)).isEqualTo(1);
            assertThat(imageRateLimiter.getGlobalActiveCount()).isEqualTo(1);
            status.setRollbackOnly();
            return created;
        }));

        assertThat(imageRateLimiter.getActiveCount(tenantA)).isZero();
        assertThat(imageRateLimiter.getGlobalActiveCount()).isZero();
        assertThat(owner.queryForObject("select count(*) from image_creations", Integer.class)).isZero();
        assertThat(owner.queryForObject("select count(*) from tasks", Integer.class)).isZero();
    }

    @Test
    void committedImageCreationHoldsItsPermitUntilAnIdempotentCancellation() {
        enableImageModels();
        var created = TenantContext.runAs(tenantA,
                () -> imageCreations.create(imageRequest("permit-cancel", "POSTER", 1, List.of()), null));
        assertThat(imageRateLimiter.getActiveCount(tenantA)).isEqualTo(1);
        assertThat(imageRateLimiter.getGlobalActiveCount()).isEqualTo(1);

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(TenantContext.runAs(tenantA, () -> imageCreations.cancel(created.id())).status())
                    .isEqualTo("CANCELLED");
            assertThat(imageRateLimiter.getActiveCount(tenantA)).isZero();
            assertThat(imageRateLimiter.getGlobalActiveCount()).isZero();
        }
        assertThat(owner.queryForObject("select concurrency_permit_held from image_creations where id=?",
                Boolean.class, created.id())).isFalse();
    }

    @Test
    void concurrentCreationRollbackDoesNotReleaseTheCommittedWorkflowsPermit() throws Exception {
        enableImageModels();
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        var acquired = new CyclicBarrier(2);
        concurrent(() -> {
            int index = sequence.getAndIncrement();
            return TenantContext.runAs(tenantA, () -> transactions.execute(status -> {
                var created = imageCreations.create(imageRequest("permit-concurrent-" + index,
                        "POSTER", 1, List.of()), null);
                try {
                    acquired.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("concurrent acquisition timed out", e);
                }
                if (index == 0) status.setRollbackOnly();
                return created.id();
            }));
        });

        assertThat(imageRateLimiter.getActiveCount(tenantA)).isEqualTo(1);
        assertThat(imageRateLimiter.getGlobalActiveCount()).isEqualTo(1);
        assertThat(owner.queryForObject("select count(*) from image_creations", Integer.class)).isEqualTo(1);
        assertThat(owner.queryForObject("select count(*) from tasks", Integer.class)).isEqualTo(1);
        var committedId = owner.queryForObject("select id from image_creations", Long.class);
        TenantContext.runAs(tenantA, () -> imageCreations.cancel(committedId));
        assertThat(imageRateLimiter.getActiveCount(tenantA)).isZero();
        assertThat(imageRateLimiter.getGlobalActiveCount()).isZero();
    }

    @Test
    void laterImageAndVideoAcquiresRefreshAggregateCounterTtls() {
        var imageKeys = List.of("tenant:" + tenantA + ":image:active", "image:global:active");
        assertThat(imageRateLimiter.tryAcquireImageGeneration(tenantA, 20, 200)).isTrue();
        imageKeys.forEach(key -> redis.expire(key, Duration.ofSeconds(30)));
        assertThat(imageRateLimiter.tryAcquireImageGeneration(tenantA, 20, 200)).isTrue();
        for (var key : imageKeys) {
            assertThat(redis.opsForValue().get(key)).isEqualTo("2");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(
                    Duration.ofHours(2).minusMinutes(1).toMillis(), Duration.ofHours(2).toMillis());
        }

        var videoKeys = List.of("tenant:" + tenantA + ":video:active", "video:global:active");
        assertThat(imageRateLimiter.tryAcquireVideoGeneration(tenantA, 2, 20, 21600)).isTrue();
        videoKeys.forEach(key -> redis.expire(key, Duration.ofSeconds(30)));
        assertThat(imageRateLimiter.tryAcquireVideoGeneration(tenantA, 2, 20, 21600)).isTrue();
        for (var key : videoKeys) {
            assertThat(redis.opsForValue().get(key)).isEqualTo("2");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(
                    Duration.ofHours(6).minusMinutes(1).toMillis(), Duration.ofHours(6).toMillis());
        }
        for (int permit = 0; permit < 2; permit++) {
            imageRateLimiter.releaseImageGeneration(tenantA);
            imageRateLimiter.releaseVideoGeneration(tenantA);
        }
        assertThat(imageRateLimiter.getGlobalActiveCount()).isZero();
        assertThat(imageRateLimiter.getVideoGlobalActiveCount()).isZero();
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
            lock.createStatement().execute("SELECT id FROM users FOR UPDATE");
            var first = pool.submit(() -> code(() -> auth.refresh(pair.refreshToken(), null, null, null)));
            var second = pool.submit(() -> code(() -> auth.refresh(pair.refreshToken(), null, null, null)));
            try {
                awaitDatabaseWaiters("users", 2);
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
        Long userId = owner.queryForObject("INSERT INTO users(tenant_id,phone) VALUES (?,?) RETURNING id", Long.class, tenantB, "13800000002");
        String otherToken = jwt.issueAccessToken(userId, tenantB, "13800000002");
        mvc.perform(get("/api/assets").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(post("/api/assets/" + ticket.assetId() + "/confirm")
                        .header("Authorization", "Bearer " + otherToken).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(3001));
        assertThat(owner.queryForObject("SELECT status FROM assets WHERE id=?", String.class, ticket.assetId())).isEqualTo("PENDING");
        assertThat(TenantContext.get()).isNull();
        assertThat(TenantContext.runAs(tenantA, () -> assets.list(0, 20)).total()).isEqualTo(1);
    }

    @Test
    void invalidUploadRequestsReturnBusinessValidationErrors() throws Exception {
        Long userId = owner.queryForObject("INSERT INTO users(tenant_id,phone) VALUES (?,?) RETURNING id", Long.class, tenantA, "13800000001");
        String token = jwt.issueAccessToken(userId, tenantA, "13800000001");
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
        assertThat(code(() -> { wrongBucket.delete("missing"); return null; })).isEqualTo(1503);
    }

    @Test
    void logoutInvalidatesAllAccessAndRefreshTokensAndNewLoginStillWorks() throws Exception {
        seedPasswordAccount();
        var first = passwordLogin("integration-password");
        var second = passwordLogin("integration-password");
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + first.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        for (var pair : List.of(first, second)) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + pair.accessToken()))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1401));
            assertThat(code(() -> auth.refresh(pair.refreshToken(), null, null, null))).isEqualTo(2005);
        }
        var newLogin = passwordLogin("integration-password");
        assertThat(jwt.parse(newLogin.accessToken(), "access").tokenVersion()).isEqualTo(1);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newLogin.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void concurrentLogoutCannotBeUndoneByRefresh() throws Exception {
        seedPasswordAccount();
        var pair = passwordLogin("integration-password");
        try (Connection lock = ownerConnection(); var pool = Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);
            lock.createStatement().execute("SELECT id FROM users FOR UPDATE");
            var logout = pool.submit(() -> { auth.logout(pair.user().userId()); return true; });
            awaitDatabaseWaiters("users", 1);
            var refresh = pool.submit(() -> code(() -> auth.refresh(pair.refreshToken(), null, null, null)));
            try { awaitDatabaseWaiters("users", 2); }
            finally { lock.commit(); }
            assertThat(logout.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(refresh.get(10, TimeUnit.SECONDS)).isEqualTo(2005);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM refresh_tokens WHERE status='ACTIVE'", Integer.class)).isZero();
    }

    @Test
    void disabledUsersCannotUseAccessRefreshOrSmsLogin() throws Exception {
        seedCode("13800000001", "123456");
        var pair = login("13800000001", "123456");
        owner.update("UPDATE users SET status='DISABLED' WHERE id=?", pair.user().userId());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + pair.accessToken()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1401));
        assertThat(code(() -> auth.refresh(pair.refreshToken(), null, null, null))).isEqualTo(2005);
        seedCode("13800000001", "654321");
        assertThat(code(() -> login("13800000001", "654321"))).isEqualTo(1401);
        assertThat(owner.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void spoofingForwardedHeaderCannotBypassIpSmsLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/send-code").header("X-Forwarded-For", "203.0.113." + i)
                    .contentType("application/json").content("{\"phone\":\"1380000000" + i + "\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/send-code").header("X-Forwarded-For", "203.0.113.99")
                .contentType("application/json").content("{\"phone\":\"13800000009\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value(2001));
        assertThat(owner.queryForObject("SELECT count(*) FROM sms_codes", Integer.class)).isEqualTo(5);
        assertThat(redis.getExpire("auth:sms:{send}:global:minute")).isBetween(1L, 60L);
    }

    @Test
    void globalSmsBudgetStopsRequestsAcrossDifferentIps() {
        org.springframework.test.util.ReflectionTestUtils.setField(auth, "globalDay", 2);
        try {
            auth.sendCode("13800000001", "203.0.113.1");
            auth.sendCode("13800000002", "203.0.113.2");
            assertThat(code(() -> auth.sendCode("13800000003", "203.0.113.3"))).isEqualTo(2002);
            assertThat(owner.queryForObject("SELECT count(*) FROM sms_codes", Integer.class)).isEqualTo(2);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(auth, "globalDay", 10000);
        }
    }

    @Test
    void concurrentDifferentPhonesStillShareTheSameIpLimit() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(auth, "perIpMinute", 1);
        try {
            var sequence = new java.util.concurrent.atomic.AtomicInteger();
            var outcomes = concurrent(() -> code(() -> auth.sendCode("1380000000" + sequence.getAndIncrement(), "203.0.113.1")));
            assertThat(outcomes).containsExactlyInAnyOrder(200, 2001);
            assertThat(owner.queryForObject("SELECT count(*) FROM sms_codes", Integer.class)).isEqualTo(1);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(auth, "perIpMinute", 5);
        }
    }

    @Test
    void productionValidatorChecksActualDatabaseRolePrivileges() {
        var safe = productionValidator(appJdbc);
        assertThatCode(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(safe, "validate")).doesNotThrowAnyException();
        var unsafe = productionValidator(owner);
        assertThatThrownBy(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(unsafe, "validate"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BYPASSRLS");
    }

    private com.wuyao.growth.common.config.ProductionConfigurationValidator productionValidator(JdbcTemplate jdbc) {
        return new com.wuyao.growth.common.config.ProductionConfigurationValidator("https://storage.example.com",
                "random-storage-key", "random-storage-secret", "random-db-secret", "redis.example.com",
                "https://assets.example.com", "random-redis-secret", "aliyun", "growth_app", "growth_owner", jdbc);
    }

    @Test
    void cleanupProcessesAllTenantsUnderRlsAndKeepsReadyAndRecentUploads() throws Exception {
        var a = ticket();
        var b = TenantContext.runAs(tenantB, () -> assets.presignUpload(new AssetDtos.PresignRequest("test", "IMAGE", "image/png"), null));
        var ready = ticket();
        var recent = ticket();
        owner.update("UPDATE assets SET created_at=now()-interval '3 hours' WHERE id IN (?,?,?)", a.assetId(), b.assetId(), ready.assetId());
        owner.update("UPDATE assets SET status='READY' WHERE id=?", ready.assetId());
        for (var item : List.of(a, b, ready, recent)) {
            byte[] content = {1, 2, 3};
            minio.putObject(PutObjectArgs.builder().bucket("test-assets").object(item.storageKey())
                    .stream(new ByteArrayInputStream(content), content.length, -1).build());
        }
        assets.cleanupAbandonedUploads();
        assertThat(owner.queryForList("SELECT id FROM assets ORDER BY id", Long.class)).containsExactly(ready.assetId(), recent.assetId());
        assertThat(imageStorage.exists(a.storageKey())).isFalse();
        assertThat(imageStorage.exists(b.storageKey())).isFalse();
        assertThat(imageStorage.exists(ready.storageKey())).isTrue();
        assertThat(imageStorage.exists(recent.storageKey())).isTrue();
        assertThat(TenantContext.get()).isNull();
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
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(ids.getFirst()).quality())).isEqualTo(request.quality());
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
        assertThat(item.getSpec().prompt()).startsWith(prompt);
        verify(imageGateway,times(1)).invokeReal(argThat(r -> r != null && r.alias()==ModelAlias.TEXT_CREATIVE && r.tenantId().equals(tenantA)));
        var calls=ArgumentCaptor.forClass(ProviderRequest.class);
        verify(imageGateway,times(3)).invokeReal(calls.capture());
        var imageCalls=calls.getAllValues().stream().filter(r->r.alias()==ModelAlias.IMAGE_PRIMARY).toList();
        assertThat(imageCalls).hasSize(2);
        assertThat(imageCalls.getFirst().prompt()).startsWith(prompt).isEqualTo(imageCalls.getLast().prompt());
    }

    @Test
    void rateLimitTimeoutLeavesImageUnsubmittedAndAnAutomaticRetryCanSucceed() {
        imageConfig.setQualities(List.of("480P"));
        imageConfig.getGenerator().setProtocol(ImageModelProperties.Protocol.OPENAI);
        enableImageModels(); planImageCount(1);
        when(imageGateway.invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY)))
                .thenReturn(imageResult());
        var created = TenantContext.runAs(tenantA,
                () -> imageCreations.create(imageRequest("rate-wait-retry", "POSTER", 1, List.of()), null));
        runImageTask("DEFAULT", imagePlanHandler);
        clearInvocations(imageGateway);
        var item = TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).items().getFirst());
        var limiter = mock(com.wuyao.growth.common.ratelimit.ImageApiRateLimiter.class);
        String message = "图片模型请求过于频繁，请稍后重试";
        doThrow(new IllegalStateException(message)).doNothing().when(limiter).waitForPermission();
        var handler = new ImageRenderHandler(imageCreations, imageGateway, imageStorage, imageRenderer,
                imageConfig, limiter, mock(com.wuyao.growth.common.metrics.ImageMetrics.class));
        var task = tasks.claim("IMAGE", 1).getFirst();

        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> handler.handle(task)))
                .isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.itemSnapshot(item.id())).getProviderCode()).isNull();
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).items().getFirst()).error())
                .isEqualTo(message).doesNotContain("核对", "费用", "尚未确认");
        verify(imageGateway, never()).invokeReal(any());

        Instant beforeFailure = Instant.now();
        assertThat(tasks.fail(task.getId(), task.getAttempts(), "HANDLER_ERROR", message)).isTrue();
        var pending = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(pending.getAttempts()).isEqualTo(1);
        assertThat(pending.getMaxAttempts()).isEqualTo(3);
        assertThat(pending.getErrorMessage()).isEqualTo(message);
        assertThat(pending.getRunAfter()).isAfterOrEqualTo(beforeFailure.plusSeconds(19))
                .isBeforeOrEqualTo(Instant.now().plusSeconds(21));
        assertThat(tasks.claim("IMAGE", 1)).isEmpty();
        // Advance only the schedule; avoid sleeping twenty seconds in the regression test.
        owner.update("update tasks set run_after=now() where id=?", task.getId());
        var retried = runImageTask("IMAGE", handler);

        assertThat(retried.getId()).isEqualTo(task.getId());
        assertThat(retried.getAttempts()).isEqualTo(2);
        var result = TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).items().getFirst());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.error()).isNull();
        verify(imageGateway, times(1)).invokeReal(any());
    }

    @Test
    void anExhaustedRateLimitBudgetPreservesTheReasonAndAllowsImmediateManualRetry() {
        imageConfig.setQualities(List.of("480P"));
        imageConfig.getGenerator().setProtocol(ImageModelProperties.Protocol.OPENAI);
        enableImageModels(); planImageCount(1);
        when(imageGateway.invokeReal(argThat(r -> r != null && r.alias() == ModelAlias.IMAGE_PRIMARY)))
                .thenReturn(imageResult());
        var created = TenantContext.runAs(tenantA,
                () -> imageCreations.create(imageRequest("rate-wait-budget", "POSTER", 1, List.of()), null));
        runImageTask("DEFAULT", imagePlanHandler);
        clearInvocations(imageGateway);
        var item = TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).items().getFirst());
        var limiter = mock(com.wuyao.growth.common.ratelimit.ImageApiRateLimiter.class);
        String message = "图片模型请求过于频繁，请稍后重试";
        doThrow(new IllegalStateException(message)).when(limiter).waitForPermission();
        var handler = new ImageRenderHandler(imageCreations, imageGateway, imageStorage, imageRenderer,
                imageConfig, limiter, mock(com.wuyao.growth.common.metrics.ImageMetrics.class));
        Task last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            var task = tasks.claim("IMAGE", 1).getFirst();
            assertThat(task.getAttempts()).isEqualTo(attempt);
            assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> handler.handle(task)))
                    .isInstanceOf(IllegalStateException.class).hasMessage(message);
            assertThat(TenantContext.runAs(tenantA, () -> imageCreations.itemSnapshot(item.id())).getProviderCode()).isNull();
            Instant beforeFailure = Instant.now();
            assertThat(tasks.fail(task.getId(), task.getAttempts(), "HANDLER_ERROR", message)).isTrue();
            var saved = taskRepository.findById(task.getId()).orElseThrow();
            if (attempt < 3) {
                long expectedBackoff = attempt == 1 ? 20 : 40;
                assertThat(saved.getStatus()).isEqualTo(TaskStatus.PENDING);
                assertThat(saved.getRunAfter()).isAfterOrEqualTo(beforeFailure.plusSeconds(expectedBackoff - 1))
                        .isBeforeOrEqualTo(Instant.now().plusSeconds(expectedBackoff + 1));
                assertThat(tasks.claim("IMAGE", 1)).isEmpty();
                owner.update("update tasks set run_after=now() where id=?", task.getId());
            } else {
                assertThat(saved.getStatus()).isEqualTo(TaskStatus.FAILED);
            }
            last = task;
        }
        var interrupted = TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).items().getFirst());
        assertThat(interrupted.status()).isEqualTo("INTERRUPTED");
        assertThat(interrupted.error()).isEqualTo(message).doesNotContain("核对", "费用", "尚未确认");
        verify(imageGateway, never()).invokeReal(any());
        Long failedTaskId = last.getId();

        var resumed = TenantContext.runAs(tenantA, () -> imageCreations.retry(created.id(), item.id(), failedTaskId, null));

        assertThat(resumed.items().getFirst().status()).isEqualTo("QUEUED");
        assertThat(resumed.items().getFirst().error()).isNull();
        var snapshot = TenantContext.runAs(tenantA, () -> imageCreations.itemSnapshot(item.id()));
        assertThat(snapshot.getProviderCode()).isNull();
        assertThat(snapshot.getGeneration()).isZero();
        assertThat(snapshot.getTaskId()).isNotEqualTo(failedTaskId);
        doNothing().when(limiter).waitForPermission();
        var restarted = runImageTask("IMAGE", handler);
        assertThat(restarted.getAttempts()).isEqualTo(1);
        assertThat(TenantContext.runAs(tenantA, () -> imageCreations.get(created.id()).items().getFirst()).status())
                .isEqualTo("SUCCEEDED");
        verify(imageGateway, times(1)).invokeReal(any());
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
    void gptImage2HonorsSelectedQualityAndRejectsUnsupportedRatios() {
        enableImageModels();imageConfig.getGenerator().setProtocol(com.wuyao.growth.common.gateway.ImageModelProperties.Protocol.OPENAI);
        imageConfig.getGenerator().setModel("gpt-image-2");
        imageConfig.setQualities(List.of("1K", "2K", "4K"));
        var req=new ImageDtos.Create("too-large-square","POSTER","新品",List.of(),"1:1","4K",1,"LOCAL","POSTER","朋友圈","自动",null);
        assertThat(code(() -> TenantContext.runAs(tenantA,()->imageCreations.create(req,null)))).isEqualTo(4004);
        assertThat(owner.queryForObject("select count(*) from tasks",Integer.class)).isZero();
        var square=new ImageDtos.Create("square-2k","POSTER","新品",List.of(),"1:1","2K",1,"LOCAL","POSTER","朋友圈","自动",null);
        assertThat(TenantContext.runAs(tenantA,()->imageCreations.create(square,null)).quality()).isEqualTo("2K");
        var wide=new ImageDtos.Create("wide-1k","POSTER","新品",List.of(),"16:9","1K",1,"LOCAL","POSTER","朋友圈","自动",null);
        assertThat(TenantContext.runAs(tenantA,()->imageCreations.create(wide,null)).quality()).isEqualTo("1K");
        var wide4k=new ImageDtos.Create("wide-4k","POSTER","新品",List.of(),"16:9","4K",1,"LOCAL","POSTER","朋友圈","自动",null);
        assertThat(TenantContext.runAs(tenantA,()->imageCreations.create(wide4k,null)).quality()).isEqualTo("4K");
        assertThat(owner.queryForObject("select count(*) from tasks",Integer.class)).isEqualTo(3);
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
