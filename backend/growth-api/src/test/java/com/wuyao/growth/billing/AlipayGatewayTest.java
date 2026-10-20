package com.wuyao.growth.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlipayGatewayTest {
    @TempDir Path folder;
    private final ObjectMapper json=new ObjectMapper();
    private AlipayProperties config;
    private AlipayGateway gateway;
    private KeyPair merchant,alipay;
    @BeforeEach void setup() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        merchant=generator.generateKeyPair();alipay=generator.generateKeyPair();
        Path key=folder.resolve("app-private.txt"),pub=folder.resolve("alipay-public.txt");
        // The Alipay key tool writes bare Base64 without PEM armour.
        Files.writeString(key,Base64.getEncoder().encodeToString(merchant.getPrivate().getEncoded()));
        Files.writeString(pub,Base64.getEncoder().encodeToString(alipay.getPublic().getEncoded()));
        config=new AlipayProperties();config.setAppId("test-app");config.setSellerId("2088000000000000");
        config.setPrivateKeyPath(key.toString());config.setAlipayPublicKeyPath(pub.toString());config.setNotifyUrl("https://test.invalid/notify");
        gateway=new AlipayGateway(config,json,mock(HttpClient.class));
    }
    private String sign(PrivateKey key,String content) throws Exception {
        var rsa=Signature.getInstance("SHA256withRSA");rsa.initSign(key);rsa.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(rsa.sign());
    }
    private boolean verify(PublicKey key,String content,String signature) throws Exception {
        var rsa=Signature.getInstance("SHA256withRSA");rsa.initVerify(key);rsa.update(content.getBytes(StandardCharsets.UTF_8));
        return rsa.verify(Base64.getDecoder().decode(signature));
    }
    private BillingDtos.OrderView order(String id,long amount,Instant expires) {
        return new BillingDtos.OrderView(id,"TOPUP",amount,amount/10,"PENDING_PAYMENT",null,Instant.now(),expires,null,null,"ALIPAY",null,null,null);
    }
    @Test void cashierUrlIsSignedByTheMerchantKeyAndCarriesTheOrder() throws Exception {
        String id=UUID.randomUUID().toString();
        String url=gateway.pageUrl(order(id,1234,Instant.parse("2030-01-02T03:04:05.987654321Z")),7);
        assertThat(url).startsWith("https://openapi.alipay.com/gateway.do?");
        var params=new HashMap<String,String>();
        for(String pair:URI.create(url).getRawQuery().split("&")) {
            String[] kv=pair.split("=",2);params.put(kv[0],URLDecoder.decode(kv[1],StandardCharsets.UTF_8));
        }
        assertThat(verify(merchant.getPublic(),AlipayGateway.content(params,false),params.get("sign"))).isTrue();
        assertThat(params).containsEntry("method","alipay.trade.page.pay").containsEntry("app_id","test-app")
            .containsEntry("sign_type","RSA2").containsEntry("notify_url","https://test.invalid/notify");
        var biz=json.readTree(params.get("biz_content"));
        assertThat(biz.path("out_trade_no").asText()).isEqualTo(id.replace("-",""));
        assertThat(biz.path("total_amount").asText()).isEqualTo("12.34");
        assertThat(biz.path("time_expire").asText()).isEqualTo("2030-01-02 11:04:05");
        assertThat(biz.path("passback_params").asText()).isEqualTo("7_"+id);
    }
    @Test void signedNotificationsVerifyButTamperingAndOtherMerchantsFail() throws Exception {
        var params=new TreeMap<String,String>(Map.of("app_id","test-app","seller_id","2088000000000000","trade_status","TRADE_SUCCESS",
            "out_trade_no","abc","total_amount","1.00","trade_no","2030","passback_params","7_x","notify_id","n","sign_type","RSA2"));
        params.put("sign",sign(alipay.getPrivate(),AlipayGateway.content(params,true)));
        assertThat(gateway.notification(params).path("trade_no").asText()).isEqualTo("2030");
        var tampered=new TreeMap<>(params);tampered.put("total_amount","0.01");
        assertThatThrownBy(()->gateway.notification(tampered)).hasMessageContaining("验证失败");
        var forged=new TreeMap<>(params);forged.put("sign",sign(merchant.getPrivate(),AlipayGateway.content(params,true)));
        assertThatThrownBy(()->gateway.notification(forged)).hasMessageContaining("验证失败");
        var other=new TreeMap<>(params);other.put("seller_id","2088999999999999");other.put("sign",sign(alipay.getPrivate(),AlipayGateway.content(other,true)));
        assertThatThrownBy(()->gateway.notification(other)).hasMessageContaining("不属于本商户");
    }
    @Test void paymentProofMustMatchOrderAmountAndTradeNumber() {
        String id=UUID.randomUUID().toString();
        var o=order(id,100,Instant.now().plusSeconds(600));
        var trade=json.createObjectNode().put("trade_status","TRADE_SUCCESS").put("out_trade_no",id.replace("-",""))
            .put("total_amount","1.00").put("trade_no","2030");
        gateway.validatePaid(trade,o);
        trade.put("total_amount","1.0");gateway.validatePaid(trade,o);
        trade.put("total_amount","1.01");assertThatThrownBy(()->gateway.validatePaid(trade,o)).hasMessageContaining("不一致");
        trade.put("total_amount","abc");assertThatThrownBy(()->gateway.validatePaid(trade,o)).hasMessageContaining("不一致");
        trade.put("total_amount","1.00").put("trade_status","WAIT_BUYER_PAY");assertThatThrownBy(()->gateway.validatePaid(trade,o)).hasMessageContaining("不一致");
        trade.put("trade_status","TRADE_FINISHED").put("out_trade_no","other");assertThatThrownBy(()->gateway.validatePaid(trade,o)).hasMessageContaining("不一致");
    }
    private void respond(HttpClient http,String body) {
        var response=new BasicClassicHttpResponse(200);
        response.setEntity(new StringEntity(body,ContentType.APPLICATION_JSON));
        try {
            doAnswer(invocation->{
                HttpClientResponseHandler<?> handler=invocation.getArgument(1);
                return handler.handleResponse(response);
            }).when(http).execute(any(ClassicHttpRequest.class),any(HttpClientResponseHandler.class));
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    @Test void queryAcceptsOnlySignedResponsesIncludingAMissingTrade() throws Exception {
        var http=mock(HttpClient.class);
        gateway=new AlipayGateway(config,json,http);
        String node="{\"code\":\"40004\",\"msg\":\"Business Failed\",\"sub_code\":\"ACQ.TRADE_NOT_EXIST\",\"sub_msg\":\"交易不存在\"}";
        respond(http,"{\"alipay_trade_query_response\":"+node+",\"sign\":\""+sign(alipay.getPrivate(),node)+"\"}");
        assertThat(AlipayGateway.missing(gateway.query(UUID.randomUUID().toString()))).isTrue();
        respond(http,"{\"alipay_trade_query_response\":"+node+",\"sign\":\""+sign(merchant.getPrivate(),node)+"\"}");
        assertThatThrownBy(()->gateway.query(UUID.randomUUID().toString())).hasMessageContaining("响应验证失败");
        respond(http,"{\"alipay_trade_query_response\":"+node+"}");
        assertThatThrownBy(()->gateway.query(UUID.randomUUID().toString())).hasMessageContaining("响应验证失败");
        String paid="{\"code\":\"10000\",\"msg\":\"Success\",\"trade_status\":\"TRADE_SUCCESS\",\"total_amount\":\"1.00\"}";
        respond(http,"{\"alipay_trade_query_response\":"+paid+",\"sign\":\""+sign(alipay.getPrivate(),paid)+"\"}");
        assertThat(AlipayGateway.paid(gateway.query(UUID.randomUUID().toString()))).isTrue();
        String denied="{\"code\":\"40006\",\"msg\":\"Insufficient Permissions\",\"sub_code\":\"isv.insufficient-isv-permissions\"}";
        respond(http,"{\"alipay_trade_query_response\":"+denied+",\"sign\":\""+sign(alipay.getPrivate(),denied)+"\"}");
        assertThatThrownBy(()->gateway.query(UUID.randomUUID().toString())).hasMessageContaining("暂未完成");
    }
    @Test void missingConfigurationDisablesPayment() {
        assertThat(gateway.configured()).isTrue();config.setSellerId("");
        assertThat(gateway.configured()).isFalse();
        assertThatThrownBy(()->gateway.query(UUID.randomUUID().toString())).hasMessageContaining("暂未开放");
    }
}
