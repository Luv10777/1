package com.wuyao.growth.common.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.*;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** OpenAI-compatible planner/images; the asynchronous bridge remains opt-in. */
@Component
@Slf4j
public class ImageModelHttpAdapter implements ProviderAdapter {
 private final ImageModelProperties config;
 private final ObjectMapper json;
 private final HttpClient httpClient;
 private static final HttpClient DEFAULT_HTTP_CLIENT=HttpClients.custom()
   .disableAutomaticRetries().disableRedirectHandling().build();
 @Autowired
 public ImageModelHttpAdapter(ImageModelProperties config,ObjectMapper json,HttpClient httpClient) {
   this.config=config;this.json=json;this.httpClient=httpClient;
 }
 /** Constructor retained for adapter unit tests and small command-line probes. */
 public ImageModelHttpAdapter(ImageModelProperties config,ObjectMapper json) {
   this(config,json,DEFAULT_HTTP_CLIENT);
 }
 public String code() {return "IMAGE_HTTP";}
 public Set<ModelAlias> supports() {
   var aliases=EnumSet.noneOf(ModelAlias.class);
   if(config.plannerConfigured()) aliases.add(ModelAlias.TEXT_PLANNER);
   if(config.advancedPlannerConfigured()) aliases.add(ModelAlias.TEXT_PLANNER_ADVANCED);
   if(config.refinerConfigured()) aliases.add(ModelAlias.TEXT_REFINER);
   if(config.generatorConfigured()) aliases.add(ModelAlias.IMAGE_PRIMARY);
   return aliases;
 }
 public ProviderResult invoke(ProviderRequest req) {
   boolean planner=Set.of(ModelAlias.TEXT_PLANNER,ModelAlias.TEXT_PLANNER_ADVANCED,ModelAlias.TEXT_REFINER).contains(req.alias());
   var endpoint=switch(req.alias()) {
     case TEXT_PLANNER -> config.getPlanner();
     case TEXT_PLANNER_ADVANCED -> config.getAdvancedPlanner();
     case TEXT_REFINER -> config.getRefiner();
     default -> config.getGenerator();
   };
   try {
     Map<String,Object> output;
     if(planner) output=plan(req,endpoint);
     else if(config.getGenerator().getProtocol()==ImageModelProperties.Protocol.BRIDGE) {
       var body=new LinkedHashMap<String,Object>(req.options());
       body.put("model",endpoint.getModel());body.put("prompt",req.prompt());body.put("idempotencyKey",req.idempotencyKey());
       output=json.readValue(post(config.generatorUrl(),endpoint,req,"application/json",List.of(json.writeValueAsBytes(body))),new TypeReference<>(){});
     } else output=image(req);
     if(!planner && output.get("imageUrl") instanceof String url && !url.isBlank()
         && !output.containsKey("imageSourceUrl")) {
       validateDownloadUrl(url);
       output.put("imageSourceUrl",url);
       if(isOnlyRouterFilesContent(URI.create(url))) output.put("imageUrl",resolveFileUrl(url));
     }
     output.put("_model",endpoint.getModel());
     return new ProviderResult(true,code(),(String)output.get("jobId"),output,null,null);
   } catch(BizException e) {throw e;}
   catch(ProviderOutcomeUnknownException e) {throw e;}
   catch(IOException e) {
     if(!planner) throw new ProviderOutcomeUnknownException("中转站图片响应无法完整解析，生成结果状态未确认", e);
     throw failure("规划模型响应异常或超时，请稍后重试");
   }
   catch(Exception e) {
     // Never expose provider bodies, signed URLs or credentials through exceptions.
     throw failure(planner?"规划模型响应异常或超时，请稍后重试":"图片服务响应异常或超时，请先核对中转站记录再重试");
   }
 }
 private Map<String,Object> plan(ProviderRequest req,ImageModelProperties.Endpoint endpoint) throws IOException {
   var content=new ArrayList<Map<String,Object>>();
   content.add(Map.of("type","text","text",req.prompt()));
   for(var reference:references(req)) {
     content.add(Map.of("type","text","text","Reference role: "+reference.get("role")));
     content.add(Map.of("type","image_url","image_url",Map.of("url",reference.get("dataUrl"))));
   }
   var body=new LinkedHashMap<String,Object>(Map.of("model",endpoint.getModel(),"stream",false,
     "messages",List.of(Map.of("role","system","content",req.options().get("system")),Map.of("role","user","content",content)),
     "response_format",Map.of("type","json_object")));
   if(endpoint.getTemperature()!=null) body.put("temperature",endpoint.getTemperature());
   if(endpoint.getMaxTokens()!=null) body.put("max_completion_tokens",endpoint.getMaxTokens());
   String url=switch(req.alias()) {
     case TEXT_PLANNER_ADVANCED -> config.advancedPlannerUrl();
     case TEXT_REFINER -> config.refinerUrl();
     default -> config.plannerUrl();
   };
   byte[] bytes=post(url,endpoint,req,"application/json",List.of(json.writeValueAsBytes(body)));
   var root=json.readTree(bytes);var choice=root.path("choices").path(0);
   if("length".equals(choice.path("finish_reason").asText())) throw failure("文本模型输出超出长度限制");
   String result=choice.path("message").path("content").asText();
   // Some compatible Claude endpoints wrap JSON despite response_format=json_object.
   // Only remove a single enclosing fence; malformed JSON still fails validation.
   result=result.strip().replaceFirst("(?is)^```(?:json)?\\s*(.*?)\\s*```$","$1");
   Map<String,Object> output=json.readValue(result,new TypeReference<>(){});
   if(root.path("usage").isObject()) output.put("_usage",json.convertValue(root.get("usage"),Map.class));
   return output;
 }
 private Map<String,Object> image(ProviderRequest req) throws IOException {
   if("query".equals(req.options().get("operation"))) throw failure("OpenAI 图片接口不支持此异步任务查询，请核对原任务接口协议");
   var endpoint=config.getGenerator();var refs=references(req);
   StringBuilder prompt=new StringBuilder(req.prompt());
   for(int n=0;n<refs.size();n++) prompt.append("\n参考图 ").append(n+1).append("STYLE".equals(refs.get(n).get("role"))?
     "：只参考风格，不复制商品、品牌或文字。":"：真实商品或门店主体，保持外观、包装与标识一致。");
   var body=new LinkedHashMap<String,Object>();
   body.put("model",endpoint.getModel());body.put("prompt",prompt.toString());body.put("n",1);
   body.put("size",req.options().get("width")+"x"+req.options().get("height"));
   // Resolution tiers are not OpenAI's low/medium/high parameter. GPT Image omits response_format.
   // The UI no longer exposes a quality selector. Treat legacy "auto" config as
   // the provider's highest standard quality so old containers follow the same rule.
   String quality = endpoint.getQuality();
   if (quality.isBlank() || quality.equalsIgnoreCase("auto")) quality = "high";
   body.put("quality", quality);
   if(!endpoint.getResponseFormat().isBlank()) body.put("response_format",endpoint.getResponseFormat());
   byte[] response;
   if(refs.isEmpty()) response=post(config.generatorUrl(),endpoint,req,"application/json",List.of(json.writeValueAsBytes(body)));
   else {
     String boundary="image-"+UUID.randomUUID();var chunks=new ArrayList<byte[]>();
     for(var field:body.entrySet()) chunks.add(utf8("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n"));
     for(int n=0;n<refs.size();n++) {
       String data=String.valueOf(refs.get(n).get("dataUrl"));
       String mime=data.startsWith("data:image/png;base64,")?"image/png":data.startsWith("data:image/jpeg;base64,")?"image/jpeg":null;
       if(mime==null) throw failure("参考图片必须是 JPG 或 PNG");
       byte[] bytes=Base64.getDecoder().decode(data.substring(data.indexOf(',')+1));
       if(bytes.length==0 || bytes.length>20*1024*1024) throw failure("参考图片大小超出限制");
       chunks.add(utf8("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+(refs.size()==1?"image":"image[]")+
         "\"; filename=\"reference-"+(n+1)+(mime.equals("image/png")?".png":".jpg")+"\"\r\nContent-Type: "+mime+"\r\n\r\n"));
       chunks.add(bytes);chunks.add(utf8("\r\n"));
     }
     chunks.add(utf8("--"+boundary+"--\r\n"));
     response=post(config.editsUrl(),endpoint,req,"multipart/form-data; boundary="+boundary,chunks);
   }
   var root=json.readTree(response);var data=root.path("data");
   if(!data.isArray() || data.size()!=1) throw failure("图片服务未返回单张有效图片");
   String url=data.get(0).path("url").asText("");
   var output=new LinkedHashMap<String,Object>();output.put("status","SUCCEEDED");
   if(!url.isBlank()) {
     validateDownloadUrl(url);
     output.put("imageUrl",isOnlyRouterFilesContent(URI.create(url))?resolveFileUrl(url):url);
     output.put("imageSourceUrl",url);
   } else {
     String encoded=data.get(0).path("b64_json").asText("");
     if(encoded.isBlank()) throw failure("图片服务未返回图片内容");
     if(encoded.length()>44*1024*1024) throw failure("生成图片超过大小限制");
     output.put("imageBase64",encoded);
   }
   if(root.path("usage").isObject()) output.put("usage",json.convertValue(root.get("usage"),Map.class));
   return output;
 }
 private List<Map<?,?>> references(ProviderRequest req) {
   var refs=new ArrayList<Map<?,?>>();
   if(req.options().get("references") instanceof List<?> list) {
     if(list.size()>6) throw failure("最多使用 6 张参考图片");
     for(var item:list) {if(!(item instanceof Map<?,?> ref)) throw failure("参考图片格式无效");refs.add(ref);}
   }
   return refs;
 }
 private byte[] post(String url,ImageModelProperties.Endpoint endpoint,ProviderRequest req,String contentType,List<byte[]> chunks) throws IOException {
   URI uri=checkedUri(url);
   var request=new HttpPost(uri);
   request.setHeader("Expect", "");
   request.setHeader("Authorization","Bearer "+endpoint.getApiKey());
   if(req.idempotencyKey()!=null && !req.idempotencyKey().isBlank()) request.setHeader("Idempotency-Key",req.idempotencyKey());
   ByteArrayOutputStream body=new ByteArrayOutputStream();
   for(byte[] chunk:chunks) { if(body.size()+chunk.length>128*1024*1024) throw failure("模型请求内容超过大小限制"); body.write(chunk); }
   request.setEntity(new ByteArrayEntity(body.toByteArray(),ContentType.parse(contentType)));
   request.setConfig(RequestConfig.custom()
     .setConnectionRequestTimeout(Timeout.ofSeconds(10))
     .setConnectTimeout(Timeout.ofSeconds(Math.max(1, Math.min(endpoint.getTimeoutSeconds(), 60))))
     .setResponseTimeout(Timeout.ofSeconds(Math.max(1, endpoint.getTimeoutSeconds())))
     .build());
   if(req.alias()!=ModelAlias.IMAGE_PRIMARY) return execute(request,endpoint.getModel(),48*1024*1024);
   long started=System.nanoTime();
   log.info("图片请求开始: model={} host={} requestBytes={}",endpoint.getModel(),uri.getHost(),body.size());
   try {
     byte[] response=execute(request,endpoint.getModel(),48*1024*1024);
     log.info("图片响应收完: model={} host={} responseBytes={} elapsedMs={}",endpoint.getModel(),uri.getHost(),response.length,(System.nanoTime()-started)/1_000_000);
     return response;
   } catch (IOException e) {
     log.warn("图片请求异常: model={} host={} elapsedMs={} type={}",endpoint.getModel(),uri.getHost(),
       (System.nanoTime()-started)/1_000_000,e.getClass().getSimpleName());
     if(req.alias()==ModelAlias.IMAGE_PRIMARY)
       throw new ProviderOutcomeUnknownException("中转站响应未完整返回，生成结果状态未确认", e);
     throw e;
   } catch (RuntimeException e) {
     log.warn("图片请求异常: model={} host={} elapsedMs={} type={}",endpoint.getModel(),uri.getHost(),
       (System.nanoTime()-started)/1_000_000,e.getClass().getSimpleName());
     throw e;
   }
 }
 private void validateDownloadUrl(String url) {
   URI uri=ImageDownloadOrigins.checkedUri(url);
   boolean allowed=ImageDownloadOrigins.allows(uri,config.getGenerator().getDownloadAllowedOrigins());
   if(!allowed) throw failure("请配置 IMAGE_DOWNLOAD_ALLOWED_ORIGINS，或让中转站返回 Base64 图片");
 }
 private boolean isOnlyRouterFilesContent(URI uri) {
   return "api.onlyrouter.ai".equalsIgnoreCase(uri.getHost())
     && uri.getPath()!=null && uri.getPath().matches("/v1/files/[^/]+/content");
 }
 private String resolveFileUrl(String url) throws IOException {
   var request=new HttpGet(URI.create(url));
   request.setHeader("Authorization","Bearer "+config.getGenerator().getApiKey());
   return httpClient.execute(request,(HttpClientResponseHandler<String>) response -> {
     if(response.getCode()!=302 || response.getFirstHeader("Location")==null)
       throw failure("Files API 未返回临时下载链接");
     String location=response.getFirstHeader("Location").getValue();
     URI signed=checkedUri(location);
     if(!"https".equalsIgnoreCase(signed.getScheme())) throw failure("Files API 下载链接无效");
     return location;
   });
 }
 private URI checkedUri(String value) {
   URI uri=URI.create(value);
   if(uri.getScheme()==null || !Set.of("http","https").contains(uri.getScheme().toLowerCase(Locale.ROOT)) || uri.getHost()==null
       || uri.getUserInfo()!=null || uri.getFragment()!=null) throw failure("模型接口地址格式无效");
   return uri;
 }
 private byte[] execute(org.apache.hc.core5.http.ClassicHttpRequest request,String model,int limit) throws IOException {
   return httpClient.execute(request,(HttpClientResponseHandler<byte[]>) response -> responseBytes(response,model,limit));
 }
 private byte[] responseBytes(ClassicHttpResponse connection,String model,int limit) throws IOException {
   int status=connection.getCode();
   if(model.equals(config.getGenerator().getModel()))
     log.info("图片响应头收到: status={} contentLength={}",status,
       connection.getEntity()==null?0:connection.getEntity().getContentLength());
   if(status<200 || status>=300) {
     String reason=switch(status) {
       case 401 -> "模型服务令牌无效或已过期";
       case 403 -> "模型未获当前令牌授权，或模型 ID 不受中转站支持（"+model+"）";
       case 404 -> "模型接口或模型 ID 不存在（"+model+"）";
       case 400 -> "模型服务拒绝请求参数（请核对模型 ID、尺寸和质量参数）";
       default -> "模型服务请求失败（HTTP "+status+"）";
     };
     throw failure(reason);
   }
   HttpEntity entity=connection.getEntity();
   if(entity==null) return new byte[0];
   if(entity.getContentLength()>limit) throw failure("模型返回内容超过大小限制");
   try(var stream=entity.getContent()) {
     byte[] bytes=stream.readNBytes(limit+1);
     if(bytes.length>limit) throw failure("模型返回内容超过大小限制");return bytes;
   }
 }
 private byte[] utf8(String value) {return value.getBytes(StandardCharsets.UTF_8);}
 private BizException failure(String message) {return BizException.of(ErrorCode.IMAGE_PROVIDER_ERROR,message);}
}
