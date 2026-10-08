package com.wuyao.growth.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 商户 → 品牌 → 门店 这层关系，经真实的 HTTP 接口和数据库走一遍。 */
@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN",
        // 本机 .env 不能把这组测试指向真实的模型或存储桶。
        "growth.ai.writer.url=", "growth.voice.sample-storage=minio"})
@AutoConfigureMockMvc
@Testcontainers
class BrandIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("brand_test").withUsername("growth_owner").withPassword("test_owner_password");

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
    @Autowired BrandService brands;
    private final ObjectMapper json = new ObjectMapper();
    JdbcTemplate owner;
    Long tenantA;
    Long tenantB;
    Long userA;
    String tokenA;
    String tokenB;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        tenantA = owner.queryForObject("INSERT INTO tenants(name) VALUES ('甲商户') RETURNING id", Long.class);
        tenantB = owner.queryForObject("INSERT INTO tenants(name) VALUES ('乙商户') RETURNING id", Long.class);
        userA = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?, 'owner-a', '甲管理员') RETURNING id", Long.class, tenantA);
        Long userB = owner.queryForObject("INSERT INTO users(tenant_id,username,name) VALUES (?, 'owner-b', '乙管理员') RETURNING id", Long.class, tenantB);
        tokenA = jwt.issueAccessToken(userA, tenantA, null);
        tokenB = jwt.issueAccessToken(userB, tenantB, null);
    }

    @Test
    void theFirstBrandBecomesTheDefaultAndTakesInTheStoresTheMerchantAlreadyHas() throws Exception {
        JsonNode before = api(post("/api/stores"), tokenA, Map.of("name", "老店"));
        assertThat(before.has("brandId")).as("没有品牌的商户，门店不归属任何品牌").isFalse();
        long oldStore = before.path("id").asLong();

        JsonNode tea = api(post("/api/brands"), tokenA, Map.of(
                "name", "  青岚茶事 ", "industry", "餐饮 / 茶饮", "slogan", "一杯好茶，见天地",
                "languageStyle", "温和、雅致", "primaryColor", "#2D5016", "website", "https://qinglan.example.com"));
        long teaId = tea.path("id").asLong();
        assertThat(tea.path("name").asText()).isEqualTo("青岚茶事");
        assertThat(tea.path("primaryColor").asText()).isEqualTo("#2d5016");
        assertThat(tea.path("defaultBrand").asBoolean()).isTrue();
        assertThat(tea.path("storeCount").asLong()).isEqualTo(1);
        assertThat(api(get("/api/stores/" + oldStore), tokenA, null).path("brandId").asLong()).isEqualTo(teaId);

        // 以后新建的门店不用选，直接归到默认品牌。
        JsonNode second = api(post("/api/stores"), tokenA, Map.of("name", "新店"));
        assertThat(second.path("brandId").asLong()).isEqualTo(teaId);

        JsonNode grill = api(post("/api/brands"), tokenA, Map.of("name", "王记烧烤"));
        long grillId = grill.path("id").asLong();
        assertThat(grill.path("defaultBrand").asBoolean()).isFalse();
        assertThat(grill.path("storeCount").asLong()).isZero();
        JsonNode grillStore = api(post("/api/stores"), tokenA, Map.of("name", "烧烤一店", "brandId", grillId));
        assertThat(grillStore.path("brandId").asLong()).isEqualTo(grillId);

        // 不带 brandId 的保存不动归属；带上才会换品牌。
        JsonNode renamed = api(put("/api/stores/" + oldStore), tokenA, Map.of("name", "老店（城西）", "version", 0));
        assertThat(renamed.path("brandId").asLong()).isEqualTo(teaId);
        JsonNode moved = api(put("/api/stores/" + oldStore), tokenA,
                Map.of("name", "老店（城西）", "version", renamed.path("version").asLong(), "brandId", grillId));
        assertThat(moved.path("brandId").asLong()).isEqualTo(grillId);

        JsonNode listed = api(get("/api/brands"), tokenA, null);
        assertThat(listed).extracting(brand -> brand.path("name").asText()).containsExactly("青岚茶事", "王记烧烤");
        assertThat(listed).extracting(brand -> brand.path("storeCount").asLong()).containsExactly(1L, 2L);
        assertThat(api(get("/api/brands/" + grillId), tokenA, null).path("storeCount").asLong()).isEqualTo(2);
    }

    @Test
    void namesAreUniqueAmongLiveBrandsAndAStaleOrMalformedEditIsRefused() throws Exception {
        long tea = api(post("/api/brands"), tokenA, Map.of("name", "青岚茶事")).path("id").asLong();
        JsonNode grill = api(post("/api/brands"), tokenA, Map.of("name", "王记烧烤"));
        long grillId = grill.path("id").asLong();

        refused(post("/api/brands"), tokenA, Map.of("name", " 青岚茶事"), 409, 1409);
        refused(put("/api/brands/" + grillId), tokenA, Map.of("name", "青岚茶事", "version", 0), 409, 1409);
        // 另一个商户用同样的名字不受影响。
        api(post("/api/brands"), tokenB, Map.of("name", "青岚茶事"));

        JsonNode edited = api(put("/api/brands/" + grillId), tokenA,
                Map.of("name", "王记烧烤", "slogan", "炭火现烤", "version", 0));
        assertThat(edited.path("slogan").asText()).isEqualTo("炭火现烤");
        assertThat(edited.path("version").asLong()).isEqualTo(1);
        refused(put("/api/brands/" + grillId), tokenA, Map.of("name", "王记烧烤", "slogan", "过期的修改", "version", 0), 409, 1409);
        assertThat(api(get("/api/brands/" + grillId), tokenA, null).path("slogan").asText()).isEqualTo("炭火现烤");
        // 整份资料按提交内容保存：没带上的字段就是清空。
        JsonNode cleared = api(put("/api/brands/" + grillId), tokenA, Map.of("name", "王记烧烤", "version", 1));
        assertThat(cleared.has("slogan")).isFalse();

        refused(post("/api/brands"), tokenA, Map.of("name", "  "), 400, 1400);
        refused(post("/api/brands"), tokenA, Map.of("name", "颜色不对", "primaryColor", "green"), 400, 1400);
        refused(post("/api/brands"), tokenA, Map.of("name", "网址不对", "website", "javascript:alert(1)"), 400, 1400);
        refused(put("/api/brands/" + tea), tokenA, Map.of("name", "没带版本"), 400, 1400);
        assertThat(api(get("/api/brands"), tokenA, null)).hasSize(2);
    }

    @Test
    void exactlyOneBrandIsTheDefaultAndItCanBeHandedOver() throws Exception {
        long tea = api(post("/api/brands"), tokenA, Map.of("name", "青岚茶事")).path("id").asLong();
        long grill = api(post("/api/brands"), tokenA, Map.of("name", "王记烧烤")).path("id").asLong();

        assertThat(api(post("/api/brands/" + grill + "/default"), tokenA, null).path("defaultBrand").asBoolean()).isTrue();
        assertThat(api(get("/api/brands/" + tea), tokenA, null).path("defaultBrand").asBoolean()).isFalse();
        // 重复设置是同一个结果。
        api(post("/api/brands/" + grill + "/default"), tokenA, null);
        assertThat(owner.queryForObject("SELECT count(*) FROM brands WHERE tenant_id = ? AND is_default", Long.class, tenantA)).isEqualTo(1);
        assertThat(api(get("/api/brands"), tokenA, null).get(0).path("id").asLong()).as("默认品牌排在最前").isEqualTo(grill);

        assertThat(api(post("/api/stores"), tokenA, Map.of("name", "新店")).path("brandId").asLong()).isEqualTo(grill);
    }

    @Test
    void aBrandWithStoresOrStillTheDefaultCannotBeRemoved() throws Exception {
        long tea = api(post("/api/brands"), tokenA, Map.of("name", "青岚茶事")).path("id").asLong();
        JsonNode store = api(post("/api/stores"), tokenA, Map.of("name", "城西店"));
        long grill = api(post("/api/brands"), tokenA, Map.of("name", "王记烧烤")).path("id").asLong();

        assertThat(refused(delete("/api/brands/" + tea), tokenA, null, 409, 1409)).contains("1 家门店", "其他品牌");

        // 没有门店、也不是默认的品牌可以直接删掉，名字随后可以再用。
        api(delete("/api/brands/" + grill), tokenA, null);
        refused(get("/api/brands/" + grill), tokenA, null, 404, 1404);
        grill = api(post("/api/brands"), tokenA, Map.of("name", "王记烧烤")).path("id").asLong();

        api(put("/api/stores/" + store.path("id").asLong()), tokenA, Map.of("name", "城西店", "version", 0, "brandId", grill));
        assertThat(refused(delete("/api/brands/" + tea), tokenA, null, 409, 1409)).contains("默认品牌");
        api(post("/api/brands/" + grill + "/default"), tokenA, null);
        api(delete("/api/brands/" + tea), tokenA, null);

        // 只剩一个品牌、还有门店在用：删不掉，改资料即可。
        assertThat(refused(delete("/api/brands/" + grill), tokenA, null, 409, 1409)).contains("唯一的品牌");
        refused(post("/api/stores"), tokenA, Map.of("name", "挂到已删除的品牌", "brandId", tea), 400, 1400);

        // 门店都停用后，最后一个品牌也能删；商户回到没有品牌的状态。
        api(delete("/api/stores/" + store.path("id").asLong()), tokenA, null);
        api(delete("/api/brands/" + grill), tokenA, null);
        assertThat(api(get("/api/brands"), tokenA, null)).isEmpty();
        assertThat(api(post("/api/stores"), tokenA, Map.of("name", "重新开店")).has("brandId")).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM brands WHERE tenant_id = ? AND status = 'ARCHIVED' AND NOT is_default",
                Long.class, tenantA)).isEqualTo(3);
    }

    @Test
    void brandsCanNeitherBeSeenNorUsedAcrossMerchants() throws Exception {
        long brandA = api(post("/api/brands"), tokenA, Map.of("name", "青岚茶事", "slogan", "一杯好茶")).path("id").asLong();
        long storeB = api(post("/api/stores"), tokenB, Map.of("name", "乙的门店")).path("id").asLong();

        assertThat(api(get("/api/brands"), tokenB, null)).isEmpty();
        refused(get("/api/brands/" + brandA), tokenB, null, 404, 1404);
        refused(put("/api/brands/" + brandA), tokenB, Map.of("name", "抢过来", "version", 0), 404, 1404);
        refused(post("/api/brands/" + brandA + "/default"), tokenB, null, 404, 1404);
        refused(delete("/api/brands/" + brandA), tokenB, null, 404, 1404);
        refused(post("/api/stores"), tokenB, Map.of("name", "挂别家的品牌", "brandId", brandA), 400, 1400);
        refused(put("/api/stores/" + storeB), tokenB, Map.of("name", "乙的门店", "version", 0, "brandId", brandA), 400, 1400);
        assertThat(api(get("/api/brands/" + brandA), tokenA, null).path("name").asText()).isEqualTo("青岚茶事");
        refused(get("/api/brands"), null, null, 401, 1401);

        assertThat(owner.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class "
                + "WHERE oid = 'public.brands'::regclass", Boolean.class)).isTrue();
        // 行级安全管不到外键检查；即使绕过应用直接改库，门店也挂不到别家的品牌上。
        assertThatThrownBy(() -> owner.update("UPDATE stores SET brand_id = ? WHERE id = ?", brandA, storeB))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_stores_brand");

        // 其他模块按门店取品牌资料，同样只在自己的商户里看得到。
        long storeA = api(post("/api/stores"), tokenA, Map.of("name", "甲的门店")).path("id").asLong();
        assertThat(TenantContext.runAs(tenantA, () -> brands.profileForStore(storeA)))
                .hasValueSatisfying(profile -> assertThat(profile.slogan()).isEqualTo("一杯好茶"));
        assertThat(TenantContext.runAs(tenantB, () -> brands.profileForStore(storeA))).isEmpty();
        assertThat(TenantContext.runAs(tenantB, () -> brands.profileForStore(storeB))).as("没有品牌的门店").isEmpty();
    }

    @Test
    void visualAssetsMustBeTheMerchantsOwnFinishedImages() throws Exception {
        long logo = asset(tenantA, "IMAGE", "READY", "a/logo.png");
        long otherLogo = asset(tenantA, "IMAGE", "READY", "a/logo-2.png");
        long pending = asset(tenantA, "IMAGE", "PENDING", "a/pending.png");
        long video = asset(tenantA, "VIDEO", "READY", "a/clip.mp4");
        long foreign = asset(tenantB, "IMAGE", "READY", "b/logo.png");

        JsonNode brand = api(post("/api/brands"), tokenA, Map.of("name", "青岚茶事", "logoAssetId", logo, "wechatQrAssetId", otherLogo));
        long id = brand.path("id").asLong();
        assertThat(brand.path("logoAssetId").asLong()).isEqualTo(logo);
        assertThat(brand.path("wechatQrAssetId").asLong()).isEqualTo(otherLogo);
        assertThat(brand.has("miniProgramQrAssetId")).isFalse();

        refused(post("/api/brands"), tokenA, Map.of("name", "没传完", "logoAssetId", pending), 400, 1400);
        refused(post("/api/brands"), tokenA, Map.of("name", "不是图片", "logoAssetId", video), 400, 1400);
        refused(post("/api/brands"), tokenA, Map.of("name", "别家的图", "logoAssetId", foreign), 404, 3001);
        refused(put("/api/brands/" + id), tokenA, Map.of("name", "青岚茶事", "version", 0, "miniProgramQrAssetId", foreign), 404, 3001);

        JsonNode swapped = api(put("/api/brands/" + id), tokenA, Map.of("name", "青岚茶事", "version", 0, "logoAssetId", otherLogo));
        assertThat(swapped.path("logoAssetId").asLong()).isEqualTo(otherLogo);
        assertThat(swapped.has("wechatQrAssetId")).as("没带上的图片就是移除").isFalse();
    }

    @Test
    void twoFirstBrandsCreatedAtTheSameMomentLeaveOneDefaultThatOwnsTheStores() throws Exception {
        api(post("/api/stores"), tokenA, Map.of("name", "一店"));
        api(post("/api/stores"), tokenA, Map.of("name", "二店"));
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<BrandDtos.View>> created = List.of("青岚茶事", "王记烧烤").stream().map(name -> pool.submit(() -> {
                together.await(10, TimeUnit.SECONDS);
                return TenantContext.runAs(tenantA, () -> brands.create(request(name), userA));
            })).toList();
            for (Future<BrandDtos.View> future : created) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        JsonNode listed = api(get("/api/brands"), tokenA, null);
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).path("defaultBrand").asBoolean()).isTrue();
        assertThat(listed.get(0).path("storeCount").asLong()).isEqualTo(2);
        assertThat(listed.get(1).path("defaultBrand").asBoolean()).isFalse();
        assertThat(listed.get(1).path("storeCount").asLong()).isZero();
    }

    private static BrandDtos.CreateRequest request(String name) {
        return new BrandDtos.CreateRequest(name, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private long asset(Long tenant, String type, String status, String key) {
        return owner.queryForObject("INSERT INTO assets(tenant_id,name,type,status,storage_key,size_bytes) "
                + "VALUES (?, '素材', ?, ?, ?, 2048) RETURNING id", Long.class, tenant, type, status, key);
    }

    private JsonNode api(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        var response = mvc.perform(prepared(request, token, body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("data");
    }

    /** @return the message the caller is shown */
    private String refused(MockHttpServletRequestBuilder request, String token, Object body, int http, int code) throws Exception {
        var response = mvc.perform(prepared(request, token, body)).andExpect(status().is(http))
                .andExpect(jsonPath("$.code").value(code)).andReturn().getResponse();
        return json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("message").asText();
    }

    private MockHttpServletRequestBuilder prepared(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(new LinkedHashMap<>((Map<?, ?>) body)));
        return request;
    }
}
