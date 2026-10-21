package com.wuyao.growth.commerce;

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
}
