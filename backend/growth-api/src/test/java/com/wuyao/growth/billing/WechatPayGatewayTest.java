package com.wuyao.growth.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.HttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.*;

class WechatPayGatewayTest {
    @TempDir Path folder;
    private final ObjectMapper json=new ObjectMapper();
    private WechatPayProperties config;
    private WechatPayGateway gateway;
    private KeyPair pair;
    @BeforeEach void setup() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);pair=generator.generateKeyPair();
        Path pub=folder.resolve("public.pem"),key=folder.resolve("private.pem");
        Files.writeString(pub,"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        Files.writeString(key,"-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        config=new WechatPayProperties();config.setAppId("test-app");config.setMerchantId("test-merchant");config.setMerchantSerial("TESTSERIAL");
        config.setApiV3Key("01234567890123456789012345678901");config.setPlatformKeyId("TEST_KEY_ID");
        config.setPlatformPublicKeyPath(pub.toString());config.setPrivateKeyPath(key.toString());config.setNotifyUrl("https://test.invalid/notify");
        gateway=new WechatPayGateway(config,json,mock(HttpClient.class));
    }
    private String sign(String body,String time,String nonce) throws Exception {
        var signature=Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());
        signature.update((time+"\n"+nonce+"\n"+body+"\n").getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }
    @Test void validSignedEncryptedNotificationsDecryptButTamperingAndReplayFail() throws Exception {
        String nonce="012345678901",aad="transaction",time=Long.toString(Instant.now().getEpochSecond());
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(config.getApiV3Key().getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        String encrypted=Base64.getEncoder().encodeToString(cipher.doFinal("{\"trade_state\":\"SUCCESS\"}".getBytes(StandardCharsets.UTF_8)));
        String body=json.writeValueAsString(Map.of("event_type","TRANSACTION.SUCCESS","resource",Map.of("algorithm","AEAD_AES_256_GCM","nonce",nonce,"associated_data",aad,"ciphertext",encrypted)));
        String signature=sign(body,time,"notify-nonce");
        assertThat(gateway.notification(body,"TEST_KEY_ID",time,"notify-nonce",signature).path("trade_state").asText()).isEqualTo("SUCCESS");
        assertThatThrownBy(()->gateway.notification(body+" ","TEST_KEY_ID",time,"notify-nonce",signature)).hasMessageContaining("验证失败");
        assertThatThrownBy(()->gateway.notification(body,"UNKNOWN",time,"notify-nonce",signature)).hasMessageContaining("验证失败");
        String stale=Long.toString(Instant.now().minusSeconds(600).getEpochSecond()),oldSignature=sign(body,stale,"notify-nonce");
        assertThatThrownBy(()->gateway.notification(body,"TEST_KEY_ID",stale,"notify-nonce",oldSignature)).hasMessageContaining("验证失败");
    }
    @Test void paymentProofMustMatchMerchantCurrencyAmountAndTenantOrder() throws Exception {
        String id=UUID.randomUUID().toString();
        var order=new BillingDtos.OrderView(id,"TOPUP",100,10,"PENDING_PAYMENT",null,Instant.now(),Instant.now().plusSeconds(600),null,null,"WECHAT",null);
        var proof=json.createObjectNode().put("trade_state","SUCCESS").put("appid","test-app").put("mchid","test-merchant")
                .put("out_trade_no",id.replace("-","")).put("attach","7:"+id).put("transaction_id","test-transaction");
        proof.putObject("amount").put("total",100).put("currency","CNY");
        gateway.validatePaid(proof,order,7);
        proof.withObject("amount").put("total",101);assertThatThrownBy(()->gateway.validatePaid(proof,order,7)).hasMessageContaining("不一致");
        proof.withObject("amount").put("total",100);assertThatThrownBy(()->gateway.validatePaid(proof,order,8)).hasMessageContaining("不一致");
        proof.put("mchid","other");assertThatThrownBy(()->gateway.validatePaid(proof,order,7)).hasMessageContaining("不一致");
    }
    @Test void missingMerchantConfigurationDisablesPayment() {
        assertThat(gateway.configured()).isTrue();config.setApiV3Key("");
        assertThat(gateway.configured()).isFalse();assertThatThrownBy(()->gateway.query(UUID.randomUUID().toString())).hasMessageContaining("暂未开放");
    }
    @Test void missingOrderRecoveryAcceptsOnlyASignedProviderResponse() throws Exception {
        var http=mock(HttpClient.class);
        gateway=new WechatPayGateway(config,json,http);
        String body="{\"code\":\"ORDER_NOT_EXIST\"}",time=Long.toString(Instant.now().getEpochSecond());
        var response=new org.apache.hc.core5.http.message.BasicClassicHttpResponse(404);
        response.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(body,org.apache.hc.core5.http.ContentType.APPLICATION_JSON));
        response.setHeader("Wechatpay-Serial","TEST_KEY_ID");response.setHeader("Wechatpay-Timestamp",time);
        response.setHeader("Wechatpay-Nonce","missing-order");response.setHeader("Wechatpay-Signature",sign(body,time,"missing-order"));
        doAnswer(invocation->{
            org.apache.hc.core5.http.io.HttpClientResponseHandler<?> handler=invocation.getArgument(1);
            return handler.handleResponse(response);
        }).when(http).execute(any(org.apache.hc.core5.http.ClassicHttpRequest.class),any(org.apache.hc.core5.http.io.HttpClientResponseHandler.class));
        assertThat(gateway.query(UUID.randomUUID().toString()).path("code").asText()).isEqualTo("ORDER_NOT_EXIST");
        response.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(body,org.apache.hc.core5.http.ContentType.APPLICATION_JSON));
        response.setHeader("Wechatpay-Signature",Base64.getEncoder().encodeToString(new byte[256]));
        assertThatThrownBy(()->gateway.query(UUID.randomUUID().toString())).hasMessageContaining("响应验证失败");
    }
}
