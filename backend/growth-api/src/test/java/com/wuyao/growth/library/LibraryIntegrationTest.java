package com.wuyao.growth.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 作品库：手动保存、文件夹、移动、移出，经真实的 HTTP 接口和数据库走一遍。 */
@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN",
        "growth.ai.writer.url=", "growth.voice.sample-storage=minio"})
@AutoConfigureMockMvc
@Testcontainers
class LibraryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("library_test").withUsername("growth_owner").withPassword("test_owner_password");

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
    private final ObjectMapper json = new ObjectMapper();
    JdbcTemplate owner;
    Long tenantA;
    Long tenantB;
    Long userA;
    Long colleagueA;
    String tokenA;
    String tokenColleague;
    String tokenB;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        tenantA = owner.queryForObject("INSERT INTO tenants(name) VALUES ('甲商户') RETURNING id", Long.class);
        tenantB = owner.queryForObject("INSERT INTO tenants(name) VALUES ('乙商户') RETURNING id", Long.class);
        userA = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?, 'owner-a', '甲管理员') RETURNING id", Long.class, tenantA);
        colleagueA = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?, 'staff-a', '甲店员') RETURNING id", Long.class, tenantA);
        Long userB = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?, 'owner-b', '乙管理员') RETURNING id", Long.class, tenantB);
        tokenA = jwt.issueAccessToken(userA, tenantA, null);
        tokenColleague = jwt.issueAccessToken(colleagueA, tenantA, null);
        tokenB = jwt.issueAccessToken(userB, tenantB, null);
    }

    @Test
    void finishedWorksAreSavedOnlyWhenAskedAndCanBeFiledMovedAndRemoved() throws Exception {
        long image = image(tenantA, userA, "SUCCEEDED", "t1/generated/1/1/image.png");
        long pending = image(tenantA, userA, "GENERATING", null);
        long video = video(tenantA, userA, "SUCCEEDED", "t1/generated-video/out.mp4");

        // 生成完不会自动进作品库。
        assertThat(api(get("/api/library/works"), tokenA, null).path("total").asLong()).isZero();

        long poster = api(post("/api/library/folders"), tokenA, Map.of("name", " 夏日海报 ")).path("id").asLong();
        refused(post("/api/library/folders"), tokenA, Map.of("name", "夏日海报"), 409, 1409);
        refused(post("/api/library/folders"), tokenA, Map.of("name", "  "), 400, 1400);
        // 同一商户的同事可以用同样的名字，文件夹各管各的。
        api(post("/api/library/folders"), tokenColleague, Map.of("name", "夏日海报"));

        JsonNode saved = api(post("/api/library/works"), tokenA, Map.of("kind", "IMAGE", "sourceId", image, "folderId", poster));
        assertThat(saved.path("folderName").asText()).isEqualTo("夏日海报");
        long imageWork = saved.path("workId").asLong();
        api(post("/api/library/works"), tokenA, body("kind", "VIDEO", "sourceId", video, "folderId", null));
        refused(post("/api/library/works"), tokenA, Map.of("kind", "IMAGE", "sourceId", pending), 400, 1400);
        // 别人的作品、别家的文件夹都存不进来。
        refused(post("/api/library/works"), tokenColleague, Map.of("kind", "IMAGE", "sourceId", image), 404, 1404);
        refused(post("/api/library/works"), tokenB, Map.of("kind", "VIDEO", "sourceId", video), 404, 1404);

        // 再保存一次是移到新位置，不会多出一件。
        JsonNode again = api(post("/api/library/works"), tokenA, body("kind", "IMAGE", "sourceId", image, "folderId", null));
        assertThat(again.path("workId").asLong()).isEqualTo(imageWork);
        assertThat(api(get("/api/library/works"), tokenA, null).path("total").asLong()).isEqualTo(2);
        api(put("/api/library/works/" + imageWork + "/folder"), tokenA, Map.of("folderId", poster));

        JsonNode folders = api(get("/api/library/folders"), tokenA, null);
        assertThat(folders.path("rootCount").asLong()).isEqualTo(1);
        assertThat(folders.path("folders").get(0).path("workCount").asLong()).isEqualTo(1);
        JsonNode inFolder = api(get("/api/library/works?folder=" + poster), tokenA, null);
        assertThat(inFolder.path("items")).hasSize(1);
        assertThat(inFolder.path("items").get(0).path("image").path("url").asText()).contains("image.png");
        JsonNode root = api(get("/api/library/works?folder=root"), tokenA, null);
        assertThat(root.path("items").get(0).path("video").path("outputUrl").asText()).contains("out.mp4");
        assertThat(api(get("/api/library/works?kind=IMAGE"), tokenA, null).path("total").asLong()).isEqualTo(1);
        assertThat(api(get("/api/library/saved?kind=IMAGE&sourceIds=" + image + "," + pending), tokenA, null))
                .singleElement().satisfies(row -> assertThat(row.path("folderName").asText()).isEqualTo("夏日海报"));

        // 别人看不到，也动不了。
        assertThat(api(get("/api/library/works"), tokenColleague, null).path("total").asLong()).isZero();
        refused(put("/api/library/works/" + imageWork + "/folder"), tokenColleague, body("folderId", null), 404, 1404);
        refused(get("/api/library/works?folder=" + poster), tokenB, null, 404, 1404);

        JsonNode renamed = api(put("/api/library/folders/" + poster), tokenA, Map.of("name", "七月海报"));
        assertThat(renamed.path("name").asText()).isEqualTo("七月海报");
        assertThat(renamed.path("workCount").asLong()).isEqualTo(1);

        // 删除文件夹，里面的作品一起移出作品库；生成记录本身还在，可以重新保存。
        api(delete("/api/library/folders/" + poster), tokenA, null);
        assertThat(api(get("/api/library/works"), tokenA, null).path("total").asLong()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM image_items WHERE id = ?", Long.class, image)).isEqualTo(1);
        api(post("/api/library/works"), tokenA, Map.of("kind", "IMAGE", "sourceId", image));

        long videoWork = api(get("/api/library/works?kind=VIDEO"), tokenA, null).path("items").get(0).path("id").asLong();
        api(delete("/api/library/works/" + videoWork), tokenA, null);
        assertThat(api(get("/api/library/works"), tokenA, null).path("items")).singleElement()
                .satisfies(row -> assertThat(row.path("kind").asText()).isEqualTo("IMAGE"));
    }

    @Test
    void severalWorksAreMovedIntoAFolderAtOnceAndAnUnknownWorkStopsTheWholeBatch() throws Exception {
        long first = api(post("/api/library/works"), tokenA, Map.of("kind", "IMAGE", "sourceId",
                image(tenantA, userA, "SUCCEEDED", "t1/generated/7/1/a.png"))).path("workId").asLong();
        long second = api(post("/api/library/works"), tokenA, Map.of("kind", "VIDEO", "sourceId",
                video(tenantA, userA, "SUCCEEDED", "t1/generated-video/b.mp4"))).path("workId").asLong();
        long others = api(post("/api/library/works"), tokenColleague, Map.of("kind", "IMAGE", "sourceId",
                image(tenantA, colleagueA, "SUCCEEDED", "t1/generated/8/1/c.png"))).path("workId").asLong();
        long folder = api(post("/api/library/folders"), tokenA, Map.of("name", "门店活动")).path("id").asLong();

        // 夹了一件同事的作品，整批都不动。
        refused(put("/api/library/works/folder"), tokenA, Map.of("workIds", java.util.List.of(first, others), "folderId", folder), 404, 1404);
        assert0(folder);
        refused(put("/api/library/works/folder"), tokenA, Map.of("workIds", java.util.List.of(), "folderId", folder), 400, 1400);

        assertThat(api(put("/api/library/works/folder"), tokenA, Map.of("workIds", java.util.List.of(first, second, first), "folderId", folder)).asInt())
                .isEqualTo(2);
        JsonNode folders = api(get("/api/library/folders"), tokenA, null);
        assertThat(folders.path("folders").get(0).path("workCount").asLong()).isEqualTo(2);
        assertThat(folders.path("rootCount").asLong()).isZero();

        // 移回未归类
        api(put("/api/library/works/folder"), tokenA, body("workIds", java.util.List.of(second), "folderId", null));
        assertThat(api(get("/api/library/folders"), tokenA, null).path("rootCount").asLong()).isEqualTo(1);
    }

    private void assert0(long folder) throws Exception {
        assertThat(api(get("/api/library/works?folder=" + folder), tokenA, null).path("total").asLong()).isZero();
    }

    @Test
    void theMigrationPutsEveryEarlierFinishedWorkIntoItsCreatorsRootFolder() throws Exception {
        long image = image(tenantA, userA, "SUCCEEDED", "t1/generated/2/1/image.png");
        image(tenantA, userA, "FAILED", null);
        image(tenantA, null, "SUCCEEDED", "t1/generated/3/1/image.png");
        long video = video(tenantA, colleagueA, "SUCCEEDED", "t1/generated-video/old.mp4");
        video(tenantB, null, "SUCCEEDED", "t2/generated-video/orphan.mp4");
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V89__works_library.sql"), StandardCharsets.UTF_8);
        owner.execute(migration.substring(migration.indexOf("DO $$")));

        assertThat(owner.queryForList("SELECT kind, source_id, owner_id FROM library_works WHERE folder_id IS NULL ORDER BY kind"))
                .containsExactly(
                        Map.of("kind", "IMAGE", "source_id", image, "owner_id", userA),
                        Map.of("kind", "VIDEO", "source_id", video, "owner_id", colleagueA));
        assertThat(owner.queryForObject("SELECT count(*) FROM library_works", Long.class)).isEqualTo(2);
        assertThat(api(get("/api/library/folders"), tokenA, null).path("rootCount").asLong()).isEqualTo(1);
    }

    private long image(Long tenant, Long creator, String status, String outputKey) {
        long creation = owner.queryForObject("INSERT INTO image_creations(tenant_id,request_key,request_hash,created_by,request,status) "
                        + "VALUES (?, gen_random_uuid()::text, 'h', ?, ?::jsonb, 'SUCCEEDED') RETURNING id", Long.class, tenant, creator,
                "{\"workflow\":\"POSTER\",\"brief\":\"夏日上新\",\"ratio\":\"3:4\",\"quality\":\"480P\",\"count\":1}");
        return owner.queryForObject("INSERT INTO image_items(tenant_id,creation_id,ordinal,spec,status,output_key) "
                + "VALUES (?, ?, 0, '{}'::jsonb, ?, ?) RETURNING id", Long.class, tenant, creation, status, outputKey);
    }

    private long video(Long tenant, Long creator, String status, String outputKey) {
        return owner.queryForObject("INSERT INTO video_workflows(tenant_id,created_by,request_key,request_hash,request,model,ratio,"
                        + "duration_seconds,resolution,status,output_storage_key) VALUES (?, ?, gen_random_uuid()::text, 'h', ?::jsonb, "
                        + "'SEEDANCE_2_0', '16:9', 5, '720p', ?, ?) RETURNING id", Long.class, tenant, creator,
                "{\"prompt\":\"门店开业\",\"model\":\"SEEDANCE_2_0\",\"ratio\":\"16:9\",\"durationSeconds\":5,\"resolution\":\"720p\"}",
                status, outputKey);
    }

    private static Map<String, Object> body(Object... pairs) {
        var map = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    private JsonNode api(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        var response = mvc.perform(prepared(request, token, body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private void refused(MockHttpServletRequestBuilder request, String token, Object body, int http, int code) throws Exception {
        mvc.perform(prepared(request, token, body)).andExpect(status().is(http)).andExpect(jsonPath("$.code").value(code));
    }

    private MockHttpServletRequestBuilder prepared(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
        return request;
    }
}
