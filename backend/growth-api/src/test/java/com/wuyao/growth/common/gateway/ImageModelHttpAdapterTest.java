package com.wuyao.growth.common.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.BizException;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ImageModelHttpAdapterTest {
 @Test void claudeRefinerCanReturnJsonInsideOneMarkdownFence() throws Exception {
   try(var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var pool=Executors.newSingleThreadExecutor()) {
     var mapper=new ObjectMapper();
     String content="```json\n"+mapper.writeValueAsString(Map.of("prompt","Subject: 周末奶茶半价; Lighting: studio lighting"))+"\n```";
     String response=mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",content))),"usage",Map.of("total_tokens",50)));
     var captured=pool.submit(()->respond(server,200,response));
     var props=config(server);props.setBaseUrl("http://127.0.0.1:"+server.getLocalPort());
     props.getRefiner().setModel("claude-sonnet-4-6");props.getRefiner().setApiKey("test-key");
     var result=new ImageModelHttpAdapter(props,mapper).invoke(new ProviderRequest(ModelAlias.TEXT_REFINER,1L,"brief",Map.of("system","rules"),"refine-key"));
     assertThat(result.output()).containsEntry("prompt","Subject: 周末奶茶半价; Lighting: studio lighting");
     assertThat(result.output()).containsEntry("_model","claude-sonnet-4-6").containsEntry("_usage",Map.of("total_tokens",50));
     assertThat(captured.get(5,TimeUnit.SECONDS)).contains("claude-sonnet-4-6");
   }
 }
 @Test void advancedAndRefinerUseSeparateCredentialsModelsAndSamplingOptions() throws Exception {
   for(var alias:List.of(ModelAlias.TEXT_PLANNER_ADVANCED,ModelAlias.TEXT_REFINER)) {
     try(var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var pool=Executors.newSingleThreadExecutor()) {
       var captured=pool.submit(()->respond(server,200,"{\"choices\":[{\"message\":{\"content\":\"{\\\"prompt\\\":\\\"refined\\\"}\"}}],\"usage\":{\"total_tokens\":10}}"));
       var props=config(server);props.setBaseUrl("http://127.0.0.1:"+server.getLocalPort());
       var endpoint=alias==ModelAlias.TEXT_REFINER?props.getRefiner():props.getAdvancedPlanner();
       endpoint.setModel(alias==ModelAlias.TEXT_REFINER?"gpt-4o":"gpt-5.6-luna");
       endpoint.setApiKey("separate-key");endpoint.setTemperature(0.5);endpoint.setMaxTokens(1500);
       var adapter=new ImageModelHttpAdapter(props,new ObjectMapper());
       assertThat(adapter.supports()).contains(alias);
       var result=adapter.invoke(new ProviderRequest(alias,1L,"brief",Map.of("system","rules"),"stable"));
       assertThat(result.output()).containsEntry("_usage",Map.of("total_tokens",10));
       assertThat(captured.get(5,TimeUnit.SECONDS)).contains("Bearer separate-key",endpoint.getModel(),"\"temperature\":0.5","\"max_completion_tokens\":1500");
     }
   }
 }
 @Test void plannerSendsImageRolesAndReadsStructuredPlan() throws Exception {
   try(var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var pool=Executors.newSingleThreadExecutor()) {
     var captured=pool.submit(()->respond(server,200,"{\"choices\":[{\"message\":{\"content\":\"{\\\"summary\\\":\\\"test\\\",\\\"question\\\":\\\"\\\",\\\"items\\\":[]}\"}}]}"));
     var props=config(server);
     var adapter=new ImageModelHttpAdapter(props,new ObjectMapper());
     var result=adapter.invoke(new ProviderRequest(ModelAlias.TEXT_PLANNER,1L,"brief",
       Map.of("system","rules","references",List.of(Map.of("role","SUBJECT","dataUrl","data:image/png;base64,AA=="))),"stable-key"));
     assertThat(result.output().get("summary")).isEqualTo("test");
     String request=captured.get(5,TimeUnit.SECONDS);
     assertThat(request).contains("stable-key","SUBJECT","image_url","json_object");
     assertThat(adapter.supports()).containsExactly(ModelAlias.TEXT_PLANNER,ModelAlias.IMAGE_PRIMARY);
   }
 }
 @Test void imageQueryRetainsIdempotencyAndNeverLeaksErrorBody() throws Exception {
   try(var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var pool=Executors.newSingleThreadExecutor()) {
     var captured=pool.submit(()->respond(server,503,"secret-vendor-debug-data"));
     var adapter=new ImageModelHttpAdapter(config(server),new ObjectMapper());
     assertThatThrownBy(()->adapter.invoke(new ProviderRequest(ModelAlias.IMAGE_PRIMARY,1L,"prompt",
       Map.of("operation","query","jobId","job-123"),"stable-key")))
       .isInstanceOf(BizException.class).hasMessageContaining("503").hasMessageNotContaining("secret");
     assertThat(captured.get(5,TimeUnit.SECONDS)).contains("query","job-123","stable-key");
   }
 }
 @Test void unconfiguredCapabilitiesCannotFallThroughToEcho() {
   var adapter=new ImageModelHttpAdapter(new ImageModelProperties(),new ObjectMapper());
   var gateway=new AiGateway(List.of(new EchoProviderAdapter(),adapter));
   assertThat(gateway.configured(ModelAlias.IMAGE_PRIMARY)).isFalse();
   assertThatThrownBy(()->gateway.invokeReal(new ProviderRequest(ModelAlias.IMAGE_PRIMARY,1L,"",Map.of(),"key")))
     .isInstanceOf(BizException.class);
 }
 private ImageModelProperties config(ServerSocket server) {
   var p=new ImageModelProperties();
   p.getGenerator().setProtocol(ImageModelProperties.Protocol.BRIDGE);
   for(var endpoint:List.of(p.getPlanner(),p.getGenerator())) {
     endpoint.setUrl("http://127.0.0.1:"+server.getLocalPort()+"/model");
     endpoint.setApiKey("test-key");endpoint.setModel("test-model");
   }
   return p;
 }
 @Test void openAiGenerationsUseSizeAndBase64WithoutBridgeFields() throws Exception {
   try(var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var pool=Executors.newSingleThreadExecutor()) {
     var captured=pool.submit(()->respond(server,200,"{\"data\":[{\"b64_json\":\"aW1hZ2U=\"}],\"usage\":{\"total_tokens\":7}}"));
     var p=openAiConfig(server);
     var result=new ImageModelHttpAdapter(p,new ObjectMapper()).invoke(imageRequest(List.of()));
     assertThat(result.output()).containsEntry("status","SUCCEEDED").containsEntry("imageBase64","aW1hZ2U=");
     assertThat(result.output().get("usage")).isInstanceOf(Map.class);
     String request=captured.get(5,TimeUnit.SECONDS);
     assertThat(request).startsWith("POST /v1/images/generations ").contains("Bearer test-key","stable-key","\"size\":\"1104x1472\"","\"n\":1");
     assertThat(request).contains("\"quality\":\"high\"").doesNotContain("\"operation\"","\"seriesKey\"","\"references\"","response_format");
   }
 }
 @Test void referencesUseMultipartEditsWithOrderedRolesAndBinaryFiles() throws Exception {
   try(var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var pool=Executors.newSingleThreadExecutor()) {
     var captured=pool.submit(()->respond(server,200,"{\"data\":[{\"b64_json\":\"aW1hZ2U=\"}]}"));
     var p=openAiConfig(server);
     var refs=List.<Map<String,Object>>of(Map.of("role","SUBJECT","dataUrl","data:image/png;base64,c3ViamVjdA=="),
       Map.of("role","STYLE","dataUrl","data:image/jpeg;base64,c3R5bGU="));
     new ImageModelHttpAdapter(p,new ObjectMapper()).invoke(imageRequest(refs));
     String request=captured.get(5,TimeUnit.SECONDS);
     assertThat(request).startsWith("POST /v1/images/edits ").contains("multipart/form-data; boundary=","name=\"image[]\"",
       "filename=\"reference-1.png\"","filename=\"reference-2.jpg\"","subject","style","参考图 1","参考图 2","1104x1472");
     assertThat(request).contains("name=\"quality\"","\r\nhigh\r\n").doesNotContain("data:image","response_format");
   }
 }
 @Test void imageUrlDownloadsRequireExactOriginAndNeverReceiveCredentials() throws Exception {
   try(var server=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));var pool=Executors.newSingleThreadExecutor()) {
     String origin="http://127.0.0.1:"+server.getLocalPort();
     var captured=pool.submit(()->{
       respond(server,200,"{\"data\":[{\"url\":\""+origin+"/image.png?signature=private\"}]}");
       return respond(server,200,"image-bytes");
     });
     var p=openAiConfig(server);p.getGenerator().setDownloadAllowedOrigins(List.of(origin));
     var result=new ImageModelHttpAdapter(p,new ObjectMapper()).invoke(imageRequest(List.of()));
     assertThat(result.output().get("imageBase64")).isEqualTo(Base64.getEncoder().encodeToString("image-bytes".getBytes(StandardCharsets.UTF_8)));
     assertThat(captured.get(5,TimeUnit.SECONDS)).startsWith("GET /image.png?signature=private ").doesNotContain("Authorization","test-key","Idempotency-Key");
   }
 }
 @Test void unapprovedImageUrlsAreRejectedBeforeDownload() throws Exception {
   try(var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var pool=Executors.newSingleThreadExecutor()) {
     var captured=pool.submit(()->respond(server,200,"{\"data\":[{\"url\":\"http://169.254.169.254/private?secret=123\"}]}"));
     assertThatThrownBy(()->new ImageModelHttpAdapter(openAiConfig(server),new ObjectMapper()).invoke(imageRequest(List.of())))
       .isInstanceOf(BizException.class).hasMessageContaining("IMAGE_DOWNLOAD_ALLOWED_ORIGINS").hasMessageNotContaining("secret");
     captured.get(5,TimeUnit.SECONDS);
   }
 }
 @Test void compatibleApiDoesNotInventAnAsyncQueryEndpoint() throws Exception {
   var p=new ImageModelProperties();p.getGenerator().setModel("gpt-image-2");
   assertThatThrownBy(()->new ImageModelHttpAdapter(p,new ObjectMapper()).invoke(new ProviderRequest(
     ModelAlias.IMAGE_PRIMARY,1L,"",Map.of("operation","query"),"stable-key")))
     .isInstanceOf(BizException.class).hasMessageContaining("不支持此异步任务查询");
 }
 @Test void baseUrlsAndExplicitOverridesAreResolvedWithoutDuplicatingV1() {
   var p=new ImageModelProperties();p.setBaseUrl("https://relay.example/prefix/v1/");
   assertThat(p.plannerUrl()).isEqualTo("https://relay.example/prefix/v1/chat/completions");
   assertThat(p.editsUrl()).isEqualTo("https://relay.example/prefix/v1/images/edits");
   p.setBaseUrl("https://relay.example");assertThat(p.generatorUrl()).isEqualTo("https://relay.example/v1/images/generations");
   p.getGenerator().setEditsUrl("https://other.example/edit");assertThat(p.editsUrl()).isEqualTo("https://other.example/edit");
 }
 private ImageModelProperties openAiConfig(ServerSocket server) {
   var p=config(server);p.setBaseUrl("http://127.0.0.1:"+server.getLocalPort()+"/v1");
   p.getGenerator().setUrl("");p.getGenerator().setProtocol(ImageModelProperties.Protocol.OPENAI);
   p.getGenerator().setModel("gpt-image-2");return p;
 }
 private ProviderRequest imageRequest(List<Map<String,Object>> refs) {
   return new ProviderRequest(ModelAlias.IMAGE_PRIMARY,1L,"画面需求",Map.of("operation","submit","width",1104,"height",1472,
     "quality","1080P","seriesKey","creation-1","references",refs),"stable-key");
 }
 private String respond(ServerSocket server,int status,String response) throws Exception {
   try(var client=server.accept()) {
     client.setSoTimeout(5000);
     var in=client.getInputStream();
     var header=new java.io.ByteArrayOutputStream();
     String headers;
     while(true) {
       int b=in.read();if(b<0)throw new IllegalStateException("EOF");header.write(b);
       headers=header.toString(StandardCharsets.US_ASCII);
       if(headers.endsWith("\r\n\r\n"))break;
       if(header.size()>16000)throw new IllegalStateException("Headers too large");
     }
     int size=Arrays.stream(headers.split("\r\n")).filter(h->h.toLowerCase().startsWith("content-length:"))
       .mapToInt(h->Integer.parseInt(h.substring(h.indexOf(':')+1).trim())).findFirst().orElse(0);
     String body=new String(in.readNBytes(size),StandardCharsets.UTF_8);
     byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
     var out=client.getOutputStream();
     out.write(("HTTP/1.1 "+status+" Response\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
     out.write(bytes);out.flush();
     return headers+body;
   }
 }
}
