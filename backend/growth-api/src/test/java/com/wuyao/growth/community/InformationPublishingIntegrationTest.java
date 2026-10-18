package com.wuyao.growth.community;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.tenant.TenantContext;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN"})
@AutoConfigureMockMvc
@Testcontainers
class InformationPublishingIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("information_test").withUsername("growth_owner").withPassword("test_owner_password");

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
        registry.add("growth.platform.operator-user-ids", () -> "1");
    }

    @Autowired CommunityService service;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    JdbcTemplate owner;
    Long tenantId;
    Long otherTenantId;
    Long userId;
    Long otherUserId;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        tenantId = owner.queryForObject("INSERT INTO tenants(name) VALUES ('发布商家') RETURNING id", Long.class);
        otherTenantId = owner.queryForObject("INSERT INTO tenants(name) VALUES ('其他商家') RETURNING id", Long.class);
        userId = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000001', '发布管理员') RETURNING id", Long.class, tenantId);
        otherUserId = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000002', '其他管理员') RETURNING id", Long.class, otherTenantId);
    }

    private CommunityDtos.InformationRequest content(String title, String category) {
        return new CommunityDtos.InformationRequest(category, title, "发布测试商家", "线上服务", "真实保存的摘要",
                "详细内容\n\nhttps://yifangzhi.com/merchant-alliance", null, null, null, true);
    }

    private void review(Long tenant, Long id, String status) throws Exception {
        var updatedAt = owner.queryForObject("SELECT updated_at FROM information_publications WHERE id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), id);
        mvc.perform(post("/api/platform/tenants/{tenantId}/information/{id}/status", tenant, id)
                        .header("Authorization", "Bearer " + jwt.issueAccessToken(userId, tenantId, "13800000001"))
                        .contentType("application/json").content(json.writeValueAsBytes(Map.of("status", status,
                                "note", "测试审核，已核对提交内容", "updatedAt", updatedAt.toString()))))
                .andExpect(status().isOk());
    }

    @Test
    void publishedContentIsReadableWithoutLoginAndFiltersExcludeExpiredAndOfflineRecords() throws Exception {
        String token = jwt.issueAccessToken(userId, tenantId, "13800000001");
        String response = mvc.perform(post("/api/information-publications").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(json.writeValueAsBytes(content("直播配件说明", "supply"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.publishedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(response).path("data").path("id").asLong();
        mvc.perform(get("/api/public/information/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/information")).andExpect(jsonPath("$.data.total").value(0));
        var other = TenantContext.runAs(otherTenantId, () -> service.publish(content("其他商家公告", "notice"), otherUserId));
        var expired = TenantContext.runAs(tenantId, () -> service.publish(content("过期内容", "supply"), userId));
        var offline = TenantContext.runAs(tenantId, () -> service.publish(content("下架内容", "supply"), userId));
        var pending = TenantContext.runAs(tenantId, () -> service.publish(content("待审核内容", "supply"), userId));
        var rejected = TenantContext.runAs(tenantId, () -> service.publish(content("驳回内容", "supply"), userId));
        review(tenantId, id, "PUBLISHED");
        review(otherTenantId, other.id(), "PUBLISHED");
        review(tenantId, expired.id(), "PUBLISHED");
        review(tenantId, offline.id(), "PUBLISHED");
        review(tenantId, rejected.id(), "REJECTED");
        owner.update("UPDATE information_publications SET valid_until=CURRENT_DATE - 1 WHERE id=?", expired.id());
        TenantContext.runAs(tenantId, () -> { service.takeOffline(offline.id()); return null; });

        mvc.perform(get("/api/public/information")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/public/information").param("category", "supply").param("keyword", "直播"))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].id").value(id));
        mvc.perform(get("/api/public/information/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("详细内容\n\nhttps://yifangzhi.com/merchant-alliance"))
                .andExpect(jsonPath("$.data.publishedAt").isNotEmpty());
        mvc.perform(get("/api/public/information/{id}", other.id())).andExpect(status().isOk());
        mvc.perform(get("/api/public/information/{id}", expired.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/information/{id}", offline.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/information/{id}", pending.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/information/{id}", rejected.id())).andExpect(status().isNotFound());
    }

    @Test
    void publicVisibilityDoesNotGrantAnotherMerchantManagementRights() throws Exception {
        var mine = TenantContext.runAs(tenantId, () -> service.publish(content("我的公告", "notice"), userId));
        var other = TenantContext.runAs(otherTenantId, () -> service.publish(content("另一家公告", "notice"), otherUserId));
        review(tenantId, mine.id(), "PUBLISHED");
        review(otherTenantId, other.id(), "PUBLISHED");
        String token = jwt.issueAccessToken(userId, tenantId, "13800000001");
        mvc.perform(get("/api/information-publications").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(mine.id()));
        mvc.perform(put("/api/information-publications/{id}", other.id()).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(json.writeValueAsBytes(content("试图覆盖", "notice"))))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/information-publications/{id}", other.id()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        assertThat(owner.queryForObject("SELECT title FROM information_publications WHERE id=?", String.class, other.id())).isEqualTo("另一家公告");
        mvc.perform(put("/api/information-publications/{id}", mine.id()).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(json.writeValueAsBytes(content("已更新公告", "notice"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        mvc.perform(get("/api/public/information/{id}", mine.id())).andExpect(status().isNotFound());
        review(tenantId, mine.id(), "PUBLISHED");
        mvc.perform(get("/api/public/information/{id}", mine.id())).andExpect(jsonPath("$.data.title").value("已更新公告"));
        mvc.perform(delete("/api/information-publications/{id}", mine.id()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/information/{id}", mine.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/information-publications").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].status").value("OFFLINE"));
    }

    @Test
    void publishersSeeRejectionReasonsAndCanResubmitWhileOldReviewsCannotApproveChangedContent() throws Exception {
        var mine = TenantContext.runAs(tenantId, () -> service.publish(content("需补充的公告", "notice"), userId));
        String token = jwt.issueAccessToken(userId, tenantId, "13800000001");
        review(tenantId, mine.id(), "REJECTED");
        mvc.perform(get("/api/information-publications").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].reviewNote").value("测试审核，已核对提交内容"));
        mvc.perform(put("/api/information-publications/{id}", mine.id()).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(json.writeValueAsBytes(content("补充后的公告", "notice"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.reviewNote").doesNotExist());
        String oldVersion = owner.queryForObject("SELECT updated_at FROM information_publications WHERE id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant().toString(), mine.id());
        mvc.perform(put("/api/information-publications/{id}", mine.id()).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(json.writeValueAsBytes(content("再次修改的公告", "notice"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/platform/tenants/{tenantId}/information/{id}/status", tenantId, mine.id())
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(json.writeValueAsBytes(Map.of("status", "PUBLISHED", "note", "旧内容的审核结果", "updatedAt", oldVersion))))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/public/information/{id}", mine.id())).andExpect(status().isNotFound());
        review(tenantId, mine.id(), "PUBLISHED");
        mvc.perform(get("/api/public/information/{id}", mine.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("再次修改的公告"))
                .andExpect(jsonPath("$.data.reviewNote").doesNotExist());
    }
}
