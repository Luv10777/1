package com.wuyao.growth.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN"})
@AutoConfigureMockMvc
@Testcontainers
class ProductPersistenceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("product_test").withUsername("growth_owner").withPassword("test_owner_password");

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

    @Autowired ProductService products;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    JdbcTemplate owner;
    Long tenantId;
    Long storeId;
    Long userId;
    Long assetId;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        tenantId = owner.queryForObject("INSERT INTO tenants(name) VALUES ('商品测试租户') RETURNING id", Long.class);
        userId = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000001', '商品管理员') RETURNING id",
                Long.class, tenantId);
        storeId = owner.queryForObject("INSERT INTO stores(tenant_id,name,created_by) VALUES (?, '测试门店', ?) RETURNING id",
                Long.class, tenantId, userId);
        assetId = owner.queryForObject("INSERT INTO assets(tenant_id,name,type,status,storage_key) VALUES (?, '图片', 'IMAGE', 'READY', 'test/image.png') RETURNING id",
                Long.class, tenantId);
    }

    @Test
    void emptyStoreProductListWithoutAnyFiltersDoesNotBindKeywordAsBinary() throws Exception {
        String token = jwt.issueAccessToken(userId, tenantId, "13800000001");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/stores/{storeId}/products", storeId)
                        .param("page", "0").param("size", "100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void optionalFiltersWorkIndependentlyAndTogetherOnPostgres() throws Exception {
        var sesame = createForSearch("芝麻丸", "PHYSICAL", "食品生鲜", "69.00");
        var coffee = createForSearch("Coffee 咖啡券", "VOUCHER", "餐饮美食", "19.90");
        var dinner = createForSearch("双人餐券", "VOUCHER", "餐饮美食", "169.00");
        var deleted = createForSearch("已删除商品", "PHYSICAL", "食品生鲜", "1.00");
        TenantContext.runAs(tenantId, () -> { products.delete(deleted.id(), userId); return null; });
        String token = jwt.issueAccessToken(userId, tenantId, "13800000001");

        for (String keyword : new String[] { null, "", "   " }) {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                            "/api/stores/{storeId}/products", storeId)
                    .param("page", "0").param("size", "2")
                    .header("Authorization", "Bearer " + token);
            if (keyword != null) request.param("keyword", keyword);
            mvc.perform(request)
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.total").value(3))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.items[0].id").value(dinner.id()))
                    .andExpect(jsonPath("$.data.items[1].id").value(coffee.id()));
        }

        assertThat(searchIds(null, "physical", null)).containsExactly(sesame.id());
        assertThat(searchIds(null, null, "餐饮美食")).containsExactly(dinner.id(), coffee.id());
        assertThat(searchIds(null, "voucher", "餐饮美食")).containsExactly(dinner.id(), coffee.id());
        assertThat(searchIds("", "physical", "食品生鲜")).containsExactly(sesame.id());
        assertThat(searchIds(null, "physical", "餐饮美食")).isEmpty();
        assertThat(searchIds("芝麻", null, null)).containsExactly(sesame.id());
        assertThat(searchIds("食品", null, null)).containsExactly(sesame.id());
        assertThat(searchIds("coffee", "voucher", "餐饮美食")).containsExactly(coffee.id());
        assertThat(searchIds("19.90", null, null)).containsExactly(coffee.id());
        assertThat(searchIds("不存在", null, null)).isEmpty();
    }

    @Test
    void savingExistingImagesAgainDoesNotConflictAndReturnsTheCommittedVersion() {
        ProductDtos.View created = create(List.of(new ProductDtos.ImageRequest(assetId, 0)));
        ProductDtos.View updated = update(created, List.of(new ProductDtos.ImageRequest(assetId, 1)));
        assertThat(updated.images()).hasSize(1);
        assertThat(updated.images().getFirst().assetId()).isEqualTo(assetId);
        assertThat(updated.images().getFirst().sortOrder()).isEqualTo(1);
        assertThat(updated.version()).isGreaterThan(created.version());
        assertThat(get(updated.id()).version()).isEqualTo(updated.version());

        ProductDtos.View savedAgain = update(updated, List.of(new ProductDtos.ImageRequest(assetId, 0)));
        assertThat(savedAgain.version()).isGreaterThan(updated.version());
        assertThat(owner.queryForObject("SELECT count(*) FROM product_images WHERE product_id=?",
                Integer.class, created.id())).isEqualTo(1);
    }

    @Test
    void updateWithoutChildrenReturnsAVersionThatCanBeUsedByTheNextSave() {
        ProductDtos.View created = create(null);
        ProductDtos.View updated = update(created, null);
        assertThat(updated.version()).isGreaterThan(created.version());
        assertThat(get(updated.id()).version()).isEqualTo(updated.version());
        assertThat(update(updated, null).version()).isGreaterThan(updated.version());
        assertThatThrownBy(() -> update(created, null))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void faqUpdateReturnsTheCommittedVersionForSubsequentEdits() {
        ProductDtos.View product = create(null);
        var faq = TenantContext.runAs(tenantId, () -> products.createFaq(product.id(),
                new ProductDtos.FaqRequest("几天发货？", "两天内", 0), userId));
        var updated = TenantContext.runAs(tenantId, () -> products.updateFaq(product.id(), faq.id(),
                new ProductDtos.FaqUpdateRequest("几天发货？", "一天内", 0, faq.version()), userId));
        assertThat(updated.version()).isGreaterThan(faq.version());
        assertThat(TenantContext.runAs(tenantId, () -> products.listFaqs(product.id(), userId))
                .getFirst().version()).isEqualTo(updated.version());
        var savedAgain = TenantContext.runAs(tenantId, () -> products.updateFaq(product.id(), faq.id(),
                new ProductDtos.FaqUpdateRequest("几天发货？", "当天", 0, updated.version()), userId));
        assertThat(savedAgain.version()).isGreaterThan(updated.version());
    }

    @Test
    void faqChangesInvalidateOpenProductEditorsWithoutLosingLiveSavedAnswers() {
        ProductDtos.View beforeCreate = create(null);
        var faq = TenantContext.runAs(tenantId, () -> products.createFaq(beforeCreate.id(),
                new ProductDtos.FaqRequest("几天发货？", "两天内", 0), userId));
        ProductDtos.View beforeEdit = get(beforeCreate.id());
        assertThat(beforeEdit.version()).isGreaterThan(beforeCreate.version());
        assertStaleFullSaveRejected(beforeCreate);
        assertThat(get(beforeCreate.id()).faqs()).singleElement()
                .satisfies(saved -> assertThat(saved.answer()).isEqualTo("两天内"));

        TenantContext.runAs(tenantId, () -> products.updateFaq(beforeCreate.id(), faq.id(),
                new ProductDtos.FaqUpdateRequest("几天发货？", "当天", 0, faq.version()), userId));
        ProductDtos.View beforeDelete = get(beforeCreate.id());
        assertThat(beforeDelete.version()).isGreaterThan(beforeEdit.version());
        assertStaleFullSaveRejected(beforeEdit);
        assertThat(get(beforeCreate.id()).faqs()).singleElement()
                .satisfies(saved -> assertThat(saved.answer()).isEqualTo("当天"));

        TenantContext.runAs(tenantId, () -> {
            products.deleteFaq(beforeCreate.id(), faq.id(), userId);
            return null;
        });
        ProductDtos.View afterDelete = get(beforeCreate.id());
        assertThat(afterDelete.version()).isGreaterThan(beforeDelete.version());
        assertStaleFullSaveRejected(beforeDelete);
        assertThat(get(beforeCreate.id()).faqs()).isEmpty();
        assertThat(fullUpdate(afterDelete).version()).isGreaterThan(afterDelete.version());
    }

    @Test
    void imageChangesInvalidateOpenEditorsWithoutRemovingReorderingOrRestoringImages() {
        ProductDtos.View beforeAdd = create(null);
        var image = TenantContext.runAs(tenantId, () -> products.addImage(beforeAdd.id(),
                new ProductDtos.ImageRequest(assetId, 0), userId));
        ProductDtos.View beforeReorder = get(beforeAdd.id());
        assertThat(beforeReorder.version()).isGreaterThan(beforeAdd.version());
        assertStaleFullSaveRejected(beforeAdd);
        assertThat(get(beforeAdd.id()).images()).singleElement()
                .satisfies(saved -> assertThat(saved.assetId()).isEqualTo(assetId));

        TenantContext.runAs(tenantId, () -> {
            products.reorderImages(beforeAdd.id(), List.of(new ProductDtos.ImageOrderRequest(image.id(), 3)), userId);
            return null;
        });
        ProductDtos.View beforeDelete = get(beforeAdd.id());
        assertThat(beforeDelete.version()).isGreaterThan(beforeReorder.version());
        assertStaleFullSaveRejected(beforeReorder);
        assertThat(get(beforeAdd.id()).images()).singleElement()
                .satisfies(saved -> assertThat(saved.sortOrder()).isEqualTo(3));

        TenantContext.runAs(tenantId, () -> {
            products.deleteImage(beforeAdd.id(), image.id(), userId);
            return null;
        });
        ProductDtos.View afterDelete = get(beforeAdd.id());
        assertThat(afterDelete.version()).isGreaterThan(beforeDelete.version());
        assertStaleFullSaveRejected(beforeDelete);
        assertThat(get(beforeAdd.id()).images()).isEmpty();
    }

    @Test
    void fullProductSaveCannotBypassConflictProtectionByOmittingVersion() throws Exception {
        ProductDtos.View product = create(null);
        var request = new ProductDtos.UpdateRequest(product.name(), product.type(), product.category(),
                product.price(), product.saleUnit(), null, null, null, null, List.of(), List.of());
        assertThatThrownBy(() -> TenantContext.runAs(tenantId, () -> products.update(product.id(), request, userId)))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        String token = jwt.issueAccessToken(userId, tenantId, "13800000001");
        mvc.perform(patch("/api/products/{id}", product.id())
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(json.writeValueAsString(request)))
                .andExpect(jsonPath("$.code").value(1400));
        assertThat(get(product.id()).version()).isEqualTo(product.version());
    }

    @Test
    void duplicateImageRequestsLeaveExistingImagesAndProductUnchanged() {
        ProductDtos.View product = create(List.of(new ProductDtos.ImageRequest(assetId, 0)));
        assertThatThrownBy(() -> update(product, List.of(
                new ProductDtos.ImageRequest(assetId, 0), new ProductDtos.ImageRequest(assetId, 1))))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        ProductDtos.View stored = get(product.id());
        assertThat(stored.images()).isEqualTo(product.images());
        assertThat(stored.version()).isEqualTo(product.version());
        assertThatThrownBy(() -> TenantContext.runAs(tenantId,
                () -> products.addImage(product.id(), new ProductDtos.ImageRequest(assetId, 1), userId)))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(get(product.id()).version()).isEqualTo(product.version());
    }

    @Test
    void invalidPricePrecisionIsRejectedBeforeTheDatabaseCanRoundOrOverflow() throws Exception {
        String token = jwt.issueAccessToken(userId, tenantId, "13800000001");
        ProductDtos.View product = create(null);
        for (String price : List.of("1.999", "1000000000000.00", "-0.01")) {
            var create = new ProductDtos.CreateRequest("商品", "PHYSICAL", "食品", new BigDecimal(price),
                    "罐", null, null, null, null, null);
            mvc.perform(post("/api/stores/{storeId}/products", storeId)
                            .header("Authorization", "Bearer " + token).contentType("application/json")
                            .content(json.writeValueAsString(create)))
                    .andExpect(jsonPath("$.code").value(1400));
            var update = new ProductDtos.UpdateRequest("商品", "PHYSICAL", "食品", new BigDecimal(price),
                    "罐", null, null, null, product.version(), null, null);
            mvc.perform(patch("/api/products/{id}", product.id())
                            .header("Authorization", "Bearer " + token).contentType("application/json")
                            .content(json.writeValueAsString(update)))
                    .andExpect(jsonPath("$.code").value(1400));
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM products", Integer.class)).isEqualTo(1);
        assertThat(get(product.id()).price()).isEqualByComparingTo("69.00");
        var largest = new ProductDtos.CreateRequest("边界金额", "PHYSICAL", "食品",
                new BigDecimal("999999999999.99"), "罐", null, null, null, null, null);
        mvc.perform(post("/api/stores/{storeId}/products", storeId)
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(json.writeValueAsString(largest)))
                .andExpect(jsonPath("$.code").value(200));
        assertThat(owner.queryForObject("SELECT price FROM products WHERE name='边界金额'", BigDecimal.class))
                .isEqualByComparingTo("999999999999.99");
    }

    private ProductDtos.View create(List<ProductDtos.ImageRequest> images) {
        return TenantContext.runAs(tenantId, () -> products.create(storeId,
                new ProductDtos.CreateRequest("商品", "PHYSICAL", "食品", new BigDecimal("69.00"),
                        "罐", null, null, null, images, null), userId));
    }

    private ProductDtos.View createForSearch(String name, String type, String category, String price) {
        return TenantContext.runAs(tenantId, () -> products.create(storeId,
                new ProductDtos.CreateRequest(name, type, category, new BigDecimal(price),
                        "份", null, null, null, null, null), userId));
    }

    private List<Long> searchIds(String keyword, String type, String category) {
        return TenantContext.runAs(tenantId, () -> products.list(storeId, keyword, type, category, 0, 100, userId))
                .items().stream().map(ProductDtos.View::id).toList();
    }

    private ProductDtos.View update(ProductDtos.View product, List<ProductDtos.ImageRequest> images) {
        return TenantContext.runAs(tenantId, () -> products.update(product.id(),
                new ProductDtos.UpdateRequest(product.name(), product.type(), product.category(), product.price(),
                        product.saleUnit(), null, null, null, product.version(), images, null), userId));
    }

    private ProductDtos.View get(Long id) {
        return TenantContext.runAs(tenantId, () -> products.get(id, userId));
    }

    private ProductDtos.View fullUpdate(ProductDtos.View snapshot) {
        return TenantContext.runAs(tenantId, () -> products.update(snapshot.id(),
                new ProductDtos.UpdateRequest(snapshot.name(), snapshot.type(), snapshot.category(), snapshot.price(),
                        snapshot.saleUnit(), snapshot.specification(), snapshot.promotionRule(), snapshot.coreSellingPoints(),
                        snapshot.version(), snapshot.images().stream()
                        .map(image -> new ProductDtos.ImageRequest(image.assetId(), image.sortOrder())).toList(),
                        snapshot.faqs().stream()
                        .map(faq -> new ProductDtos.FaqRequest(faq.question(), faq.answer(), faq.sortOrder())).toList()), userId));
    }

    private void assertStaleFullSaveRejected(ProductDtos.View snapshot) {
        assertThatThrownBy(() -> fullUpdate(snapshot))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
