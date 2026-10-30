package com.wuyao.growth.community;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN", "logging.level.com.wuyao.growth=WARN"})
@AutoConfigureMockMvc
@Testcontainers
class PlatformManagementIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform_management_test").withUsername("growth_owner").withPassword("test_owner_password");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "growth_app");
        r.add("spring.datasource.password", () -> "growth_dev_local");
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("spring.flyway.placeholders.app_db_password", () -> "growth_dev_local");
        r.add("growth.jwt.secret", () -> "test-only-random-signing-secret-0123456789abcdef");
        r.add("growth.identity.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("growth.platform.operator-user-ids", () -> "1");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @MockitoBean IdentityVerifier verifier;
    JdbcTemplate owner;
    Long operatorTenant, merchantTenant, operator, merchantUser, staffUser;
    String operatorToken, merchantToken, staffToken;

    @BeforeEach void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        operatorTenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('平台测试') RETURNING id", Long.class);
        merchantTenant = owner.queryForObject("INSERT INTO tenants(name) VALUES ('商户测试') RETURNING id", Long.class);
        operator = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000001', '平台人员') RETURNING id", Long.class, operatorTenant);
        merchantUser = owner.queryForObject("INSERT INTO users(tenant_id,phone,name) VALUES (?, '13800000002', '商家管理员') RETURNING id", Long.class, merchantTenant);
        staffUser = owner.queryForObject("INSERT INTO users(tenant_id,phone,name,role) VALUES (?, '13800000003', '店员', 'STAFF') RETURNING id", Long.class, merchantTenant);
        operatorToken = jwt.issueAccessToken(operator, operatorTenant, "13800000001");
        merchantToken = jwt.issueAccessToken(merchantUser, merchantTenant, "13800000002");
        staffToken = jwt.issueAccessToken(staffUser, merchantTenant, "13800000003");
        verdict(IdentityVerifier.Outcome.MATCH);
    }

    private void verdict(IdentityVerifier.Outcome outcome) {
        when(verifier.verify(anyString(), anyString())).thenReturn(new IdentityVerifier.Result(outcome, "REQ-TEST"));
    }

    private JsonNode submitIdentity(String token, String expectedStatus) throws Exception {
        String response = mvc.perform(post("/api/identity/applications").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(json.writeValueAsBytes(Map.of("legalName", "测试用户", "idNumber", "11010519491231002X", "declarationAccepted", true))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value(expectedStatus)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("11010519491231002X", "测试用户");
        return json.readTree(response).path("data");
    }

    @Test void onlyExplicitOperatorsCanEnumerateOrChangePlatformData() throws Exception {
        mvc.perform(get("/api/platform/information")).andExpect(status().isUnauthorized());
        for (String token : new String[]{merchantToken, staffToken}) {
            mvc.perform(get("/api/platform/access").header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.data.operator").value(false));
            mvc.perform(get("/api/platform/information").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            mvc.perform(get("/api/platform/identities").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            mvc.perform(post("/api/platform/tenants/{tenantId}/merchants/99/review", merchantTenant).header("Authorization", "Bearer " + token)
                    .contentType("application/json").content("{\"status\":\"APPROVED\",\"note\":\"测试审核记录\"}")).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/platform/summary").header("Authorization", "Bearer " + operatorToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approvedMerchants").value(0));
    }

    @Test void identitySubmissionIsEncryptedIsolatedAuditedAndRetriedAfterAMismatchWithoutMembership() throws Exception {
        verdict(IdentityVerifier.Outcome.MISMATCH);
        long id = submitIdentity(merchantToken, "REJECTED").path("id").asLong();
        byte[] ciphertext = owner.queryForObject("SELECT id_number_ciphertext FROM real_name_applications WHERE id=?", byte[].class, id);
        assertThat(ciphertext).isNotEqualTo("11010519491231002X".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(new String(ciphertext, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("11010519491231002X");
        mvc.perform(get("/api/identity/me").header("Authorization", "Bearer " + staffToken)).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/platform/identities").header("Authorization", "Bearer " + operatorToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].tenantId").value(merchantTenant))
                .andExpect(jsonPath("$.data.items[0].idNumber").doesNotExist());
        mvc.perform(post("/api/platform/tenants/{t}/identities/{id}/reveal", merchantTenant, id).header("Authorization", "Bearer " + merchantToken)
                .contentType("application/json").content("{\"note\":\"核对本人实名资料\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/platform/tenants/{t}/identities/{id}/reveal", merchantTenant, id).header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content("{\"note\":\"核对申请人的实名资料\"}")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.idNumber").value("11010519491231002X"));
        mvc.perform(get("/api/identity/me").header("Authorization", "Bearer " + merchantToken)).andExpect(jsonPath("$.data.status").value("REJECTED"));
        // SUBMITTED, AUTO_REJECTED, REVEAL
        assertThat(owner.queryForObject("SELECT count(*) FROM platform_management_audit WHERE target_type='IDENTITY' AND target_id=?", Long.class, id)).isEqualTo(3);
        verdict(IdentityVerifier.Outcome.MATCH);
        assertThat(submitIdentity(merchantToken, "VERIFIED").path("id").asLong()).isEqualTo(id);
        mvc.perform(post("/api/platform/tenants/{t}/identities/{id}/review", merchantTenant, id).header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content("{\"status\":\"REJECTED\",\"note\":\"人工审核已经取消\"}")).andExpect(status().is4xxClientError());
        assertThat(owner.queryForObject("SELECT status FROM real_name_applications WHERE id=?", String.class, id)).isEqualTo("VERIFIED");
    }

    @Test void identityRejectsInvalidInputSavesNothingWithoutAVerdictAndRefusesDuplicates() throws Exception {
        mvc.perform(post("/api/identity/applications").header("Authorization", "Bearer " + merchantToken)
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("legalName", "测试用户", "idNumber", "110105194912310021", "declarationAccepted", true))))
                .andExpect(status().isBadRequest());
        verdict(IdentityVerifier.Outcome.UNAVAILABLE);
        mvc.perform(post("/api/identity/applications").header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("legalName", "测试用户", "idNumber", "11010519491231002X", "declarationAccepted", true))))
                .andExpect(status().isServiceUnavailable());
        assertThat(owner.queryForObject("SELECT count(*) FROM real_name_applications", Long.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM platform_management_audit WHERE target_type='IDENTITY'", Long.class)).isZero();
        verdict(IdentityVerifier.Outcome.MATCH);
        submitIdentity(operatorToken, "VERIFIED");
        mvc.perform(post("/api/identity/applications").header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("legalName", "测试用户", "idNumber", "11010519491231002X", "declarationAccepted", true))))
                .andExpect(status().isConflict());
    }

    @Test void platformModerationPersistsAndCannotBeBypassedByThePublisher() throws Exception {
        Long id = owner.queryForObject("INSERT INTO information_publications(tenant_id,submitted_by,category,title,merchant_name,region,content,status,published_at) VALUES (?,?,'notice','测试公告','测试商家','测试地区','测试正文','PUBLISHED',now()) RETURNING id", Long.class, merchantTenant, merchantUser);
        String updatedAt = owner.queryForObject("SELECT updated_at FROM information_publications WHERE id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant().toString(), id);
        mvc.perform(get("/api/platform/information").header("Authorization", "Bearer " + operatorToken)).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(post("/api/platform/tenants/{t}/information/{id}/status", merchantTenant, id).header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("status", "OFFLINE",
                        "note", "测试下线，等待平台复核", "updatedAt", updatedAt)))).andExpect(status().isOk());
        mvc.perform(get("/api/public/information/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(put("/api/information-publications/{id}", id).header("Authorization", "Bearer " + merchantToken)
                .contentType("application/json").content("{\"category\":\"notice\",\"title\":\"试图重新发布\",\"merchantName\":\"商家\",\"region\":\"地区\",\"content\":\"内容\",\"declarationAccepted\":true}"))
                .andExpect(status().isForbidden());
        assertThat(owner.queryForObject("SELECT platform_blocked FROM information_publications WHERE id=?", Boolean.class, id)).isTrue();
    }

    @Test void merchantReviewsPersistAndAreReflectedInListsAndAudit() throws Exception {
        Long id = owner.queryForObject("INSERT INTO merchant_applications(tenant_id,submitted_by,merchant_name,company_name,industry,region,credit_code,contact_name,contact_phone,introduction) VALUES (?,?,'测试商家','测试经营主体','餐饮','测试地区','91330100MA2ABC1234','测试联系人','13800000002','商家测试简介') RETURNING id", Long.class, merchantTenant, merchantUser);
        mvc.perform(get("/api/platform/summary").header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.pendingMerchants").value(1));
        mvc.perform(get("/api/platform/merchants").header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.items[0].creditCode").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].contactPhone").doesNotExist());
        mvc.perform(post("/api/platform/tenants/{t}/merchants/{id}/reveal", merchantTenant, id).header("Authorization", "Bearer " + merchantToken)
                .contentType("application/json").content("{\"note\":\"查阅经营主体资料测试\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/platform/tenants/{t}/merchants/{id}/reveal", merchantTenant, id).header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content("{\"note\":\"查阅经营主体资料测试\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.creditCode").value("91330100MA2ABC1234"))
                .andExpect(jsonPath("$.data.contactPhone").value("13800000002"));
        mvc.perform(post("/api/platform/tenants/{t}/merchants/{id}/review", merchantTenant, id).header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content("{\"status\":\"APPROVED\",\"note\":\"\"}"))
                .andExpect(status().isBadRequest());
        for (String state : new String[]{"APPROVED", "SUSPENDED", "APPROVED"}) {
            mvc.perform(post("/api/platform/tenants/{t}/merchants/{id}/review", merchantTenant, id).header("Authorization", "Bearer " + operatorToken)
                    .contentType("application/json").content(json.writeValueAsBytes(Map.of("status", state, "note", "测试审核：经营资料已核对"))))
                    .andExpect(status().isOk());
            assertThat(owner.queryForObject("SELECT status FROM merchant_applications WHERE id=?", String.class, id)).isEqualTo(state);
            mvc.perform(get("/api/platform/merchants").param("status", state).param("keyword", "测试商家").header("Authorization", "Bearer " + operatorToken))
                    .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].status").value(state));
        }
        mvc.perform(get("/api/platform/summary").header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.approvedMerchants").value(1)).andExpect(jsonPath("$.data.pendingMerchants").value(0));
        mvc.perform(get("/api/platform/tenants/{t}/audit/MERCHANT/{id}", merchantTenant, id).header("Authorization", "Bearer " + operatorToken))
                .andExpect(jsonPath("$.data.length()").value(4));
        mvc.perform(post("/api/platform/tenants/{t}/merchants/{id}/review", merchantTenant, id).header("Authorization", "Bearer " + operatorToken)
                .contentType("application/json").content("{\"status\":\"REJECTED\",\"note\":\"不支持的状态变更测试\"}"))
                .andExpect(status().isConflict());
    }
}
