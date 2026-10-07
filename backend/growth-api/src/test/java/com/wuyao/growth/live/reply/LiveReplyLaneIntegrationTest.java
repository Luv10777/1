package com.wuyao.growth.live.reply;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.task.TaskWorker;
import com.wuyao.growth.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The application as it really runs, with its worker switched on: while the queue narration uses is
 * held up by a task that will not finish, a task on the replies' queue is still picked up and done.
 */
// Declared first so that the containers outlive the application: its workers are still polling the
// database until the context closes, and closing waits for them.
@Testcontainers
@SpringBootTest(properties = {"growth.worker.enabled=true", "growth.worker.poll-interval=200",
        "logging.level.root=WARN", "logging.level.com.wuyao.growth=WARN",
        // A developer's local .env must not point this test at a real model or bucket.
        "growth.ai.writer.url=", "growth.voice.sample-storage=minio"})
// Workers that keep running would go on polling a database that is gone once this class is done.
@DirtiesContext
class LiveReplyLaneIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("lane_test").withUsername("growth_owner").withPassword("test_owner_password");

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
        registry.add("spring.flyway.placeholders.app_db_password", () -> "growth_dev_local");
        registry.add("growth.jwt.secret", () -> "test-only-random-signing-secret-0123456789abcdef");
        registry.add("growth.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("growth.storage.access-key", () -> "testadmin");
        registry.add("growth.storage.secret-key", () -> "testadmin123");
        registry.add("growth.storage.bucket", () -> "test-assets");
    }

    static final CountDownLatch NARRATION_STARTED = new CountDownLatch(1);
    static final CountDownLatch RELEASE_NARRATION = new CountDownLatch(1);

    @TestConfiguration
    static class Handlers {
        /** Stands in for a clip of narration being synthesised: it holds its worker until told to stop. */
        @Bean
        TaskHandler heldUpNarration() {
            return new TaskHandler() {
                public String type() { return "TEST_HELD_UP_NARRATION"; }
                public Map<String, Object> handle(Task task) {
                    NARRATION_STARTED.countDown();
                    try {
                        RELEASE_NARRATION.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Map.of("done", true);
                }
            };
        }

        @Bean
        TaskHandler quickReply() {
            return new TaskHandler() {
                public String type() { return "TEST_QUICK_REPLY"; }
                public Map<String, Object> handle(Task task) { return Map.of("thread", Thread.currentThread().getName()); }
            };
        }

        /** Stands in for a reply being judged and synthesised: it finishes only once three are under way together. */
        @Bean
        TaskHandler replyThatNeedsCompany() {
            return new TaskHandler() {
                public String type() { return "TEST_REPLY_IN_COMPANY"; }
                public Map<String, Object> handle(Task task) {
                    REPLIES_UNDER_WAY.countDown();
                    try {
                        if (!REPLIES_UNDER_WAY.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("没有三条回复同时在处理");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Map.of("thread", Thread.currentThread().getName());
                }
            };
        }
    }

    static final CountDownLatch REPLIES_UNDER_WAY = new CountDownLatch(3);

    @Autowired TaskService tasks;
    @Autowired Map<String, TaskWorker> workers;
    @Autowired LiveReplyLane lane;

    @Test
    void repliesAreHandledWhileNarrationIsStillBeingWorkedOnAndSeveralAtOnce() throws Exception {
        // The ordinary worker, and the lane for replies with loops of its own, both started by the application itself.
        assertThat(workers).containsOnlyKeys("taskWorker");
        assertThat(lane.isRunning()).isTrue();
        assertThat(lane.concurrency()).isEqualTo(3);
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Long tenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('通道测试') RETURNING id", Long.class);
        try {
            Long narration = TenantContext.runAs(tenant, () -> tasks.submit("TEST_HELD_UP_NARRATION", "LIVE", Map.of(), "held-up", null)).getId();
            assertThat(NARRATION_STARTED.await(10, TimeUnit.SECONDS)).as("the ordinary worker picked the narration up").isTrue();

            Long reply = TenantContext.runAs(tenant, () -> tasks.submit("TEST_QUICK_REPLY", LiveReplyLane.QUEUE, Map.of(), "quick", null)).getId();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(owner.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, reply)).isEqualTo("SUCCEEDED"));
            // It finished with the narration still running: it did not wait its turn behind it.
            assertThat(owner.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, narration)).isEqualTo("RUNNING");

            // Three comments at once are worked on together, not one after another: each of these
            // only finishes if the other two are in flight at the same time.
            for (int i = 1; i <= 3; i++) {
                String key = "company-" + i;
                TenantContext.runAs(tenant, () -> tasks.submit("TEST_REPLY_IN_COMPANY", LiveReplyLane.QUEUE, Map.of(), key, null));
            }
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                    assertThat(owner.queryForList("SELECT status FROM tasks WHERE type = 'TEST_REPLY_IN_COMPANY'", String.class))
                            .containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED"));
            assertThat(owner.queryForList("SELECT DISTINCT result->>'thread' FROM tasks WHERE type = 'TEST_REPLY_IN_COMPANY'", String.class))
                    .hasSize(3).allMatch(thread -> thread.startsWith("live-reply-"));

            // And a reply task left on narration's queue would have had to wait: that queue has one worker.
            Long stuck = TenantContext.runAs(tenant, () -> tasks.submit("TEST_QUICK_REPLY", "LIVE", Map.of(), "stuck", null)).getId();
            Thread.sleep(1500);
            assertThat(owner.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, stuck)).isEqualTo("PENDING");

            RELEASE_NARRATION.countDown();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(owner.queryForObject("SELECT count(*) FROM tasks WHERE status <> 'SUCCEEDED'", Integer.class)).isZero());
        } finally {
            RELEASE_NARRATION.countDown();
        }
    }
}
