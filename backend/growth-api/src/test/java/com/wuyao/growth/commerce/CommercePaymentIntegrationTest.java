package com.wuyao.growth.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.billing.BillingTestFixtures;
import com.wuyao.growth.billing.AlipayGateway;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"growth.worker.enabled=false", "logging.level.root=WARN",
        "logging.level.com.wuyao.growth=WARN", "growth.commerce.alipay.store-id=5"})
@AutoConfigureMockMvc
@Testcontainers
class CommercePaymentIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("commerce_test").withUsername("growth_owner").withPassword("test_owner_password");

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
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @MockitoBean AlipayGateway alipay;
    JdbcTemplate owner;

    @BeforeEach
    void setUp() {
        when(alipay.configured()).thenReturn(true);
        doCallRealMethod().when(alipay).validatePaid(any(JsonNode.class), anyString(), any(BigDecimal.class));
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("TRUNCATE tenants RESTART IDENTITY CASCADE");
        owner.update("INSERT INTO tenants(id,name) VALUES (1,'收款测试')");
        BillingTestFixtures.activateMembership(owner, 1L);
        owner.update("INSERT INTO users(id,tenant_id,phone,name) VALUES (1,1,'13800000001','订单管理员')");
        owner.update("INSERT INTO stores(id,tenant_id,name,created_by) VALUES (5,1,'官方店',1),(6,1,'其他店',1)");
        owner.update("""
                INSERT INTO products(id,tenant_id,store_id,name,type,category,price,sale_unit,online_status,inventory,created_by)
                VALUES (3,1,5,'官方商品','PHYSICAL','设备',98.75,'件','PUBLISHED',9,1),
                       (4,1,6,'其他商品','PHYSICAL','设备',50.00,'件','PUBLISHED',9,1)
                """);
    }

    @Test
    void guestCheckoutPaysThroughAlipayAndThenMerchantConfirmsDelivery() throws Exception {
        JsonNode receipt = place(3);
        assertThat(receipt.path("totalAmount").decimalValue()).isEqualByComparingTo("197.50");
        assertThat(receipt.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(receipt.path("payment").path("method").asText()).isEqualTo("ALIPAY");
        assertThat(receipt.path("payment").path("recipient").asText()).isEqualTo("嘉兴市梧曜科技有限公司");
        assertThat(receipt.path("paymentState").asText()).isEqualTo("PENDING");

        mvc.perform(get("/api/public/orders/{token}", receipt.path("lookupToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment.method").value("ALIPAY"))
                .andExpect(jsonPath("$.data.totalAmount").value(197.50))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.buyerPhone").doesNotExist());

        String token = jwt.issueAccessToken(1L, 1L, "13800000001");
        mvc.perform(get("/api/stores/5/orders").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].payment.method").value("ALIPAY"));
        var proof = proof(receipt, "197.50");
        when(alipay.queryTrade(receipt.path("orderNumber").asText())).thenReturn(proof);
        mvc.perform(post("/api/public/orders/{token}/refresh", receipt.path("lookupToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentState").value("PAID"))
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"));
        mvc.perform(patch("/api/stores/5/orders/{id}", receipt.path("id").asLong())
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.payment").doesNotExist());
        mvc.perform(get("/api/public/orders/{token}", receipt.path("lookupToken").asText()))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.payment").doesNotExist());
    }

    @Test
    void otherStoreOrdersNeverReceiveTheOfficialStoreQr() throws Exception {
        JsonNode receipt = place(4);
        assertThat(receipt.hasNonNull("payment")).isFalse();
        mvc.perform(get("/api/public/orders/{token}", receipt.path("lookupToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.payment").doesNotExist());
    }

    @Test
    void cancellationRemovesPaymentInstructionsAndRestoresInventory() throws Exception {
        JsonNode receipt = place(3);
        when(alipay.queryTrade(receipt.path("orderNumber").asText()))
                .thenReturn(json.createObjectNode().put("trade_status", "WAIT_BUYER_PAY"));
        String token = jwt.issueAccessToken(1L, 1L, "13800000001");
        mvc.perform(patch("/api/stores/5/orders/{id}", receipt.path("id").asLong())
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.payment").doesNotExist());
        mvc.perform(get("/api/public/orders/{token}", receipt.path("lookupToken").asText()))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.payment").doesNotExist());
        assertThat(owner.queryForObject("SELECT inventory FROM products WHERE id=3", Integer.class)).isEqualTo(9);
        verify(alipay).closeTrade(receipt.path("orderNumber").asText());
    }

    @Test
    void callbackChecksAmountAndTenantAndRepeatedNotificationDoesNotRegressStatus() throws Exception {
        JsonNode receipt = place(3);
        String orderId = receipt.path("id").asText();
        var proof = proof(receipt, "0.01");
        when(alipay.notification(anyMap())).thenReturn(proof);
        assertThat(mvc.perform(post("/api/billing/alipay/notify").param("sign", "test")).andReturn()
                .getResponse().getContentAsString()).isEqualTo("fail");
        assertThat(owner.queryForObject("SELECT payment_state FROM merchant_orders WHERE id=?", String.class, Long.parseLong(orderId))).isEqualTo("PENDING");
        proof.put("total_amount", "197.50").put("passback_params", "commerce_2_" + orderId);
        assertThat(mvc.perform(post("/api/billing/alipay/notify").param("sign", "test")).andReturn()
                .getResponse().getContentAsString()).isEqualTo("fail");
        proof.put("passback_params", "commerce_1_" + orderId);
        for (int i = 0; i < 2; i++) assertThat(mvc.perform(post("/api/billing/alipay/notify").param("sign", "test"))
                .andReturn().getResponse().getContentAsString()).isEqualTo("success");
        assertThat(owner.queryForObject("SELECT payment_state FROM merchant_orders WHERE id=?", String.class, Long.parseLong(orderId))).isEqualTo("PAID");
        assertThat(owner.queryForObject("SELECT inventory FROM products WHERE id=3", Integer.class)).isEqualTo(7);
        String token = jwt.issueAccessToken(1L, 1L, "13800000001");
        mvc.perform(patch("/api/stores/5/orders/{id}", orderId).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/public/orders/{token}/alipay", receipt.path("lookupToken").asText()))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidTokenAndOtherMerchantsCannotOpenOfficialCashier() throws Exception {
        mvc.perform(post("/api/public/orders/invalid/alipay")).andExpect(status().isNotFound());
        JsonNode receipt = place(4);
        mvc.perform(post("/api/public/orders/{token}/alipay", receipt.path("lookupToken").asText()))
                .andExpect(status().isConflict());
        verify(alipay, never()).pageUrl(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void providerUnavailableDoesNotLeaveAnOrderOrReservedInventory() throws Exception {
        when(alipay.configured()).thenReturn(false);
        var input = Map.of("items", java.util.List.of(Map.of("productId", 3, "quantity", 2)),
                "buyerName", "测试收货人", "buyerPhone", "13800000001", "shippingAddress", "测试地址");
        mvc.perform(post("/api/public/orders").contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isServiceUnavailable());
        assertThat(owner.queryForObject("SELECT count(*) FROM merchant_orders", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT inventory FROM products WHERE id=3", Integer.class)).isEqualTo(9);
    }

    @Test
    void historicalManualOrdersKeepQrAndCannotBecomeAutomaticPayments() throws Exception {
        JsonNode receipt = place(3);
        owner.update("UPDATE merchant_orders SET payment_method='MANUAL',payment_state=NULL,payment_expires_at=NULL,status='PENDING_CONFIRMATION' WHERE id=?", receipt.path("id").asLong());
        mvc.perform(get("/api/public/orders/{token}", receipt.path("lookupToken").asText()))
                .andExpect(jsonPath("$.data.payment.method").value("ALIPAY_MANUAL"));
        mvc.perform(post("/api/public/orders/{token}/alipay", receipt.path("lookupToken").asText()))
                .andExpect(status().isConflict());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode proof(JsonNode receipt, String amount) {
        return json.createObjectNode().put("trade_status", "TRADE_SUCCESS").put("trade_no", "trade_" + receipt.path("id").asText())
                .put("out_trade_no", receipt.path("orderNumber").asText()).put("total_amount", amount)
                .put("passback_params", "commerce_1_" + receipt.path("id").asText());
    }

    private JsonNode place(long productId) throws Exception {
        var input = Map.of("items", java.util.List.of(Map.of("productId", productId, "quantity", 2)),
                "buyerName", "测试收货人", "buyerPhone", "13800000001", "shippingAddress", "测试地址");
        var result = mvc.perform(post("/api/public/orders").contentType("application/json")
                        .content(json.writeValueAsString(input)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
}
