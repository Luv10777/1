package com.wuyao.growth.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.gateway.ProviderResult;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskRepository;
import com.wuyao.growth.common.tenant.TenantContext;
import org.flywaydb.core.Flyway;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 问答集只有启用 / 归档，问答只有启用 / 停用；两者都启用才进入开播时读取的门店知识。 */
@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN",
        // 本机 .env 不能把这组测试指向真实的模型或存储桶。
        "growth.ai.writer.url=", "growth.voice.sample-storage=minio"})
@AutoConfigureMockMvc
@Testcontainers
class KnowledgeIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("knowledge_test").withUsername("growth_owner").withPassword("test_owner_password");

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
    @Autowired TaskRepository taskRepository;
    @Autowired KnowledgeDocumentDigestHandler digestHandler;
    // 文件读取和文本模型换成替身：这里验证的是整理结果怎么落地，不是模型本身。
    @MockitoBean AssetService assets;
    @MockitoBean AiGateway gateway;
    private final ObjectMapper json = new ObjectMapper();
    JdbcTemplate owner;
    String token;
    long store;
    Long tenant;
    Long user;

    @BeforeEach
    void setUp() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        tenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('甲商户') RETURNING id", Long.class);
        com.wuyao.growth.billing.BillingTestFixtures.activateMembership(owner, tenant);
        user = owner.queryForObject(
                "INSERT INTO users(tenant_id,username,name) VALUES (?, 'owner', '管理员') RETURNING id", Long.class, tenant);
        token = jwt.issueAccessToken(user, tenant, null);
        store = api(post("/api/stores"), Map.of("name", "知识门店")).path("id").asLong();
    }

    @Test
    void anEntryCountsAsSoonAsItAndItsSetAreSwitchedOn() throws Exception {
        JsonNode set = api(post("/api/stores/" + store + "/knowledge-sets"), Map.of("name", "门店规则"));
        assertThat(set.path("status").asText()).isEqualTo("ACTIVE");
        long setId = set.path("id").asLong();

        JsonNode hours = api(post("/api/knowledge-sets/" + setId + "/entries"),
                Map.of("question", "营业时间", "answer", "每天 9 点至 18 点"));
        assertThat(hours.path("status").asText()).as("不带状态时默认启用").isEqualTo("ACTIVE");
        long parking = api(post("/api/knowledge-sets/" + setId + "/entries"),
                Map.of("question", "停车", "answer", "门口可停", "status", "DISABLED")).path("id").asLong();
        assertThat(contextQuestions()).containsExactly("营业时间");

        api(patch("/api/knowledge-entries/" + parking), Map.of("status", "ACTIVE"));
        assertThat(contextQuestions()).containsExactly("营业时间", "停车");

        // 删除是真的删除，不会以停用的样子再冒出来。
        api(delete("/api/knowledge-entries/" + parking), null);
        assertThat(owner.queryForObject("SELECT count(*) FROM knowledge_entries WHERE id = ?", Integer.class, parking))
                .isZero();
        assertThat(api(get("/api/knowledge-sets/" + setId + "/entries"), null).path("total").asInt()).isEqualTo(1);
    }

    @Test
    void anArchivedSetIsReadOnlyAndRestoringBringsItBackAsItWas() throws Exception {
        long setId = api(post("/api/stores/" + store + "/knowledge-sets"), Map.of("name", "售后")).path("id").asLong();
        long on = api(post("/api/knowledge-sets/" + setId + "/entries"),
                Map.of("question", "退换", "answer", "七天内可退")).path("id").asLong();
        api(post("/api/knowledge-sets/" + setId + "/entries"),
                Map.of("question", "保修", "answer", "一年", "status", "DISABLED"));

        assertThat(api(post("/api/knowledge-sets/" + setId + "/archive"), null).path("status").asText())
                .isEqualTo("ARCHIVED");
        assertThat(contextQuestions()).isEmpty();
        refused(post("/api/knowledge-sets/" + setId + "/entries"), Map.of("question", "新问题", "answer", "新回答"));
        refused(patch("/api/knowledge-entries/" + on), Map.of("answer", "改不了"));
        refused(delete("/api/knowledge-entries/" + on), null);
        refused(patch("/api/knowledge-sets/" + setId), Map.of("name", "改名"));

        assertThat(api(post("/api/knowledge-sets/" + setId + "/restore"), null).path("status").asText())
                .isEqualTo("ACTIVE");
        JsonNode entries = api(get("/api/knowledge-sets/" + setId + "/entries"), null).path("items");
        Map<String, String> statuses = new LinkedHashMap<>();
        entries.forEach(entry -> statuses.put(entry.path("question").asText(), entry.path("status").asText()));
        assertThat(statuses).containsEntry("退换", "ACTIVE").containsEntry("保修", "DISABLED");
        assertThat(contextQuestions()).containsExactly("退换");
    }

    /** V60 只能让原来不生效的问答继续不生效，原来生效的继续生效。 */
    @Test
    void v59KeepsWhatWasLiveLiveAndWhatWasNotSwitchedOff() {
        String database = "knowledge_v59";
        owner.execute("DROP DATABASE IF EXISTS " + database);
        owner.execute("CREATE DATABASE " + database);
        String url = POSTGRES.getJdbcUrl().replace("/knowledge_test", "/" + database);
        Flyway flyway = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").placeholders(Map.of("app_db_password", "growth_dev_local"))
                .target("58").load();
        flyway.migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        Long tenant = old.queryForObject("INSERT INTO tenants(name) VALUES ('旧商户') RETURNING id", Long.class);
        Long user = old.queryForObject(
                "INSERT INTO users(tenant_id,username,name) VALUES (?, 'old', '旧管理员') RETURNING id", Long.class, tenant);
        Long oldStore = old.queryForObject(
                "INSERT INTO stores(tenant_id,name,created_by) VALUES (?, '旧门店', ?) RETURNING id", Long.class, tenant, user);
        Long product = old.queryForObject("""
                INSERT INTO products(tenant_id,store_id,name,type,category,price,sale_unit)
                VALUES (?, ?, '蜂蜜', 'PHYSICAL', '食品', 1, '罐') RETURNING id""", Long.class, tenant, oldStore);
        long published = oldSet(old, tenant, oldStore, "PUBLISHED");
        long draft = oldSet(old, tenant, oldStore, "DRAFT");
        long disabled = oldSet(old, tenant, oldStore, "DISABLED");
        long archived = oldSet(old, tenant, oldStore, "ARCHIVED");
        oldEntry(old, tenant, oldStore, published, "已发布·启用", "ACTIVE", "MANUAL", null);
        oldEntry(old, tenant, oldStore, published, "已发布·草稿", "DRAFT", "LIVE_DRAFT", null);
        oldEntry(old, tenant, oldStore, published, "已发布·已删除", "DISABLED", "MANUAL", null);
        oldEntry(old, tenant, oldStore, published, "商品专属", "ACTIVE", "MANUAL", product);
        oldEntry(old, tenant, oldStore, draft, "草稿集·启用", "ACTIVE", "AI_SUGGESTION", null);
        oldEntry(old, tenant, oldStore, disabled, "停用集·启用", "ACTIVE", "MANUAL", null);
        oldEntry(old, tenant, oldStore, archived, "归档集·停用", "DISABLED", "MANUAL", null);

        flyway = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").placeholders(Map.of("app_db_password", "growth_dev_local")).load();
        flyway.migrate();

        assertThat(old.queryForList("SELECT status FROM knowledge_sets ORDER BY id", String.class))
                .containsExactly("ACTIVE", "ACTIVE", "ACTIVE", "ARCHIVED");
        Map<String, String> statuses = new LinkedHashMap<>();
        old.queryForList("SELECT question, status, source FROM knowledge_entries ORDER BY id").forEach(row ->
                statuses.put((String) row.get("question"), row.get("status") + "/" + row.get("source")));
        assertThat(statuses).containsExactly(
                Map.entry("已发布·启用", "ACTIVE/MANUAL"),
                Map.entry("已发布·草稿", "DISABLED/LIVE"),
                Map.entry("草稿集·启用", "DISABLED/MANUAL"),
                Map.entry("停用集·启用", "DISABLED/MANUAL"),
                Map.entry("归档集·停用", "DISABLED/MANUAL"));
        owner.execute("DROP DATABASE " + database + " WITH (FORCE)");
    }

    private static long oldSet(JdbcTemplate db, Long tenant, Long store, String status) {
        return db.queryForObject("""
                INSERT INTO knowledge_sets(tenant_id,store_id,name,status) VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, tenant, store, status, status);
    }

    private static void oldEntry(JdbcTemplate db, Long tenant, Long store, long set, String question,
                                 String status, String source, Long product) {
        db.update("""
                INSERT INTO knowledge_entries(tenant_id,store_id,knowledge_set_id,product_id,scope,source,status,question,answer)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '回答')""",
                tenant, store, set, product, product == null ? "STORE" : "PRODUCT", source, status, question);
    }

    private List<String> contextQuestions() throws Exception {
        JsonNode entries = api(get("/api/stores/" + store + "/knowledge-context"), null).path("entries");
        return java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                .map(entry -> entry.path("question").asText()).toList();
    }

    @Test
    void brandKnowledgeIsSharedByItsStoresAndOnlyTheOwnerChangesIt() throws Exception {
        // 第一个品牌会收下已有的门店。
        long brand = api(post("/api/brands"), Map.of("name", "青岚茶事")).path("id").asLong();
        long storeSet = api(post("/api/stores/" + store + "/knowledge-sets"), Map.of("name", "本店")).path("id").asLong();
        api(post("/api/knowledge-sets/" + storeSet + "/entries"), Map.of("question", "营业时间", "answer", "9 点到 21 点"));
        JsonNode brandSet = api(post("/api/brands/" + brand + "/knowledge-sets"), Map.of("name", "品牌通用"));
        assertThat(brandSet.path("brandId").asLong()).isEqualTo(brand);
        assertThat(brandSet.has("storeId")).as("接口不返回空字段").isFalse();
        long brandSetId = brandSet.path("id").asLong();
        api(post("/api/knowledge-sets/" + brandSetId + "/entries"), Map.of("question", "会员怎么积分", "answer", "一元一分"));

        assertThat(api(get("/api/stores/" + store + "/knowledge-context"), null).path("brandId").asLong()).isEqualTo(brand);
        assertThat(contextQuestions()).as("本店的在前，品牌的在后").containsExactly("营业时间", "会员怎么积分");

        // 另一个品牌的门店读不到这个品牌的问答。
        long other = api(post("/api/brands"), Map.of("name", "王记烧烤")).path("id").asLong();
        long otherStore = api(post("/api/stores"), Map.of("name", "烧烤一店", "brandId", other)).path("id").asLong();
        assertThat(api(get("/api/stores/" + otherStore + "/knowledge-context"), null).path("entries").size()).isZero();

        // 店员能看、开播时也用得到品牌问答，但改不了。
        Long staff = owner.queryForObject(
                "INSERT INTO users(tenant_id,username,name,role) VALUES (?, 'clerk', '店员', 'STAFF') RETURNING id", Long.class, tenant);
        owner.update("INSERT INTO store_members(tenant_id,store_id,user_id) VALUES (?, ?, ?)", tenant, store, staff);
        String mine = token;
        token = jwt.issueAccessToken(staff, tenant, null);
        try {
            assertThat(api(get("/api/brands/" + brand + "/knowledge-sets"), null).path("total").asInt()).isEqualTo(1);
            assertThat(contextQuestions()).containsExactly("营业时间", "会员怎么积分");
            mvc.perform(prepared(post("/api/knowledge-sets/" + brandSetId + "/entries"),
                    Map.of("question", "店员写的", "answer", "不行"))).andExpect(status().isForbidden());
            mvc.perform(prepared(post("/api/brands/" + brand + "/knowledge-sets"), Map.of("name", "店员建的")))
                    .andExpect(status().isForbidden());
        } finally {
            token = mine;
        }
    }

    @Test
    void aDocumentBecomesSuggestionsThatChangeNothingUntilAccepted() throws Exception {
        long brand = api(post("/api/brands"), Map.of("name", "青岚茶事", "slogan", "一杯好茶")).path("id").asLong();
        long asset = documentAsset("品牌手册.txt", "text/plain", "青岚茶事始于 1998 年。会员每消费一元积一分。");
        when(gateway.configured(ModelAlias.TEXT_WRITER)).thenReturn(true);
        when(gateway.invoke(any())).thenReturn(modelReply("""
                {"fields": {"slogan": "一杯好茶", "brandStory": "始于 1998 年的茶园", "website": "https://x.example"},
                 "faqs": [{"question": "会员怎么积分？", "answer": "每消费一元积一分。"},
                          {"question": "会员怎么积分", "answer": "重复的会被去掉"},
                          {"question": "开了多少年了？", "answer": "从 1998 年开始。"}]}"""));

        JsonNode created = api(post("/api/brands/" + brand + "/knowledge-documents"), Map.of("assetId", asset));
        assertThat(created.path("status").asText()).isEqualTo("PROCESSING");
        long document = created.path("id").asLong();
        runDigest();

        JsonNode detail = api(get("/api/knowledge-documents/" + document), null);
        assertThat(detail.path("document").path("status").asText()).isEqualTo("READY");
        assertThat(detail.path("document").path("pendingCount").asInt()).isEqualTo(3);
        Map<String, JsonNode> byKey = suggestionsByKey(detail);
        assertThat(byKey.keySet()).as("和现在一样的口号、不认识的字段、重复的问题都不会成为建议")
                .containsExactly("brandStory", "会员怎么积分？", "开了多少年了？");
        assertThat(byKey.get("brandStory").path("fieldLabel").asText()).isEqualTo("品牌故事");
        assertThat(api(get("/api/brands/" + brand), null).has("brandStory")).as("没采纳之前什么都不改").isFalse();

        // 问答要放进同一归属的问答集。
        long qa = byKey.get("会员怎么积分？").path("id").asLong();
        mvc.perform(prepared(post("/api/knowledge-suggestions/" + qa + "/accept"), Map.of())).andExpect(status().isBadRequest());
        long storeSet = api(post("/api/stores/" + store + "/knowledge-sets"), Map.of("name", "本店")).path("id").asLong();
        mvc.perform(prepared(post("/api/knowledge-suggestions/" + qa + "/accept"), Map.of("knowledgeSetId", storeSet)))
                .andExpect(status().isBadRequest());
        long brandSet = api(post("/api/brands/" + brand + "/knowledge-sets"), Map.of("name", "品牌通用")).path("id").asLong();
        api(post("/api/knowledge-suggestions/" + qa + "/accept"),
                Map.of("knowledgeSetId", brandSet, "answer", "每消费一元积一分，可以抵现。"));
        JsonNode entry = api(get("/api/knowledge-sets/" + brandSet + "/entries"), null).path("items").get(0);
        assertThat(entry.path("source").asText()).isEqualTo("DOCUMENT");
        assertThat(entry.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(entry.path("answer").asText()).isEqualTo("每消费一元积一分，可以抵现。");
        mvc.perform(prepared(post("/api/knowledge-suggestions/" + qa + "/accept"), Map.of("knowledgeSetId", brandSet)))
                .andExpect(status().isConflict());

        api(post("/api/knowledge-suggestions/" + byKey.get("brandStory").path("id").asLong() + "/accept"),
                Map.of("value", "1998 年，从一片茶园开始。"));
        JsonNode brandNow = api(get("/api/brands/" + brand), null);
        assertThat(brandNow.path("brandStory").asText()).isEqualTo("1998 年，从一片茶园开始。");
        assertThat(brandNow.path("slogan").asText()).as("其他字段原样保留").isEqualTo("一杯好茶");
        api(post("/api/knowledge-suggestions/" + byKey.get("开了多少年了？").path("id").asLong() + "/dismiss"), null);

        // 重新整理：处理过的不会再冒出来，新的照常出现。
        when(gateway.invoke(any())).thenReturn(modelReply("""
                {"fields": {"brandStory": "另一种写法", "culture": "敬茶如敬人"},
                 "faqs": [{"question": "会员怎么积分", "answer": "又说一遍"}, {"question": "开了多少年了", "answer": "又说一遍"},
                          {"question": "有包间吗？", "answer": "有两个包间。"}]}"""));
        api(post("/api/knowledge-documents/" + document + "/retry"), null);
        runDigest();
        List<String> pending = new java.util.ArrayList<>();
        suggestionsByKey(api(get("/api/knowledge-documents/" + document), null)).forEach((key, item) -> {
            if (item.path("status").asText().equals("PENDING")) pending.add(key);
        });
        assertThat(pending).containsExactly("culture", "有包间吗？");
    }

    @Test
    void aDocumentThatCannotBeReadFailsWithAReasonTheMerchantCanActOn() throws Exception {
        long oldWord = documentAsset("旧版说明.doc", "application/msword", "随便什么");
        long failed = api(post("/api/stores/" + store + "/knowledge-documents"), Map.of("assetId", oldWord)).path("id").asLong();
        runDigest();
        JsonNode view = api(get("/api/knowledge-documents/" + failed), null).path("document");
        assertThat(view.path("status").asText()).isEqualTo("FAILED");
        assertThat(view.path("errorMessage").asText()).contains("PDF");

        long text = documentAsset("门店介绍.txt", "text/plain", "店在西湖边。");
        when(gateway.configured(ModelAlias.TEXT_WRITER)).thenReturn(false);
        long unconfigured = api(post("/api/stores/" + store + "/knowledge-documents"), Map.of("assetId", text)).path("id").asLong();
        runDigest();
        assertThat(api(get("/api/knowledge-documents/" + unconfigured), null).path("document").path("errorMessage").asText())
                .contains("文本模型");
    }

    private static Map<String, JsonNode> suggestionsByKey(JsonNode detail) {
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        detail.path("suggestions").forEach(item -> byKey.put(item.path("kind").asText().equals("FIELD")
                ? item.path("field").asText() : item.path("question").asText(), item));
        return byKey;
    }

    /** A READY document asset row, with the asset module stubbed to hand back these bytes for it. */
    private long documentAsset(String name, String mimeType, String content) {
        long id = owner.queryForObject("""
                INSERT INTO assets(tenant_id,name,type,status,storage_key,mime_type,size_bytes,created_by)
                VALUES (?, ?, 'DOCUMENT', 'READY', ?, ?, ?, ?) RETURNING id""",
                Long.class, tenant, name, "t" + tenant + "/document/" + java.util.UUID.randomUUID(), mimeType, content.length(), user);
        when(assets.documentReference(id)).thenReturn(new AssetService.DocumentReference(id, name, mimeType));
        when(assets.readDocument(id)).thenReturn(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return id;
    }

    private static ProviderResult modelReply(String text) {
        return new ProviderResult(true, "TEST", null, Map.of("text", text), null, null);
    }

    /** Runs the newest digest task the way the worker would, without the clock-sensitive claim. */
    private void runDigest() {
        Long taskId = owner.queryForObject("SELECT id FROM tasks WHERE type = ? ORDER BY id DESC LIMIT 1", Long.class,
                KnowledgeDocumentDigestHandler.TYPE);
        TenantContext.runAs(tenant, () -> {
            Task task = taskRepository.findById(taskId).orElseThrow();
            return digestHandler.handle(task);
        });
    }

    private JsonNode api(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = mvc.perform(prepared(request, body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("data");
    }

    private void refused(MockHttpServletRequestBuilder request, Object body) throws Exception {
        mvc.perform(prepared(request, body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1409));
    }

    private MockHttpServletRequestBuilder prepared(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
        return request;
    }
}
