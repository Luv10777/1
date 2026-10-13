package com.wuyao.growth.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.spec.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** WeChat Pay API v3 Native. Both responses and notifications must pass RSA verification. */
@Component @RequiredArgsConstructor
public class WechatPayGateway {
    private final WechatPayProperties config;
    private final ObjectMapper json;
    private final HttpClient http;
    public boolean configured() { return config.configured(); }
    public String create(BillingDtos.OrderView order,long tenantId) {
        requireConfigured();
        var body=new LinkedHashMap<String,Object>();
        body.put("appid",config.getAppId()); body.put("mchid",config.getMerchantId());
        body.put("description",order.kind().equals("TOPUP")?"一方志 · 充值烟火":"一方志 · "+order.plan().name());
        body.put("out_trade_no",tradeNo(order.id())); body.put("notify_url",config.getNotifyUrl());
        body.put("attach",tenantId+":"+order.id());
        body.put("time_expire",DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(order.expiresAt().truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.ofHours(8))));
        body.put("amount",Map.of("total",order.amountMinor(),"currency","CNY"));
        JsonNode result=send("POST","/v3/pay/transactions/native",body);
        String url=result.path("code_url").asText();
        if(!url.startsWith("weixin://wxpay/")) throw unavailable("微信支付未返回有效收款码");
        return url;
    }
    public JsonNode query(String id) { return send("GET","/v3/pay/transactions/out-trade-no/"+tradeNo(id)+"?mchid="+config.getMerchantId(),null,true); }
    public void close(String id) { send("POST","/v3/pay/transactions/out-trade-no/"+tradeNo(id)+"/close",Map.of("mchid",config.getMerchantId())); }
    public JsonNode notification(String body,String serial,String timestamp,String nonce,String signature) {
        requireConfigured();
        try {
            verify(body,serial,timestamp,nonce,signature);
            JsonNode event=json.readTree(body);
            if(!"TRANSACTION.SUCCESS".equals(event.path("event_type").asText())) throw new IllegalArgumentException("非支付成功通知");
            JsonNode r=event.path("resource");
            if(!"AEAD_AES_256_GCM".equals(r.path("algorithm").asText())) throw new IllegalArgumentException("通知加密算法无效");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(config.getApiV3Key().getBytes(StandardCharsets.UTF_8),"AES"),
                new GCMParameterSpec(128,r.path("nonce").asText().getBytes(StandardCharsets.UTF_8)));
            cipher.updateAAD(r.path("associated_data").asText().getBytes(StandardCharsets.UTF_8));
            return json.readTree(cipher.doFinal(Base64.getDecoder().decode(r.path("ciphertext").asText())));
        } catch(Exception e) { throw BizException.of(ErrorCode.BAD_REQUEST,"微信支付通知验证失败"); }
    }
    public void validatePaid(JsonNode transaction,BillingDtos.OrderView order,long tenantId) {
        if(!"SUCCESS".equals(transaction.path("trade_state").asText())
            ||!config.getAppId().equals(transaction.path("appid").asText())
            ||!config.getMerchantId().equals(transaction.path("mchid").asText())
            ||!tradeNo(order.id()).equals(transaction.path("out_trade_no").asText())
            ||!(tenantId+":"+order.id()).equals(transaction.path("attach").asText())
            ||!"CNY".equals(transaction.path("amount").path("currency").asText())
            ||order.amountMinor()!=transaction.path("amount").path("total").asLong(-1)
            ||transaction.path("transaction_id").asText().isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST,"微信支付结果与订单不一致");
    }
    private JsonNode send(String method,String path,Map<String,Object> body) {
        return send(method,path,body,false);
    }
    private JsonNode send(String method,String path,Map<String,Object> body,boolean allowMissingOrder) {
        requireConfigured();
        try {
            String payload=body==null?"":json.writeValueAsString(body);
            String nonce=UUID.randomUUID().toString().replace("-",""),timestamp=Long.toString(Instant.now().getEpochSecond());
            String canonical=method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+payload+"\n";
            Signature rsa=Signature.getInstance("SHA256withRSA"); rsa.initSign(privateKey()); rsa.update(canonical.getBytes(StandardCharsets.UTF_8));
            var request="GET".equals(method)?new HttpGet("https://api.mch.weixin.qq.com"+path):new HttpPost("https://api.mch.weixin.qq.com"+path);
            request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(5)).setResponseTimeout(Timeout.ofSeconds(20)).build());
            request.setHeader("Authorization","WECHATPAY2-SHA256-RSA2048 mchid=\""+config.getMerchantId()+"\",nonce_str=\""+nonce
                +"\",timestamp=\""+timestamp+"\",serial_no=\""+config.getMerchantSerial()+"\",signature=\""+Base64.getEncoder().encodeToString(rsa.sign())+"\"");
            request.setHeader("Wechatpay-Serial",config.getPlatformKeyId());
            request.setHeader("Accept","application/json"); request.setHeader("User-Agent","Yifangzhi-Billing/1.0");
            if(request instanceof HttpPost post) post.setEntity(new StringEntity(payload,ContentType.APPLICATION_JSON));
            return http.execute(request,response->{
                String responseBody=response.getEntity()==null?"":EntityUtils.toString(response.getEntity(),StandardCharsets.UTF_8);
                try { verify(responseBody,response.getFirstHeader("Wechatpay-Serial")==null?null:response.getFirstHeader("Wechatpay-Serial").getValue(),
                    response.getFirstHeader("Wechatpay-Timestamp")==null?null:response.getFirstHeader("Wechatpay-Timestamp").getValue(),
                    response.getFirstHeader("Wechatpay-Nonce")==null?null:response.getFirstHeader("Wechatpay-Nonce").getValue(),
                    response.getFirstHeader("Wechatpay-Signature")==null?null:response.getFirstHeader("Wechatpay-Signature").getValue());
                } catch(Exception invalidSignature) { throw unavailable("微信支付响应验证失败，请刷新订单后重试"); }
                JsonNode decoded=responseBody.isBlank()?json.createObjectNode():json.readTree(responseBody);
                // Only a verified ORDER_NOT_EXIST response proves that a timed-out create may be retried.
                if(allowMissingOrder&&response.getCode()==404&&"ORDER_NOT_EXIST".equals(decoded.path("code").asText())) return decoded;
                if(response.getCode()<200||response.getCode()>=300) throw unavailable("微信支付暂未完成，请刷新订单后重试");
                return decoded;
            });
        } catch(BizException e) { throw e; }
        catch(Exception e) { throw unavailable("暂时无法连接微信支付，请稍后重试"); }
    }
    void verify(String body,String serial,String timestamp,String nonce,String signature) throws Exception {
        if(!config.getPlatformKeyId().equals(serial)||timestamp==null||nonce==null||signature==null
            ||Math.abs(Instant.now().getEpochSecond()-Long.parseLong(timestamp))>300) throw new SignatureException("微信支付签名头无效");
        Signature rsa=Signature.getInstance("SHA256withRSA"); rsa.initVerify(publicKey());
        rsa.update((timestamp+"\n"+nonce+"\n"+body+"\n").getBytes(StandardCharsets.UTF_8));
        if(!rsa.verify(Base64.getDecoder().decode(signature))) throw new SignatureException("微信支付验签失败");
    }
    private PrivateKey privateKey() throws Exception {
        String pem=Files.readString(Path.of(config.getPrivateKeyPath()));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pemBytes(pem)));
    }
    private PublicKey publicKey() throws Exception {
        String pem=Files.readString(Path.of(config.getPlatformPublicKeyPath()));
        if(pem.contains("BEGIN CERTIFICATE")) return CertificateFactory.getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8))).getPublicKey();
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pemBytes(pem)));
    }
    private byte[] pemBytes(String pem) { return Base64.getDecoder().decode(pem.replaceAll("-----[^-]+-----","").replaceAll("\\s","")); }
    private String tradeNo(String id) { return UUID.fromString(id).toString().replace("-",""); }
    private void requireConfigured() { if(!configured()) throw unavailable("微信收款服务暂未开放，请联系一方志客服"); }
    private BizException unavailable(String message) { return BizException.of(ErrorCode.PAYMENT_UNAVAILABLE,message); }
}
