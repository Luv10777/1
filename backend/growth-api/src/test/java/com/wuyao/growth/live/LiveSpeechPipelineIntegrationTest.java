package com.wuyao.growth.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.gateway.ProviderAdapter;
import com.wuyao.growth.common.gateway.ProviderRequest;
import com.wuyao.growth.common.gateway.ProviderResult;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.task.TaskHandler;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.task.TaskWorker;
import com.wuyao.growth.live.player.LivePlayerWebSocketHandler;
import com.wuyao.growth.voice.VoiceProvider;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole path a spoken segment travels, against a real database and object store: the api records
 * work, a worker writes and synthesises it, the player is pushed the result and its acknowledgement
 * triggers the next segment. Only the two paid providers are stand-ins.
 */
@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN",
        // A developer's local .env must not point this test at a real model or bucket.
        "growth.ai.writer.url=", "growth.voice.sample-storage=minio"})
@AutoConfigureMockMvc
@Testcontainers
class LiveSpeechPipelineIntegrationTest {
    private static final String HONEY = "家人们看过来，这款椴树蜂蜜售价 69 元一罐，一罐 500 克，结晶细腻，入口很顺，喜欢的朋友可以看看。";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("live_test").withUsername("growth_owner").withPassword("test_owner_password");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("cgr.dev/chainguard/minio:latest")
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
        registry.add("growth.storage.endpoint", LiveSpeechPipelineIntegrationTest::minioEndpoint);
        // Presigned URLs are signed for the public endpoint, which otherwise comes from the environment.
        registry.add("growth.storage.public-endpoint", LiveSpeechPipelineIntegrationTest::minioEndpoint);
        registry.add("growth.storage.access-key", () -> "testadmin");
        registry.add("growth.storage.secret-key", () -> "testadmin123");
        registry.add("growth.storage.bucket", () -> "test-assets");
    }

    /** Stands in for the configured text model; each test decides what it writes. */
    static final class StubWriter implements ProviderAdapter {
        volatile Function<ProviderRequest, Map<String, Object>> reply = request -> Map.of("text", HONEY);
        final List<ProviderRequest> requests = new CopyOnWriteArrayList<>();

        public String code() { return "STUB_WRITER"; }
        public Set<ModelAlias> supports() { return Set.of(ModelAlias.TEXT_WRITER); }
        public ProviderResult invoke(ProviderRequest request) {
            requests.add(request);
            return new ProviderResult(true, code(), null, reply.apply(request), null, null);
        }
    }

    @TestConfiguration
    static class Providers {
        @Bean
        @Order(-1)
        StubWriter stubWriter() { return new StubWriter(); }
    }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired TaskService tasks;
    @Autowired List<TaskHandler> handlers;
    @Autowired LivePlayerWebSocketHandler playerSocketHandler;
    @Autowired StubWriter writer;
    @MockitoBean VoiceProvider voiceProvider;
    private final ObjectMapper json = new ObjectMapper();
    JdbcTemplate owner;
    MinioClient minio;
    TaskWorker worker;
    String token;
    Long tenant;
    long store;
    long product;
    long session;

    static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    @AfterEach
    void stopWorker() {
        if (worker != null) worker.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tasks, tenants RESTART IDENTITY CASCADE");
        minio = MinioClient.builder().endpoint(minioEndpoint()).credentials("testadmin", "testadmin123").build();
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket("test-assets").build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket("test-assets").build());
        }
        // Identities restart with every test, so clip keys repeat: start from an empty store.
        for (var result : minio.listObjects(ListObjectsArgs.builder().bucket("test-assets").recursive(true).build())) {
            minio.removeObject(RemoveObjectArgs.builder().bucket("test-assets").object(result.get().objectName()).build());
        }
        writer.reply = request -> Map.of("text", HONEY);
        writer.requests.clear();
        when(voiceProvider.configured()).thenReturn(true);
        when(voiceProvider.code()).thenReturn("stub-voice");
        when(voiceProvider.model()).thenReturn("stub-model");
        when(voiceProvider.builtInVoices()).thenReturn(List.of("voice-a", "voice-b"));
        when(voiceProvider.synthesize(anyString(), anyString(), any())).thenReturn(new VoiceProvider.Audio(new byte[48000], 24000, 80));
        // The same loop the worker process runs, driven by hand so the test decides when work happens.
        // Replies have a queue of their own; in a running system a second loop works it alongside this one.
        worker = new TaskWorker(tasks, handlers, List.of("LIVE_REPLY", "LIVE"), 5, Duration.ofMinutes(30), 10_000);

        tenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('直播测试') RETURNING id", Long.class);
        Long user = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?, 'host', '主播') RETURNING id", Long.class, tenant);
        token = jwt.issueAccessToken(user, tenant, null);
        store = api(post("/api/stores"), Map.of("name", "蜂蜜小店")).path("id").asLong();
        product = api(post("/api/stores/" + store + "/products"), Map.of("name", "椴树蜂蜜", "type", "PHYSICAL",
                "category", "食品", "price", 69, "saleUnit", "罐", "specification", "500 克",
                "coreSellingPoints", "东北椴树蜜，结晶细腻")).path("id").asLong();
        session = api(post("/api/stores/" + store + "/live-sessions"), Map.of("name", "晚间场", "roomId", "room-1",
                "productIds", List.of(product), "config", Map.of("rotateRoles", true,
                        "rotationSelection", List.of("builtin:voice-a", "builtin:voice-b"),
                        "voiceRoles", List.of(Map.of("id", "builtin:voice-a", "role", "host"),
                                Map.of("id", "builtin:voice-b", "role", "cohost"))))).path("id").asLong();
    }

    @Test
    void narrationIsWrittenSynthesisedPushedAndToppedUpAsThePlayerConsumesIt() throws Exception {
        api(post(path("/start")), null);
        WebSocketSession socket = connectPlayer();

        JsonNode started = api(post(path("/auto-script/start")), null);
        assertThat(started.path("enabled").asBoolean()).isTrue();
        assertThat(started.path("inFlight").asInt()).isEqualTo(1);
        // The request only recorded work: nothing has been written or spoken yet.
        assertThat(writer.requests).isEmpty();
        verify(voiceProvider, never()).synthesize(anyString(), anyString(), any());

        drain();

        // The low-water mark is three finished segments; generation stops there until one is played.
        JsonNode buffered = api(get(path("/auto-script")), null);
        assertThat(buffered.path("buffered").asInt()).isEqualTo(3);
        assertThat(buffered.path("inFlight").asInt()).isZero();
        assertThat(buffered.path("generated").asInt()).isEqualTo(3);
        assertThat(scripts("status")).containsExactly("READY", "READY", "READY");
        assertThat(scripts("beat")).containsExactly("OPENING_SCENE", "PAIN_SCENE", "DETAIL_FEATURE");
        assertThat(scripts("voice")).containsOnly("builtin:voice-a");
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks WHERE status <> 'SUCCEEDED'", Integer.class)).isZero();

        // The model was given the merchant's saved facts, never asked to improvise them.
        assertThat(writer.requests.get(0).prompt()).contains("名称：椴树蜂蜜", "售价：69 元 / 罐", "规格：500 克", "东北椴树蜜");
        assertThat(writer.requests.get(1).prompt()).contains("避免重复", HONEY);

        List<JsonNode> pushed = commands(socket);
        assertThat(pushed).extracting(command -> command.path("id").asText()).containsExactly("script-0", "script-1", "script-2");
        assertThat(pushed.get(0).path("text").asText()).isEqualTo(HONEY);

        // The clip is fetched with the player's own token and is a real WAV.
        String audioUrl = pushed.get(0).path("audioUrl").asText();
        byte[] wav = mvc.perform(get(audioUrl).param("token", playerToken)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(wav, 0, 4)).isEqualTo("RIFF");
        assertThat(wav).hasSize(48044);
        assertThat(clips()).hasSize(3);

        // Playing one segment frees a slot, and that alone triggers the next one.
        acknowledge(socket, "script-0");
        assertThat(api(get(path("/auto-script")), null).path("inFlight").asInt()).isEqualTo(1);
        drain();
        assertThat(scripts("status")).containsExactly("PLAYED", "READY", "READY", "READY");
        assertThat(commands(socket)).extracting(command -> command.path("id").asText()).endsWith("script-3");
        assertThat(clips()).as("a played clip is removed from storage").hasSize(3);

        // One pass over the product is four beats; the voice changes when the pass completes.
        acknowledge(socket, "script-1");
        drain();
        assertThat(scripts("beat")).endsWith("CLOSE", "OPENING_SCENE");
        assertThat(scripts("voice")).endsWith("builtin:voice-a", "builtin:voice-b");
        verify(voiceProvider).synthesize(anyString(), eq("voice-b"), any());

        // Stopping means no further segments, even as the player keeps consuming.
        assertThat(api(post(path("/auto-script/stop")), null).path("enabled").asBoolean()).isFalse();
        acknowledge(socket, "script-2");
        drain();
        assertThat(owner.queryForObject("SELECT count(*) FROM live_speech_items", Integer.class)).isEqualTo(5);
    }

    @Test
    void aStoreWithABrandNarratesAndAnswersWithTheBrandsOwnDescription() throws Exception {
        // The merchant's first brand takes in the store that already exists; nothing is set on the session.
        api(post("/api/brands"), Map.of("name", "椴语", "slogan", "一勺蜜，一片林", "intro", "2019 年在长白山建起第一座蜂场",
                "languageStyle", "朴实、不夸张"));
        api(post(path("/start")), null);
        connectPlayer();
        String branded = "椴语 2019 年在长白山建起第一座蜂场，这款椴树蜂蜜 69 元一罐，一罐 500 克，喜欢的朋友可以看看。";
        writer.reply = request -> Map.of("text", branded);

        api(post(path("/auto-script/start")), null);
        drain();

        assertThat(writer.requests.get(0).prompt()).contains("名称：椴树蜂蜜", "【品牌资料】\n品牌：椴语", "口号：一勺蜜，一片林",
                "简介：2019 年在长白山建起第一座蜂场", "表达风格：朴实、不夸张");
        assertThat(writer.requests.get(0).options().get("system").toString()).contains("9. 【品牌资料】是这家店所属品牌的介绍");
        // The year comes from the brand, so the segment passes the check that refuses invented numbers.
        assertThat(scripts("status")).containsOnly("READY");
        assertThat(scripts("text")).containsOnly(branded);
        api(post(path("/auto-script/stop")), null);

        int asked = writer.requests.size();
        writer.reply = request -> decided("提问", 0, "我们是椴语，2019 年就在长白山养蜂了。");
        api(post(path("/mock-comments")), comment("brand-1", "你们是什么牌子"));
        drain();
        ProviderRequest judged = writer.requests.get(asked);
        assertThat(judged.prompt()).contains("名称：椴树蜂蜜", "【品牌资料】\n品牌：椴语", "【观众弹幕】你们是什么牌子");
        assertThat(judged.options().get("system").toString()).contains("关于【品牌资料】：它是这家店所属品牌的介绍");
        assertThat(feed("brand-1")).containsEntry("status", "ANSWERED").containsEntry("source", "AI");
    }

    @Test
    void manualSpeechIsQueuedForAWorkerAndTheSameIdNeverBuysASecondClip() throws Exception {
        WebSocketSession socket = connectPlayer();
        Map<String, Object> speech = Map.of("id", "manual-1", "mode", "APPEND", "text", "欢迎来到直播间", "builtInVoice", "voice-a");

        JsonNode accepted = api(post(path("/speech")), speech);
        assertThat(accepted.path("status").asText()).isEqualTo("PENDING");
        assertThat(accepted.path("deduplicated").asBoolean()).isFalse();
        verify(voiceProvider, never()).synthesize(anyString(), anyString(), any());
        assertThat(api(get(path("/player/status")), null).path("queueLength").asInt()).isEqualTo(1);

        drain();

        assertThat(commands(socket)).extracting(command -> command.path("id").asText()).containsExactly("manual-1");
        JsonNode repeated = api(post(path("/speech")), speech);
        assertThat(repeated.path("status").asText()).isEqualTo("READY");
        assertThat(repeated.path("deduplicated").asBoolean()).isTrue();
        drain();
        verify(voiceProvider, times(1)).synthesize(anyString(), anyString(), any());

        mvc.perform(post(path("/speech")).header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("id", "manual-1", "mode", "APPEND", "text", "换了内容", "builtInVoice", "voice-a"))))
                .andExpect(jsonPath("$.code").value(1409));
        // An unknown voice is refused while the user is still waiting, before anything is queued.
        mvc.perform(post(path("/speech")).header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("id", "manual-2", "mode", "APPEND", "text", "你好", "builtInVoice", "nobody"))))
                .andExpect(jsonPath("$.code").value(1400));
        assertThat(owner.queryForObject("SELECT count(*) FROM live_speech_items", Integer.class)).isEqualTo(1);

        JsonNode feed = api(get(path("/speech-items")), null);
        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).path("status").asText()).isEqualTo("READY");
        assertThat(feed.get(0).path("durationMillis").asLong()).isEqualTo(1000);
    }

    @Test
    void aRefreshedConsoleTakesOverWithANewCredentialAndCarriesOnWithTheSameQueue() throws Exception {
        api(post(path("/start")), null);
        WebSocketSession first = connectPlayer();
        api(post(path("/auto-script/start")), null);
        drain();
        acknowledge(first, "script-0");
        drain();
        long synthesised = synthesised();

        // What reloading the page does: a fresh credential and a fresh socket.
        WebSocketSession second = connectPlayer();

        verify(first).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
        assertThat(commands(second)).extracting(command -> command.path("id").asText())
                .containsExactly("script-1", "script-2", "script-3");
        assertThat(scripts("status")).containsExactly("PLAYED", "READY", "READY", "READY");
        drain();
        assertThat(synthesised()).as("nothing is produced again").isEqualTo(synthesised);
        // The page that was replaced can no longer consume the queue.
        acknowledge(first, "script-1");
        assertThat(scripts("status")).containsExactly("PLAYED", "READY", "READY", "READY");
    }

    @Test
    void aLongWaitIsExplainedAsAMissingWorkerOrASlowProviderNeverConfused() throws Exception {
        api(post(path("/start")), null);
        assertThat(api(post(path("/auto-script/start")), null).has("hint")).isFalse();

        // Nothing has claimed the task for half a minute: that is a worker problem.
        owner.update("UPDATE live_speech_items SET queued_at = now() - interval '30 seconds'");
        assertThat(api(get(path("/auto-script")), null).path("hint").asText()).contains("还没有 worker 开始处理");

        // A worker took it half a minute ago and the model has not answered: that is the model.
        owner.update("UPDATE live_speech_items SET started_at = now() - interval '31 seconds'");
        assertThat(api(get(path("/auto-script")), null).path("hint").asText())
                .contains("文案模型已处理", "worker 在正常工作").doesNotContain("请确认后台 worker");

        // The real pipeline records both moments itself.
        owner.update("UPDATE live_speech_items SET queued_at = created_at, started_at = NULL");
        drain();
        assertThat(owner.queryForObject("SELECT count(*) FROM live_speech_items WHERE kind='SCRIPT' AND status='READY' "
                + "AND started_at IS NOT NULL AND queued_at >= created_at", Integer.class)).isEqualTo(3);
        assertThat(api(get(path("/auto-script")), null).has("hint")).isFalse();
    }

    @Test
    void aSessionCannotGoOnAirWithoutAHostVoiceAndAStoreHasOnlyOneOnAir() throws Exception {
        long voiceless = api(post("/api/stores/" + store + "/live-sessions"), Map.of("name", "没选主播",
                "productIds", List.of(product))).path("id").asLong();
        mvc.perform(post("/api/live-sessions/" + voiceless + "/start").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1400)).andExpect(jsonPath("$.message").value("请先选择本场的主播音色"));

        api(post(path("/start")), null);
        long second = api(post(path("/duplicate")), Map.of("name", "第二场")).path("id").asLong();
        mvc.perform(post("/api/live-sessions/" + second + "/start").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1409)).andExpect(jsonPath("$.message").value(
                        "门店已有一场正在进行的直播「晚间场」，请先结束它再开始新的一场"));
        // Paused still counts as on air.
        api(post(path("/pause")), null);
        mvc.perform(post("/api/live-sessions/" + second + "/start").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1409));
        // The database refuses a second one even if the application check were bypassed.
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='uk_live_sessions_one_active'",
                Integer.class)).isEqualTo(1);

        api(post(path("/end")), null);
        assertThat(api(post("/api/live-sessions/" + second + "/start"), null).path("status").asText()).isEqualTo("LIVE");
    }

    @Test
    void theNextSessionIsCopiedFromAnEarlierOneAndADraftCanBeDeleted() throws Exception {
        api(post(path("/qa")), Map.of("question", "营业时间", "answer", "每天 9 点到 18 点"));
        api(post(path("/start")), null);
        api(post(path("/end")), null);

        JsonNode copy = api(post(path("/duplicate")), Map.of("name", "明天这一场"));

        long copyId = copy.path("id").asLong();
        assertThat(copy.path("status").asText()).isEqualTo("DRAFT");
        assertThat(copy.path("name").asText()).isEqualTo("明天这一场");
        assertThat(copy.path("productIds")).hasSize(1);
        assertThat(copy.at("/config/voiceRoles/0/id").asText()).isEqualTo("builtin:voice-a");
        assertThat(api(get("/api/live-sessions/" + copyId + "/qa"), null).get(0).path("question").asText()).isEqualTo("营业时间");
        // The list is a summary: it names each session without carrying its knowledge snapshot.
        JsonNode list = api(get("/api/stores/" + store + "/live-sessions"), null);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("id").asLong()).isEqualTo(copyId);
        assertThat(list.get(0).has("knowledge")).isFalse();
        assertThat(list.get(1).path("status").asText()).isEqualTo("ENDED");

        // History stays; only what never started can go.
        mvc.perform(delete(path("")).header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.code").value(1409));
        api(delete("/api/live-sessions/" + copyId), null);
        assertThat(owner.queryForObject("SELECT count(*) FROM live_sessions", Integer.class)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM live_session_qa WHERE session_id=?", Integer.class, copyId)).isZero();
    }

    @Test
    void aClonedVoiceThatASessionOnAirDependsOnCannotBeDeleted() throws Exception {
        Long sample = owner.queryForObject("INSERT INTO voice_samples(tenant_id,store_id,name,storage_key,mime_type,status,"
                + "provider_code,provider_voice_id,consent_at,consent_by,consent_text) VALUES (?,?,'店主声音','t/voice','audio/wav','READY',"
                + "'stub-voice','voice-clone-1',now(),(SELECT id FROM users LIMIT 1),'同意') RETURNING id", Long.class, tenant, store);
        when(voiceProvider.supportsVoice("voice-clone-1")).thenReturn(true);
        long cloned = api(post("/api/stores/" + store + "/live-sessions"), Map.of("name", "克隆音色场",
                "productIds", List.of(product), "config", Map.of("voiceRoles",
                        List.of(Map.of("id", "sample:" + sample, "role", "host"))))).path("id").asLong();

        api(post("/api/live-sessions/" + cloned + "/start"), null);
        mvc.perform(delete("/api/voice-samples/" + sample).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1400))
                .andExpect(jsonPath("$.message").value("这个音色正在场次「克隆音色场」中使用，结束这一场后才能删除"));
        assertThat(owner.queryForObject("SELECT status FROM voice_samples WHERE id=?", String.class, sample)).isEqualTo("READY");
        verify(voiceProvider, never()).deleteVoice(anyString());

        api(post("/api/live-sessions/" + cloned + "/end"), null);
        api(delete("/api/voice-samples/" + sample), null);
        assertThat(owner.queryForObject("SELECT status FROM voice_samples WHERE id=?", String.class, sample)).isEqualTo("DELETED");
    }

    @Test
    void sessionStartsWithoutARoomLink() throws Exception {
        long roomless = api(post("/api/stores/" + store + "/live-sessions"), Map.of("name", "无直播间链接",
                "productIds", List.of(product), "config", Map.of("voiceRoles",
                        List.of(Map.of("id", "builtin:voice-a", "role", "host"))))).path("id").asLong();
        JsonNode started = api(post("/api/live-sessions/" + roomless + "/start"), null);
        assertThat(started.path("status").asText()).isEqualTo("LIVE");
        assertThat(started.path("roomId").isMissingNode() || started.path("roomId").isNull()).isTrue();
    }

    @Test
    void aSegmentThatInventsFactsIsRewrittenOnceAndAbandonedIfStillWrong() throws Exception {
        api(post(path("/start")), null);
        // First draft invents a price; the rewrite is clean.
        writer.reply = request -> Map.of("text", request.prompt().contains("上一版不合格")
                ? HONEY : "家人们看过来，这款椴树蜂蜜原价 199 元，今天直播间只要 69 元一罐，喜欢的朋友抓紧。");
        api(post(path("/auto-script/start")), null);
        drain();
        assertThat(scripts("status")).startsWith("READY");
        assertThat(writer.requests.get(1).prompt()).contains("出现了商品资料里没有的数字 199");

        // Now every draft makes a promise the merchant never wrote: nothing more reaches the audience.
        api(post(path("/auto-script/stop")), null);
        owner.update("UPDATE live_speech_items SET status='PLAYED'");
        writer.reply = request -> Map.of("text", "家人们看过来，这款椴树蜂蜜今天下单全国包邮，售价 69 元一罐，喜欢的朋友抓紧。");
        long synthesised = synthesised();
        api(post(path("/auto-script/start")), null);
        drain();

        JsonNode stopped = api(get(path("/auto-script")), null);
        assertThat(stopped.path("enabled").asBoolean()).isFalse();
        assertThat(stopped.path("lastError").asText()).contains("连续 3 次失败", "包邮");
        assertThat(owner.queryForObject("SELECT count(*) FROM live_speech_items WHERE status='FAILED'", Integer.class)).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT count(*) FROM live_speech_items WHERE status='READY'", Integer.class)).isZero();
        assertThat(synthesised()).as("rejected text is never turned into audio").isEqualTo(synthesised);
    }

    @Test
    void withoutATextModelNarrationStopsAtOnceAndSaysWhy() throws Exception {
        api(post(path("/start")), null);
        // What the placeholder adapter returns: an echo, with no text.
        writer.reply = request -> Map.of("echo", request.prompt(), "note", "占位适配器，未调用真实模型");
        api(post(path("/auto-script/start")), null);
        drain();

        JsonNode status = api(get(path("/auto-script")), null);
        assertThat(status.path("enabled").asBoolean()).isFalse();
        assertThat(status.path("lastError").asText()).contains("尚未配置文本大模型");
        assertThat(writer.requests).hasSize(1);
        verify(voiceProvider, never()).synthesize(anyString(), anyString(), any());
    }

    @Test
    void narrationCannotStartBeforeTheSessionOrWithoutASpeechProvider() throws Exception {
        mvc.perform(post(path("/auto-script/start")).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1409)).andExpect(jsonPath("$.message").value("请先开始本场，再开启自动讲解"));
        api(post(path("/start")), null);
        when(voiceProvider.configured()).thenReturn(false);
        mvc.perform(post(path("/auto-script/start")).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1400));
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks", Integer.class)).isZero();
        // Another tenant cannot see, let alone steer, this session's narration.
        Long otherTenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('别家') RETURNING id", Long.class);
        Long outsider = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?, 'outsider', '外人') RETURNING id", Long.class, otherTenant);
        mvc.perform(get(path("/auto-script")).header("Authorization", "Bearer " + jwt.issueAccessToken(outsider, otherTenant, null)))
                .andExpect(jsonPath("$.code").value(1404));
    }

    @Test
    void endingTheSessionStopsNarrationAndRemovesUnplayedClips() throws Exception {
        api(post(path("/start")), null);
        connectPlayer();
        api(post(path("/auto-script/start")), null);
        drain();
        assertThat(clips()).hasSize(3);

        api(post(path("/end")), null);

        assertThat(scripts("status")).containsOnly("DISCARDED");
        assertThat(clips()).isEmpty();
        assertThat(owner.queryForObject("SELECT enabled FROM live_script_states", Boolean.class)).isFalse();
    }

    @Test
    void aClipFinishedByAnotherProcessReachesThePlayerThroughTheDatabaseNotification() throws Exception {
        WebSocketSession socket = connectPlayer();
        // What a separate worker process leaves behind: a READY row, and a NOTIFY on commit.
        owner.update("INSERT INTO live_speech_items(tenant_id,session_id,command_id,kind,mode,status,text,voice,fingerprint,"
                + "duration_millis,pause_offsets,ready_at) VALUES (?,?,'from-worker','MANUAL','APPEND','READY','来自另一个进程',"
                + "'builtin:voice-a','f',1000,'[]'::jsonb,now())", tenant, session);
        owner.queryForObject("SELECT pg_notify('live_speech_ready', ?)::text", String.class, tenant + ":" + session);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(commands(socket)).extracting(command -> command.path("id").asText()).containsExactly("from-worker"));
    }

    /** What the text model returns when it judges a comment. */
    private static Map<String, Object> decided(String kind, int basis, String answer) {
        try {
            Map<String, Object> decision = new java.util.LinkedHashMap<>();
            decision.put("类型", kind);
            decision.put("概括", "观众说的话");
            decision.put("依据", basis);
            decision.put("回答", answer);
            return Map.of("text", new ObjectMapper().writeValueAsString(decision));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aCommentIsJudgedBeforeItIsAnsweredAndASavedAnswerIsPutIntoSpokenWords() throws Exception {
        WebSocketSession socket = connectPlayer();
        api(post(path("/qa")), Map.of("question", "几点关门", "answer", "晚上 9 点"));

        // Word for word a saved question: nothing to judge, only the merchant's note to put into speech.
        writer.reply = request -> Map.of("text", "我们每天营业到晚上 9 点哈。");
        JsonNode exact = api(post(path("/mock-comments")), comment("c-1", "几点关门？"));
        assertThat(exact.path("status").asText()).isEqualTo("answering");
        assertThat(writer.requests).as("the request only recorded work").isEmpty();
        drain();
        assertThat(writer.requests).hasSize(1);
        assertThat(writer.requests.get(0).prompt()).isEqualTo("【问题】几点关门\n【商家的回答】晚上 9 点");
        assertThat(writer.requests.get(0).options()).doesNotContainKey("reasoning");
        assertThat(writer.requests.get(0).options().get("system").toString()).contains("商家已经为它写好了回答");
        assertThat(feed("c-1")).containsEntry("status", "ANSWERED").containsEntry("source", "SESSION")
                .containsEntry("answer", "我们每天营业到晚上 9 点哈。");

        // Same meaning, other words: the model is asked to think, names the saved Q&A and words the answer.
        writer.reply = request -> decided("提问", 1, "晚上 9 点关门哦。");
        api(post(path("/mock-comments")), comment("c-2", "你们营业到几点呀"));
        drain();
        assertThat(writer.requests.get(1).options()).containsEntry("reasoning", true);
        assertThat(writer.requests.get(1).prompt()).contains("1. 问：几点关门 答：晚上 9 点", "名称：椴树蜂蜜", "【观众弹幕】你们营业到几点呀");
        assertThat(feed("c-2")).containsEntry("status", "ANSWERED").containsEntry("source", "SESSION")
                .containsEntry("answer", "晚上 9 点关门哦。");

        // No saved question fits, but the product facts do.
        writer.reply = request -> decided("提问", 0, "这款椴树蜂蜜一罐是 500 克的。");
        api(post(path("/mock-comments")), comment("c-3", "一罐多大"));
        drain();
        assertThat(feed("c-3")).containsEntry("status", "ANSWERED").containsEntry("source", "AI")
                .containsEntry("answer", "这款椴树蜂蜜一罐是 500 克的。");

        // Praise needs no response: no reply, and nothing for the merchant to fill in.
        writer.reply = request -> decided("其他", 0, "谢谢喜欢");
        api(post(path("/mock-comments")), comment("c-4", "蜂蜜不错 润喉"));
        drain();
        assertThat(feed("c-4")).containsEntry("status", "SKIPPED").containsEntry("answer", null).containsEntry("knowledge_gap", false);

        // A wish to buy is met with a nudge, from the saved facts like any other reply.
        writer.reply = request -> decided("想买", 0, "想要的朋友直接拍，69 元一罐。");
        api(post(path("/mock-comments")), comment("c-5", "有点想买"));
        drain();
        assertThat(feed("c-5")).containsEntry("status", "ANSWERED").containsEntry("source", "AI")
                .containsEntry("answer", "想要的朋友直接拍，69 元一罐。");

        // A complaint is not answered, whatever the model wrote, and is not let go either.
        writer.reply = request -> decided("转人工", 0, "不好意思哈，可能是个人体质问题。");
        api(post(path("/mock-comments")), comment("c-6", "上次喝了拉肚子"));
        drain();
        assertThat(feed("c-6")).containsEntry("status", "ATTENTION").containsEntry("answer", null)
                .containsEntry("note", "需要人工处理：观众说的话").containsEntry("knowledge_gap", false);

        // Four replies were synthesised and pushed as interruptions, in order; the others said nothing.
        assertThat(owner.queryForList("SELECT text FROM live_speech_items WHERE kind='REPLY' AND status='READY' ORDER BY id", String.class))
                .containsExactly("我们每天营业到晚上 9 点哈。", "晚上 9 点关门哦。", "这款椴树蜂蜜一罐是 500 克的。", "想要的朋友直接拍，69 元一罐。");
        assertThat(commands(socket)).hasSize(4).allMatch(command -> "INTERRUPT".equals(command.path("mode").asText()));
        // Nothing is being narrated, so no reply carries a line to get back to it.
        assertThat(owner.queryForObject("SELECT count(*) FROM live_speech_items WHERE outro_text IS NOT NULL", Integer.class)).isZero();

        // Judging and synthesis for replies never share narration's queue, and each clip was made by
        // the task that worded it: the task queued as a fallback found it done.
        assertThat(owner.queryForList("SELECT DISTINCT queue FROM tasks WHERE type='LIVE_REPLY_GENERATE'", String.class)).containsExactly("LIVE_REPLY");
        assertThat(owner.queryForList("SELECT queue || ':' || (result->>'status') FROM tasks WHERE type='LIVE_SPEECH_SYNTHESIZE'", String.class))
                .hasSize(4).containsOnly("LIVE_REPLY:SKIPPED");

        // The same comment arriving again buys nothing.
        assertThat(api(post(path("/mock-comments")), comment("c-3", "一罐多大")).path("status").asText()).isEqualTo("answered");
        drain();
        assertThat(writer.requests).hasSize(6);
        assertThat(owner.queryForObject("SELECT count(*) FROM live_comments", Integer.class)).isEqualTo(6);

        // The console's feed lists them newest first.
        JsonNode realtime = api(get(path("/realtime")), null);
        assertThat(realtime.path("items")).extracting(item -> item.path("text").asText())
                .containsExactly("上次喝了拉肚子", "有点想买", "蜂蜜不错 润喉", "一罐多大", "你们营业到几点呀", "几点关门？");
        assertThat(realtime.path("items").get(0).path("state").asText()).isEqualTo("ATTENTION");
        assertThat(realtime.path("items").get(2).path("state").asText()).isEqualTo("SKIPPED");
        assertThat(realtime.path("items").get(0).path("provider").asText()).isEqualTo("MOCK");
    }

    @Test
    void aSavedAnswerIsSpokenAsWrittenWheneverPuttingItIntoSpokenWordsDoesNotWorkOut() throws Exception {
        connectPlayer();
        api(post(path("/qa")), Map.of("question", "几点关门", "answer", "晚上 9 点"));

        // No text model at all: the merchant's answer does not depend on one.
        writer.reply = request -> Map.of("echo", request.prompt());
        api(post(path("/mock-comments")), comment("c-1", "几点关门"));
        drain();
        assertThat(feed("c-1")).containsEntry("status", "ANSWERED").containsEntry("answer", "晚上 9 点").containsEntry("source", "SESSION");

        // A rewording that changes the facts is refused twice, then dropped in favour of the original.
        writer.requests.clear();
        writer.reply = request -> Map.of("text", "我们每天营业到晚上 10 点哈。");
        api(post(path("/mock-comments")), comment("c-2", "几点关门?"));
        drain();
        assertThat(writer.requests).hasSize(2);
        assertThat(writer.requests.get(1).prompt()).contains("上一版不合格", "10");
        assertThat(feed("c-2")).containsEntry("status", "ANSWERED").containsEntry("answer", "晚上 9 点");

        // The model service failing outright is no different.
        writer.reply = request -> { throw new IllegalStateException("model down"); };
        api(post(path("/mock-comments")), comment("c-3", "几点 关门"));
        drain();
        assertThat(feed("c-3")).containsEntry("status", "ANSWERED").containsEntry("answer", "晚上 9 点");
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks WHERE status <> 'SUCCEEDED'", Integer.class)).isZero();
    }

    @Test
    void aReplyDuringNarrationSaysWhatWasAskedAndTheHostEndsWithALineBackToTheNarration() throws Exception {
        api(post(path("/qa")), Map.of("question", "几点关门", "answer", "我们每天晚上 9 点关门。"));
        api(post(path("/start")), null);
        WebSocketSession socket = connectPlayer();
        // A provider that reports when each character is spoken: 100 ms apiece.
        when(voiceProvider.synthesize(anyString(), anyString(), any())).thenAnswer(call -> {
            String text = call.getArgument(0);
            List<VoiceProvider.Word> words = new ArrayList<>();
            for (int i = 0; i < text.length(); i++) words.add(new VoiceProvider.Word(text.substring(i, i + 1), i * 100L, i * 100L + 100));
            return new VoiceProvider.Audio(new byte[text.length() * 4800], 24000, 80, words);
        });
        api(post(path("/auto-script/start")), null);
        drain();

        // The model words the whole reply, lead-in included; it is told that it is cutting into narration.
        writer.reply = request -> Map.of("text", "有朋友问几点关门，我们每天晚上 9 点关门哈。");
        api(post(path("/mock-comments")), comment("c-1", "几点关门"));
        drain();
        assertThat(writer.requests.get(writer.requests.size() - 1).options().get("system").toString()).contains("这条弹幕打断了讲解");
        writer.reply = request -> decided("提问", 0, "有朋友问一罐多大，这款椴树蜂蜜一罐是 500 克的。");
        api(post(path("/mock-comments")), comment("c-2", "一罐多大"));
        drain();
        // Spoken as written, the saved answer gets a fixed lead-in quoting the merchant's own question.
        writer.reply = request -> Map.of("echo", request.prompt());
        api(post(path("/mock-comments")), comment("c-3", "几点关门？"));
        drain();

        var replies = owner.queryForList("SELECT text, outro_text, outro_offset_millis, duration_millis, pause_offsets::text AS pauses "
                + "FROM live_speech_items WHERE kind='REPLY' ORDER BY id");
        assertThat(replies).hasSize(3).allSatisfy(reply -> {
            // The closing line is the last sentence of the same clip; where it starts comes from the timing.
            String closing = (String) reply.get("outro_text");
            long expected = (((String) reply.get("text")).length() - 1) * 100L;
            assertThat(closing).endsWith("。");
            assertThat((Long) reply.get("outro_offset_millis")).isBetween(expected - 80, expected + 180)
                    .isLessThan((Long) reply.get("duration_millis"));
            assertThat(reply.get("pauses")).as("a reply is never interrupted").isEqualTo("[]");
        });
        assertThat(replies.get(0).get("text")).isEqualTo("有朋友问几点关门，我们每天晚上 9 点关门哈。");
        assertThat(replies.get(1).get("text")).isEqualTo("有朋友问一罐多大，这款椴树蜂蜜一罐是 500 克的。");
        assertThat((String) replies.get(2).get("text")).endsWith("问几点关门，我们每天晚上 9 点关门。");

        // The player is told where the closing line starts, so it can leave it out.
        assertThat(commands(socket)).filteredOn(command -> command.path("id").asText().startsWith("comment:"))
                .hasSize(3).allMatch(command -> command.path("outroOffsetMillis").asLong() > 1000);
        assertThat(commands(socket)).filteredOn(command -> command.path("id").asText().startsWith("script-"))
                .allMatch(command -> command.path("outroOffsetMillis").asLong(0) == 0);
    }

    @Test
    void aSessionCanHaveItsCoHostAnswerViewersWhileTheHostAloneNarrates() throws Exception {
        long duet = api(post("/api/stores/" + store + "/live-sessions"), Map.of("name", "双人场",
                "productIds", List.of(product), "config", Map.of("replyByCohost", true,
                        "voiceRoles", List.of(Map.of("id", "builtin:voice-a", "role", "host"),
                                Map.of("id", "builtin:voice-b", "role", "cohost"))))).path("id").asLong();
        String base = "/api/live-sessions/" + duet;
        assertThat(api(get(base), null).path("config").path("replyByCohost").asBoolean()).isTrue();
        api(post(base + "/qa"), Map.of("question", "几点关门", "answer", "我们每天晚上 9 点关门。"));
        api(post(base + "/start"), null);
        api(post(base + "/auto-script/start"), null);
        drain();

        // The console still names the host; the session's own setting decides who answers.
        writer.reply = request -> Map.of("text", "有朋友问几点关门，我们每天晚上 9 点关门哈。");
        api(post(base + "/mock-comments"), comment("c-1", "几点关门"));
        drain();
        assertThat(writer.requests.get(writer.requests.size() - 1).options().get("system").toString())
                .startsWith("你是一场抖音实景直播的助播。").contains("主播正在讲解商品");
        writer.reply = request -> decided("提问", 0, "有朋友问一罐多大，这款椴树蜂蜜一罐是 500 克的。");
        api(post(base + "/mock-comments"), comment("c-2", "一罐多大"));
        drain();
        assertThat(writer.requests.get(writer.requests.size() - 1).options().get("system").toString())
                .startsWith("你是一场抖音实景直播的助播。");

        assertThat(owner.queryForList("SELECT DISTINCT voice FROM live_speech_items WHERE session_id=? AND kind='REPLY'", String.class, duet))
                .containsExactly("builtin:voice-b");
        // She answers and stops: the host's voice coming back is the hand-over.
        assertThat(owner.queryForObject("SELECT count(*) FROM live_speech_items WHERE session_id=? AND kind='REPLY' AND outro_text IS NULL",
                Integer.class, duet)).isEqualTo(2);
        // The second pass over the product is where a narrating co-host would take her turn. She does not.
        api(post(base + "/auto-script/stop"), null);
        owner.update("UPDATE live_speech_items SET status='PLAYED' WHERE session_id=? AND kind='SCRIPT'", duet);
        owner.update("UPDATE live_script_states SET next_seq = 4 WHERE session_id = ?", duet);
        writer.reply = request -> Map.of("text", HONEY);
        api(post(base + "/auto-script/start"), null);
        drain();
        assertThat(owner.queryForObject("SELECT max(seq) FROM live_speech_items WHERE session_id=? AND kind='SCRIPT'", Integer.class, duet))
                .isGreaterThanOrEqualTo(4);
        assertThat(owner.queryForList("SELECT DISTINCT voice FROM live_speech_items WHERE session_id=? AND kind='SCRIPT'", String.class, duet))
                .containsExactly("builtin:voice-a");
    }

    @Test
    void questionsTheMaterialCannotAnswerAreKeptForTheMerchantUntilTheLibraryCoversThem() throws Exception {
        connectPlayer();
        long synthesised = synthesised();

        // A real question with nothing to answer it from: said three ways, it is still one gap.
        writer.reply = request -> decided("提问", 0, "");
        api(post(path("/mock-comments")), comment("c-1", "能打包吗"));
        api(post(path("/mock-comments")), comment("c-2", "能打包吗？"));
        api(post(path("/mock-comments")), comment("c-3", "保质期多久"));
        drain();
        assertThat(feed("c-1")).containsEntry("status", "UNANSWERED").containsEntry("knowledge_gap", true);
        assertThat((String) feed("c-1").get("note")).contains("没有相关内容");

        // An invented price fails the check twice: nothing is spoken, and the question is a gap too.
        writer.requests.clear();
        writer.reply = request -> decided("提问", 0, "这款蜂蜜今天直播间只要 39 元。");
        api(post(path("/mock-comments")), comment("c-4", "能便宜点吗"));
        drain();
        assertThat(writer.requests).hasSize(2);
        assertThat(writer.requests.get(1).prompt()).contains("上一版不合格", "39");
        assertThat(feed("c-4")).containsEntry("status", "UNANSWERED").containsEntry("knowledge_gap", true);
        assertThat((String) feed("c-4").get("note")).contains("未通过校验", "39");

        // A number the viewer typed is not a fact the answer may repeat.
        writer.reply = request -> decided("提问", 0, "对的，两罐一共 88 元。");
        api(post(path("/mock-comments")), comment("c-5", "两罐是不是 88 元"));
        drain();
        assertThat(feed("c-5")).containsEntry("status", "UNANSWERED");

        // A wish to buy that yields nothing sayable is not a question left unanswered.
        writer.reply = request -> decided("想买", 0, "今天下单全国包邮哦。");
        api(post(path("/mock-comments")), comment("c-9", "想买两罐"));
        drain();
        assertThat(feed("c-9")).containsEntry("status", "UNANSWERED").containsEntry("knowledge_gap", false);
        writer.reply = request -> decided("想买", 0, "");
        api(post(path("/mock-comments")), comment("c-10", "想买槐花蜜"));
        drain();
        assertThat(feed("c-10")).containsEntry("status", "SKIPPED").containsEntry("knowledge_gap", false);

        // Praise, output that cannot be read, and no model at all are not gaps in the material.
        writer.reply = request -> decided("其他", 0, "");
        api(post(path("/mock-comments")), comment("c-6", "主播声音真好听"));
        drain();
        assertThat(feed("c-6")).containsEntry("status", "SKIPPED").containsEntry("knowledge_gap", false);
        writer.reply = request -> Map.of("text", "命中 7");
        api(post(path("/mock-comments")), comment("c-7", "有发票吗"));
        drain();
        assertThat(feed("c-7")).containsEntry("status", "FAILED").containsEntry("knowledge_gap", false);
        assertThat((String) feed("c-7").get("note")).contains("格式");
        writer.reply = request -> Map.of("echo", request.prompt());
        api(post(path("/mock-comments")), comment("c-8", "怎么保存"));
        drain();
        assertThat(feed("c-8")).containsEntry("status", "FAILED").containsEntry("knowledge_gap", false);
        assertThat((String) feed("c-8").get("note")).contains("尚未配置文本大模型");

        assertThat(owner.queryForObject("SELECT count(*) FROM live_speech_items", Integer.class)).isZero();
        assertThat(synthesised()).isEqualTo(synthesised);
        assertThat(owner.queryForObject("SELECT count(*) FROM tasks WHERE status <> 'SUCCEEDED'", Integer.class)).isZero();

        // The list for the merchant: most asked first, the latest wording of each.
        JsonNode gaps = api(get(path("/unanswered-questions")), null);
        assertThat(gaps).extracting(gap -> gap.path("text").asText() + "×" + gap.path("count").asInt())
                .containsExactlyInAnyOrder("能打包吗？×2", "保质期多久×1", "能便宜点吗×1", "两罐是不是 88 元×1");
        assertThat(gaps.get(0).path("text").asText()).isEqualTo("能打包吗？");

        // Once the product library answers a question, it is no longer asked of the merchant.
        api(post("/api/products/" + product + "/faqs"), Map.of("question", "能打包吗", "answer", "可以打包带走"));
        assertThat(api(get(path("/unanswered-questions")), null)).extracting(gap -> gap.path("text").asText())
                .containsExactlyInAnyOrder("保质期多久", "能便宜点吗", "两罐是不是 88 元");
    }

    @Test
    void whetherThisIsARecordingIsAnsweredTruthfullyAndNoReplyEverClaimsToBeARealPerson() throws Exception {
        connectPlayer();
        // The model is told what is true of the stream; its first wording adds a claim that is not.
        writer.reply = request -> decided("提问", 0, request.prompt().contains("上一版不合格")
                ? "不是录播哈，咱们是实时直播，画面是现场实拍的。" : "不是录播哦，真人实时在播。");
        api(post(path("/mock-comments")), comment("c-1", "是不是录播"));
        drain();
        assertThat(writer.requests.get(0).prompt()).contains("【直播间情况】这是实时直播，不是录播");
        assertThat(writer.requests.get(1).prompt()).contains("上一版不合格", "不能谈论主播是真人还是 AI");
        assertThat(feed("c-1")).containsEntry("status", "ANSWERED").containsEntry("answer", "不是录播哈，咱们是实时直播，画面是现场实拍的。");

        // Asked outright who or what the host is, the model is to let the comment go. If it answers
        // instead, nothing is spoken whichever way it answers, and that is no gap in the merchant's material.
        writer.reply = request -> decided("提问", 0, "放心，是真人在播。");
        api(post(path("/mock-comments")), comment("c-2", "主播是真人吗"));
        drain();
        assertThat(feed("c-2")).containsEntry("status", "UNANSWERED").containsEntry("answer", null).containsEntry("knowledge_gap", false);
        writer.reply = request -> decided("提问", 0, "我是 AI 主播，声音是合成的哦。");
        api(post(path("/mock-comments")), comment("c-3", "你是 AI 吗"));
        drain();
        assertThat(feed("c-3")).containsEntry("status", "UNANSWERED").containsEntry("answer", null).containsEntry("knowledge_gap", false);
        writer.reply = request -> decided("其他", 0, "");
        api(post(path("/mock-comments")), comment("c-5", "是机器人在播吧"));
        drain();
        assertThat(feed("c-5")).containsEntry("status", "SKIPPED").containsEntry("answer", null);

        // A reply that was worded but could not be turned into audio is reported on the comment.
        when(voiceProvider.synthesize(anyString(), anyString(), any())).thenThrow(new IllegalStateException("voice down"));
        writer.reply = request -> decided("提问", 0, "这款椴树蜂蜜一罐是 500 克的。");
        api(post(path("/mock-comments")), comment("c-6", "一罐多大"));
        drain();
        assertThat(feed("c-6")).containsEntry("status", "FAILED").containsEntry("answer", null);
        assertThat((String) feed("c-6").get("note")).contains("语音没有合成出来");
        reset(voiceProvider);
        when(voiceProvider.configured()).thenReturn(true);
        when(voiceProvider.builtInVoices()).thenReturn(List.of("voice-a", "voice-b"));
        when(voiceProvider.synthesize(anyString(), anyString(), any())).thenReturn(new VoiceProvider.Audio(new byte[48000], 24000, 80));

        // The same holds when a saved answer is being put into spoken words.
        api(post(path("/qa")), Map.of("question", "几点关门", "answer", "晚上 9 点"));
        writer.reply = request -> Map.of("text", "真人主播告诉你，晚上 9 点关门。");
        api(post(path("/mock-comments")), comment("c-4", "几点关门"));
        drain();
        assertThat(feed("c-4")).containsEntry("status", "ANSWERED").containsEntry("answer", "晚上 9 点");
        assertThat(owner.queryForList("SELECT text FROM live_speech_items WHERE kind='REPLY' AND status='READY' ORDER BY id", String.class))
                .containsExactly("不是录播哈，咱们是实时直播，画面是现场实拍的。", "晚上 9 点");
    }

    @Test
    void aQuestionStillWaitingWhenTheSessionEndsIsNeverAnswered() throws Exception {
        api(post(path("/start")), null);
        api(post(path("/mock-comments")), comment("c-1", "保质期多久"));
        api(post(path("/end")), null);
        drain();
        assertThat(feed("c-1")).containsEntry("status", "UNANSWERED").containsEntry("note", "场次已结束");
        assertThat(writer.requests).isEmpty();
    }

    @Test
    void speechTablesAreIsolatedPerTenant() throws Exception {
        api(post(path("/start")), null);
        api(post(path("/auto-script/start")), null);
        api(post(path("/mock-comments")), comment("c-1", "保质期多久"));
        Long otherTenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('别家') RETURNING id", Long.class);
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "growth_app", "growth_dev_local")) {
            for (String table : List.of("live_speech_items", "live_script_states", "live_comments")) {
                connection.createStatement().execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
                try (var rows = connection.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).as(table).isEqualTo(1);
                }
                connection.createStatement().execute("SELECT set_config('app.tenant_id', '" + otherTenant + "', false)");
                try (var rows = connection.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).as(table).isZero();
                }
            }
        }
    }

    private String playerToken;

    private WebSocketSession connectPlayer() throws Exception {
        playerToken = api(post(path("/player/pairing")), null).path("token").asText();
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getUri()).thenReturn(java.net.URI.create("ws://localhost/api/player/ws?token=" + playerToken));
        when(socket.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(socket.isOpen()).thenReturn(true);
        playerSocketHandler.afterConnectionEstablished(socket);
        playerSocketHandler.handleMessage(socket, new TextMessage("{\"type\":\"heartbeat\"}"));
        return socket;
    }

    private void acknowledge(WebSocketSession socket, String id) throws Exception {
        playerSocketHandler.handleMessage(socket, new TextMessage("{\"type\":\"ack\",\"id\":\"" + id + "\"}"));
    }

    private long synthesised() {
        return mockingDetails(voiceProvider).getInvocations().stream()
                .filter(invocation -> "synthesize".equals(invocation.getMethod().getName())).count();
    }

    /** Runs worker rounds until nothing is left to claim. */
    private void drain() {
        for (int round = 0; round < 20; round++) {
            worker.poll();
            // poll() hands each task to the worker's own thread; wait for it before looking at what is left.
            await().atMost(Duration.ofSeconds(30)).until(() ->
                    owner.queryForObject("SELECT count(*) FROM tasks WHERE status = 'RUNNING'", Integer.class) == 0);
            if (owner.queryForObject("SELECT count(*) FROM tasks WHERE status = 'PENDING'", Integer.class) == 0) return;
        }
        throw new AssertionError("任务没有在 20 轮内处理完");
    }

    /** Commands the player was sent, in order: the snapshot's first, then each push. */
    private List<JsonNode> commands(WebSocketSession socket) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, atLeastOnce()).sendMessage(captor.capture());
        List<JsonNode> commands = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            JsonNode node = json.readTree(message.getPayload());
            if ("snapshot".equals(node.path("type").asText())) node.path("commands").forEach(commands::add);
            if ("command".equals(node.path("type").asText())) commands.add(node.path("command"));
        }
        return commands;
    }

    private static Map<String, Object> comment(String id, String text) {
        return Map.of("id", id, "text", text, "builtInVoice", "voice-a");
    }

    /** The stored outcome of the comment submitted under this id. */
    private Map<String, Object> feed(String id) {
        return owner.queryForMap("SELECT status, answer, source, note, knowledge_gap FROM live_comments WHERE external_id = ?",
                com.wuyao.growth.live.player.PlayerTokenService.sha256(id));
    }

    private List<String> scripts(String column) {
        return owner.queryForList("SELECT " + column + " FROM live_speech_items WHERE kind='SCRIPT' ORDER BY id", String.class);
    }

    private List<String> clips() throws Exception {
        List<String> keys = new ArrayList<>();
        for (var result : minio.listObjects(ListObjectsArgs.builder().bucket("test-assets")
                .prefix("t" + tenant + "/live-speech/").recursive(true).build())) keys.add(result.get().objectName());
        return keys;
    }

    private String path(String suffix) {
        return "/api/live-sessions/" + session + suffix;
    }

    private JsonNode api(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
        var response = mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("data");
    }
}
