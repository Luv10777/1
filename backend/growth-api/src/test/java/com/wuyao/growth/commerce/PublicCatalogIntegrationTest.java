package com.wuyao.growth.commerce;

import com.wuyao.growth.community.MerchantTestFixtures;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN"})
@AutoConfigureMockMvc
@Testcontainers
class PublicCatalogIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog_test").withUsername("growth_owner").withPassword("test_owner_password");

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
    JdbcTemplate owner;
    Long tenantA;
    Long tenantB;
    Long userA;
    Long userB;
    Long storeA;
    Long storeB;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        tenantA = owner.queryForObject("INSERT INTO tenants(name) VALUES ('甲商户') RETURNING id", Long.class);
        tenantB = owner.queryForObject("INSERT INTO tenants(name) VALUES ('乙商户') RETURNING id", Long.class);
        userA = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000001', '甲') RETURNING id", Long.class, tenantA);
        userB = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000002', '乙') RETURNING id", Long.class, tenantB);
        storeA = owner.queryForObject("INSERT INTO stores(tenant_id,name,created_by) VALUES (?, '甲茶铺', ?) RETURNING id", Long.class, tenantA, userA);
        storeB = owner.queryForObject("INSERT INTO stores(tenant_id,name,created_by) VALUES (?, '乙家居', ?) RETURNING id", Long.class, tenantB, userB);
    }

    private Long product(Long tenant, Long store, Long user, String name, String type, String onlineStatus, String category) {
        return owner.queryForObject("""
                INSERT INTO products(tenant_id,store_id,name,type,category,price,sale_unit,online_status,created_by)
                VALUES (?,?,?,?,?,19.90,'件',?,?) RETURNING id""",
                Long.class, tenant, store, name, type, category, onlineStatus, user);
    }

    @Test
    void anonymousVisitorsSeeOnlyPublishedPhysicalProductsFromAllMerchantsWithStoreNames() throws Exception {
        product(tenantA, storeA, userA, "龙井茶礼盒", "PHYSICAL", "PUBLISHED", "茶饮");
        product(tenantB, storeB, userB, "实木托盘", "PHYSICAL", "PUBLISHED", "家居");
        product(tenantA, storeA, userA, "草稿商品", "PHYSICAL", "DRAFT", "茶饮");
        product(tenantA, storeA, userA, "团购券", "VOUCHER", "DRAFT", "茶饮");
        Long deleted = product(tenantB, storeB, userB, "已删除商品", "PHYSICAL", "PUBLISHED", "家居");
        owner.update("UPDATE products SET deleted_at = now() WHERE id = ?", deleted);

        mvc.perform(get("/api/public/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[?(@.name=='龙井茶礼盒')].storeName").value("甲茶铺"))
                .andExpect(jsonPath("$.data.items[?(@.name=='实木托盘')].storeName").value("乙家居"));
    }

    @Test
    void keywordMatchesNameOrCategoryAndPagingIsBounded() throws Exception {
        product(tenantA, storeA, userA, "龙井茶礼盒", "PHYSICAL", "PUBLISHED", "茶饮");
        product(tenantB, storeB, userB, "实木托盘", "PHYSICAL", "PUBLISHED", "家居");

        mvc.perform(get("/api/public/products").param("keyword", "龙井"))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].name").value("龙井茶礼盒"));
        mvc.perform(get("/api/public/products").param("keyword", "家居"))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].name").value("实木托盘"));
        mvc.perform(get("/api/public/products").param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/public/products").param("size", "51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/products").param("page", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    void productsOfArchivedStoresAreNotListed() throws Exception {
        product(tenantA, storeA, userA, "龙井茶礼盒", "PHYSICAL", "PUBLISHED", "茶饮");
        product(tenantB, storeB, userB, "实木托盘", "PHYSICAL", "PUBLISHED", "家居");
        owner.update("UPDATE stores SET status = 'ARCHIVED' WHERE id = ?", storeB);

        mvc.perform(get("/api/public/products"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("龙井茶礼盒"));
    }

    @Test
    void coverIsTheFirstImageByOrder() throws Exception {
        Long id = product(tenantA, storeA, userA, "龙井茶礼盒", "PHYSICAL", "PUBLISHED", "茶饮");
        Long second = owner.queryForObject("INSERT INTO assets(tenant_id,name,type,status,storage_key) VALUES (?, '二', 'IMAGE', 'READY', 'test/2.png') RETURNING id", Long.class, tenantA);
        Long first = owner.queryForObject("INSERT INTO assets(tenant_id,name,type,status,storage_key) VALUES (?, '一', 'IMAGE', 'READY', 'test/1.png') RETURNING id", Long.class, tenantA);
        owner.update("INSERT INTO product_images(tenant_id,product_id,asset_id,sort_order) VALUES (?,?,?,1)", tenantA, id, second);
        owner.update("INSERT INTO product_images(tenant_id,product_id,asset_id,sort_order) VALUES (?,?,?,0)", tenantA, id, first);

        mvc.perform(get("/api/public/products")).andExpect(jsonPath("$.data.items[0].coverAssetId").value(first));
    }

    @Test
    void allianceStoresListOnlyApprovedMerchantsActiveStoresIncludingOnesWithoutProducts() throws Exception {
        MerchantTestFixtures.approveMerchant(owner, tenantA, userA);
        Long emptyStore = owner.queryForObject("INSERT INTO stores(tenant_id,name,created_by) VALUES (?, '甲茶铺二店', ?) RETURNING id", Long.class, tenantA, userA);
        Long archived = owner.queryForObject("INSERT INTO stores(tenant_id,name,created_by,status) VALUES (?, '甲茶铺旧店', ?, 'ARCHIVED') RETURNING id", Long.class, tenantA, userA);
        product(tenantA, storeA, userA, "龙井茶礼盒", "PHYSICAL", "PUBLISHED", "茶饮");
        product(tenantA, storeA, userA, "草稿商品", "PHYSICAL", "DRAFT", "茶饮");
        product(tenantA, archived, userA, "旧店商品", "PHYSICAL", "PUBLISHED", "茶饮");
        // 乙只提交了申请、还没通过：即使有在售商品，也不算入驻商户。
        owner.update("""
                INSERT INTO merchant_applications(tenant_id,submitted_by,merchant_name,company_name,industry,region,
                    credit_code,contact_name,contact_phone,introduction)
                VALUES (?,?,'乙商家','乙商家有限公司','家居','测试地区','91330400MA2T000002','联系人','13800009998','测试')""", tenantB, userB);
        product(tenantB, storeB, userB, "实木托盘", "PHYSICAL", "PUBLISHED", "家居");

        mvc.perform(get("/api/public/alliance-stores")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].storeId").value(storeA))
                .andExpect(jsonPath("$.data.items[0].storeName").value("甲茶铺"))
                .andExpect(jsonPath("$.data.items[0].productTotal").value(1))
                .andExpect(jsonPath("$.data.items[0].products[0].name").value("龙井茶礼盒"))
                .andExpect(jsonPath("$.data.items[0].products[0].storeName").value("甲茶铺"))
                .andExpect(jsonPath("$.data.items[1].storeId").value(emptyStore))
                .andExpect(jsonPath("$.data.items[1].productTotal").value(0))
                .andExpect(jsonPath("$.data.items[1].products.length()").value(0))
                .andExpect(jsonPath("$.data.items[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].merchantName").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].contactPhone").doesNotExist());
    }

    @Test
    void allianceStoresShowTheLatestSixProductsAndDropSuspendedMerchants() throws Exception {
        MerchantTestFixtures.approveMerchant(owner, tenantA, userA);
        MerchantTestFixtures.approveMerchant(owner, tenantB, userB);
        for (int i = 1; i <= 8; i++) {
            Long id = product(tenantA, storeA, userA, "茶" + i, "PHYSICAL", "PUBLISHED", "茶饮");
            owner.update("UPDATE products SET updated_at = now() - make_interval(mins => ?) WHERE id = ?", 10 - i, id);
        }
        product(tenantA, storeA, userA, "团购券", "VOUCHER", "PUBLISHED", "茶饮");

        mvc.perform(get("/api/public/alliance-stores"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].productTotal").value(8))
                .andExpect(jsonPath("$.data.items[0].products.length()").value(6))
                .andExpect(jsonPath("$.data.items[0].products[0].name").value("茶8"))
                .andExpect(jsonPath("$.data.items[0].products[5].name").value("茶3"));

        owner.update("UPDATE merchant_applications SET status = 'SUSPENDED' WHERE tenant_id = ?", tenantA);
        mvc.perform(get("/api/public/alliance-stores"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].storeName").value("乙家居"));

        mvc.perform(get("/api/public/alliance-stores").param("size", "51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/alliance-stores").param("page", "-1")).andExpect(status().isBadRequest());
    }
}
