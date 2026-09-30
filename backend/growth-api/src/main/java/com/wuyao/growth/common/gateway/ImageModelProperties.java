package com.wuyao.growth.common.gateway;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component @ConfigurationProperties(prefix="growth.image") @Getter @Setter
public class ImageModelProperties {
 private Endpoint text=new Endpoint();
 private Generator generator=new Generator();
 private String baseUrl="";
 private List<String> qualities=List.of("1K","2K","4K");
 private long maxOutputPixels;
 private int timeoutSeconds=300;
 private int pollSeconds=5;
 private int maxPolls=360;
 private String font="Microsoft YaHei";
 public String textUrl() {return endpoint(text.getUrl(),"/chat/completions");}
 public ModelAlias selectedTextAlias() {return ModelAlias.TEXT_CREATIVE;}
 public String generatorUrl() {return endpoint(generator.getUrl(),"/images/generations");}
 public String editsUrl() {
   if(!generator.getEditsUrl().isBlank()) return generator.getEditsUrl();
   String url=generatorUrl();
   if(url.endsWith("/images/generations")) return url.substring(0,url.length()-"generations".length())+"edits";
   throw new IllegalArgumentException("请配置参考图编辑接口地址");
 }
 private String endpoint(String explicit,String path) {
   if(!explicit.isBlank()) return explicit;
   if(baseUrl.isBlank()) return "";
   String root=baseUrl.replaceAll("/+$", "");
   return root+(root.endsWith("/v1")?"":"/v1")+path;
 }
 public boolean textConfigured() {return !textUrl().isBlank() && text.credentialsConfigured();}
 public boolean generatorConfigured() {return !generatorUrl().isBlank() && generator.credentialsConfigured();}
 public enum Protocol {OPENAI, BRIDGE}
 @Getter @Setter public static class Generator extends Endpoint {
   private Protocol protocol=Protocol.OPENAI;
   private String editsUrl="";
   private String quality="";
   private String responseFormat="";
   private List<String> downloadAllowedOrigins=List.of();
 }
 @Getter @Setter public static class Endpoint {
   private String url="";
   private String apiKey="";
   private String model="";
   private Double temperature;
   private Integer maxTokens;
   private int timeoutSeconds=60;
   public boolean credentialsConfigured() {return !apiKey.isBlank() && !model.isBlank();}
 }
}
