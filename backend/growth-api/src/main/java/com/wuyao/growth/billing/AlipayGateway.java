package com.wuyao.growth.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Alipay 电脑网站支付 in public-key mode. Both responses and notifications must pass RSA2 verification. */
@Component @RequiredArgsConstructor
public class AlipayGateway {
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AlipayProperties config;
    private final ObjectMapper json;
    private final HttpClient http;
    public boolean configured() { return config.configured(); }
    /** Signed locally on every request: Alipay rejects stale request timestamps, so the URL is never stored. */
    public String pageUrl(BillingDtos.OrderView order,long tenantId) {
        requireConfigured();
        var biz=new LinkedHashMap<String,Object>();
        biz.put("out_trade_no",tradeNo(order.id())); biz.put("total_amount",yuan(order.amountMinor()));
        biz.put("subject",order.kind().equals("TOPUP")?"一方志 · 充值烟火":"一方志 · "+order.plan().name());
        biz.put("product_code","FAST_INSTANT_TRADE_PAY");
        biz.put("time_expire",TIME.format(order.expiresAt().atZone(SHANGHAI)));
        // Only URL-safe characters, so Alipay returns it unchanged in the notification.
        biz.put("passback_params",tenantId+"_"+order.id());
        try { return config.getGateway()+"?"+form(signed("alipay.trade.page.pay",biz,true)); }
        catch(Exception e) { throw unavailable("支付宝收银台暂时无法打开，请稍后重试"); }
    }
    public JsonNode query(String id) { return send("alipay.trade.query",Map.of("out_trade_no",tradeNo(id))); }
    public void close(String id) { send("alipay.trade.close",Map.of("out_trade_no",tradeNo(id))); }
    /** Alipay creates the trade only after the buyer opens the cashier, so a pending order may not exist remotely yet. */
    public static boolean missing(JsonNode result) { return "ACQ.TRADE_NOT_EXIST".equals(result.path("sub_code").asText()); }
    public static boolean paid(JsonNode result) {
        String status=result.path("trade_status").asText();
        return "TRADE_SUCCESS".equals(status)||"TRADE_FINISHED".equals(status);
    }
    public JsonNode notification(Map<String,String> params) {
        requireConfigured();
        try { if(!verify(content(params,true),params.get("sign"))) throw new SignatureException("支付宝验签失败"); }
        catch(Exception e) { throw BizException.of(ErrorCode.BAD_REQUEST,"支付宝通知验证失败"); }
        if(!config.getAppId().equals(params.get("app_id"))||!config.getSellerId().equals(params.get("seller_id")))
            throw BizException.of(ErrorCode.BAD_REQUEST,"支付宝通知不属于本商户");
        var proof=json.createObjectNode(); params.forEach(proof::put);
        return proof;
    }
    public void validatePaid(JsonNode trade,BillingDtos.OrderView order) {
        boolean amount;
        try { amount=new BigDecimal(trade.path("total_amount").asText()).compareTo(BigDecimal.valueOf(order.amountMinor(),2))==0; }
        catch(NumberFormatException e) { amount=false; }
        if(!paid(trade)||!amount||!tradeNo(order.id()).equals(trade.path("out_trade_no").asText())
            ||trade.path("trade_no").asText().isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST,"支付宝支付结果与订单不一致");
    }
    private JsonNode send(String method,Map<String,Object> biz) {
        requireConfigured();
        try {
            var request=new HttpPost(config.getGateway());
            request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(5)).setResponseTimeout(Timeout.ofSeconds(20)).build());
            request.setEntity(new StringEntity(form(signed(method,biz,false)),ContentType.APPLICATION_FORM_URLENCODED.withCharset(StandardCharsets.UTF_8)));
            return http.execute(request,response->{
                String body=response.getEntity()==null?"":EntityUtils.toString(response.getEntity(),StandardCharsets.UTF_8);
                JsonNode result;
                try { result=verifiedResponse(body,method.replace('.','_')+"_response"); }
                catch(Exception invalidSignature) { throw unavailable("支付宝响应验证失败，请刷新订单后重试"); }
                // Only a verified TRADE_NOT_EXIST proves that the buyer has not opened the cashier.
                if("10000".equals(result.path("code").asText())||missing(result)) return result;
                throw unavailable("支付宝暂未完成，请刷新订单后重试");
            });
        } catch(BizException e) { throw e; }
        catch(Exception e) { throw unavailable("暂时无法连接支付宝，请稍后重试"); }
    }
    /** The signature covers the raw JSON text of the response node, so it is cut from the body rather than re-serialised. */
    JsonNode verifiedResponse(String body,String root) throws Exception {
        JsonNode tree=json.readTree(body);
        int start=body.indexOf("\""+root+"\""),sign=body.lastIndexOf("\"sign\"");
        if(start<0||sign<0||!tree.path(root).isObject()) throw new SignatureException("支付宝响应格式无效");
        start=body.indexOf('{',start); int end=body.lastIndexOf(',',sign);
        if(start<0||end<start||!verify(body.substring(start,end).trim(),tree.path("sign").asText())) throw new SignatureException("支付宝验签失败");
        return tree.path(root);
    }
    private SortedMap<String,String> signed(String method,Map<String,Object> biz,boolean notify) throws Exception {
        var params=new TreeMap<String,String>();
        params.put("app_id",config.getAppId()); params.put("method",method); params.put("format","JSON");
        params.put("charset","utf-8"); params.put("sign_type","RSA2"); params.put("version","1.0");
        params.put("timestamp",TIME.format(ZonedDateTime.now(SHANGHAI)));
        if(notify) params.put("notify_url",config.getNotifyUrl());
        params.put("biz_content",json.writeValueAsString(biz));
        Signature rsa=Signature.getInstance("SHA256withRSA"); rsa.initSign(privateKey());
        rsa.update(content(params,false).getBytes(StandardCharsets.UTF_8));
        params.put("sign",Base64.getEncoder().encodeToString(rsa.sign()));
        return params;
    }
    /** Sorted, non-empty key=value pairs without the signature; notifications also leave out sign_type. */
    static String content(Map<String,String> params,boolean notification) {
        return new TreeMap<>(params).entrySet().stream()
            .filter(e->!"sign".equals(e.getKey())&&!(notification&&"sign_type".equals(e.getKey()))&&e.getValue()!=null&&!e.getValue().isEmpty())
            .map(e->e.getKey()+"="+e.getValue()).collect(Collectors.joining("&"));
    }
    private boolean verify(String content,String signature) throws Exception {
        if(signature==null||signature.isBlank()) return false;
        Signature rsa=Signature.getInstance("SHA256withRSA"); rsa.initVerify(publicKey());
        rsa.update(content.getBytes(StandardCharsets.UTF_8));
        return rsa.verify(Base64.getDecoder().decode(signature));
    }
    private static String form(Map<String,String> params) {
        return params.entrySet().stream().map(e->e.getKey()+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
    private PrivateKey privateKey() throws Exception {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes(config.getPrivateKeyPath())));
    }
    private PublicKey publicKey() throws Exception {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes(config.getAlipayPublicKeyPath())));
    }
    /** The Alipay key tool writes bare Base64; PEM armour is accepted too. */
    private byte[] keyBytes(String path) throws Exception {
        return Base64.getDecoder().decode(Files.readString(Path.of(path)).replaceAll("-----[^-]+-----","").replaceAll("\\s",""));
    }
    private static String yuan(long minor) { return BigDecimal.valueOf(minor,2).toPlainString(); }
    private String tradeNo(String id) { return UUID.fromString(id).toString().replace("-",""); }
    private void requireConfigured() { if(!configured()) throw unavailable("支付宝收款服务暂未开放，请联系一方志客服"); }
    private BizException unavailable(String message) { return BizException.of(ErrorCode.PAYMENT_UNAVAILABLE,message); }
}
