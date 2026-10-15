package com.wuyao.growth.creative.image;
import com.wuyao.growth.common.gateway.ImageModelProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ImagePlannerTest {
 private ImageDtos.Create request(int count) {
   return new ImageDtos.Create("test-request","POSTER","周末双人餐 99 元",List.of(),"3:4","2K",count,"LOCAL","POSTER","朋友圈","帮我搭配",null);
 }
 @Test void refusesInventedPricesAndWrongCounts() {
   var invented=new ImageDtos.Spec("海报","自然光","双人餐只要 69 元","");
   assertThatThrownBy(()->ImagePlanner.validate(new ImageDtos.Plan("方案","",List.of(invented)),request(1)))
     .isInstanceOf(IllegalArgumentException.class);
   var valid=new ImageDtos.Spec("海报","自然光","双人餐 99 元","");
   assertThat(ImagePlanner.validate(new ImageDtos.Plan("方案","",List.of(valid)),request(1)).items()).hasSize(1);
   assertThatThrownBy(()->ImagePlanner.validate(new ImageDtos.Plan("方案","",List.of(valid)),request(3)))
     .isInstanceOf(IllegalArgumentException.class);
 }
 @Test void clarificationCannotAlsoScheduleGeneration() {
   var spec=new ImageDtos.Spec("主图","自然光","","");
   assertThatThrownBy(()->ImagePlanner.validate(new ImageDtos.Plan("方案","哪个价格？",List.of(spec)),request(1)))
     .isInstanceOf(IllegalArgumentException.class);
   assertThat(ImagePlanner.validate(new ImageDtos.Plan("方案","哪个价格？",List.of()),request(1)).items()).isEmpty();
 }
 @Test void providerPixelsAreKeptAtReturnedDimensions() {
   var renderer=new ImageRenderer();
   var returned=new BufferedImage(1374,1145,BufferedImage.TYPE_INT_RGB);
   returned.setRGB(500,500,0xffa12345);
   var decoded=renderer.decode(renderer.png(returned));
   assertThat(decoded.getWidth()).isEqualTo(1374);
   assertThat(decoded.getHeight()).isEqualTo(1145);
   assertThat(decoded.getRGB(500,500)).isEqualTo(0xffa12345);
 }
 @Test void qualityHasConsistentOrientations() {
   assertThat(ImageQuality.dimensions("2K","3:4")).isEqualTo(new ImageDtos.Dimensions(1536,2048));
   assertThat(ImageQuality.dimensions("4K","16:9")).isEqualTo(new ImageDtos.Dimensions(3840,2160));
   assertThat(ImageQuality.dimensions("2K","9:16")).isEqualTo(new ImageDtos.Dimensions(1152,2048));
 }
 @Test void outputCapHidesProxyUnsupportedPortraitSizes() {
   var config=new ImageModelProperties();
   config.setMaxOutputPixels(1_600_000);
   assertThat(ImageQuality.supportsOutput("1K","3:4",config)).isTrue();
   assertThat(ImageQuality.supportsOutput("2K","9:16",config)).isFalse();
   assertThat(ImageQuality.supportsOutput("4K","9:16",config)).isFalse();
 }
 @Test void finishedModelImageIsNotCoveredWithProgrammaticTextBoxes() {
   var renderer=new ImageRenderer();
   var source=new BufferedImage(480,640,BufferedImage.TYPE_INT_RGB);
   var g=source.createGraphics();
   g.setColor(java.awt.Color.RED);g.fillRect(0,0,480,640);g.dispose();
   byte[] png=renderer.png(source);
   assertThat(renderer.png(renderer.decode(png))).isEqualTo(png);
 }
 @Test void imageModelReceivesExactCopyEvenForLegacyBackgroundPlans() {
   var spec=new ImageDtos.Spec("海报","no text, top 23% empty","双人餐 99 元","周末限定");
   var creation=new ImageCreation();creation.setRequest(request(1));
   creation.setPlan(new ImageDtos.Plan("方案","","清爽自然光",List.of(spec)));
   assertThat(ImageRenderHandler.imagePrompt(creation,spec))
     .contains("Headline: 双人餐 99 元","Caption: 周末限定")
     .doesNotContain("Preserve existing logos/branding");
 }
 @Test void rendererRemovesLegacyBrandingAndTextRestrictionsFromSavedPrompts() {
   var spec=new ImageDtos.Spec("商品图","移除商品 Logo，保留包装文字。\nNo added headline, caption, prices, labels, slogans or decorative typography. Preserve only authentic text printed on the referenced product and its real logo.\nEmpty text fields must not render any text.","","");
   var prompt=ImageRenderHandler.cleanLegacyPrompt(spec.prompt());
   assertThat(prompt).contains("移除商品 Logo").contains("保留包装文字")
     .doesNotContain("Preserve only authentic text","real logo","Empty text fields must not render");
 }
 @Test void plannerCannotSelectAnExistingImageForEditing() {
   var spec=new ImageDtos.Spec("海报","完整海报","标题","",123L);
   assertThatThrownBy(()->ImagePlanner.validate(new ImageDtos.Plan("方案","",List.of(spec)),request(1)))
     .isInstanceOf(IllegalArgumentException.class);
 }
 @Test void workflowsHaveIndependentPlanningAndRenderingInstructions() {
   assertThat(ImagePlanner.system("POSTER")).contains("营销海报视觉总监").doesNotContain("商品摄影总监");
   assertThat(ImagePlanner.system("PRODUCT_SET")).contains("商品摄影总监").doesNotContain("营销海报视觉总监");
   assertThat(ImagePlanner.system("POSTER")).contains("需求已在前一步理解完成","understanding.mustChange")
     .contains("用户的目标优先于默认营销方式", "understanding 明确要求修改或删除的内容除外");
   assertThat(ImagePlanner.system("POSTER"))
     .contains("用户要求无字画面、留白或只给底图时，不得自行添加海报文案",
       "用户提供的文案按明确的逐字要求呈现",
       "headline/caption 中的原文必须连续、逐字出现在 prompt 中", "严格 JSON");
   assertThat(ImagePlanner.system("PRODUCT_SET"))
     .contains("用户未指定相反方向时", "未要求文字或装饰时", "用户要求这些元素时允许", "用户要求保留和修改的商品属性")
     .contains("用户明确要求替换商品时按需求更换，其他情况下不擅自替换商品")
     .doesNotContain("WHITE_BG", "近纯白、少道具");
   var productRequest=new ImageDtos.Create("product-request","PRODUCT_SET","真实商品",List.of(),"1:1","1080P",3,"LOCAL","PRODUCT_MAIN","美团 / 菜品图","真实自然",null);
   var product=new ImageCreation();product.setRequest(productRequest);
   var spec=new ImageDtos.Spec("主图","真实商品近景","","");
   product.setPlan(new ImageDtos.Plan("方案","","Product identity: real item",List.of(spec)));
   assertThat(ImageRenderHandler.imagePrompt(product,spec)).isEqualTo("真实商品近景");
   assertThat(ImageRenderHandler.imagePrompt(product,spec))
     .doesNotContain("complete ready-to-publish poster","Generate a complete ready-to-publish design with integrated text");
   assertThat(ImagePlanner.version("POSTER")).isNotEqualTo(ImagePlanner.version("PRODUCT_SET"));
 }
 @Test void understandingRunsBeforeRefinementAndItsExplicitRemovalReachesTheImagePrompt() {
   var gateway=mock(com.wuyao.growth.common.gateway.AiGateway.class);
   var mapper=new ObjectMapper();
   var request=new ImageDtos.Create("product-request","PRODUCT_SET","把商品表面的 Logo 去掉，商品其他部分保持原样。",
     List.of(),"1:1","1K",1,"TAOBAO","PRODUCT_MAIN","淘宝 / 商品主图","真实自然",null);
   var creation=new ImageCreation();creation.setId(41L);creation.setTenantId(7L);creation.setRequest(request);
   var understanding=Map.of("summary","移除商品表面的 Logo，其余部分保留","question", "",
     "mustChange",List.of("移除商品表面的 Logo"),"mustPreserve",List.of("商品其他部分"),"mustAvoid",List.of());
   var refined=Map.of("summary","不应取代理解结果","question", "","visualDirection","符合用户修改要求的商品摄影",
     "items",List.of(Map.of("role","商品主图","prompt","移除商品表面的 Logo，保留商品原有形状、颜色和配件。",
       "headline","","caption","","mustPreserve",List.of("商品形状","颜色","配件"),"mustAvoid",List.of())));
   when(gateway.invokeReal(argThat(r->r!=null && "UNDERSTAND".equals(r.options().get("stage")))))
     .thenReturn(new com.wuyao.growth.common.gateway.ProviderResult(true,"TEST",null,understanding,null,null));
   when(gateway.invokeReal(argThat(r->r!=null && "REFINE".equals(r.options().get("stage")))))
     .thenReturn(new com.wuyao.growth.common.gateway.ProviderResult(true,"TEST",null,refined,null,null));

   var plan=new ImagePlanner(gateway,mapper).plan(creation,List.of(),Map.of());
   assertThat(plan.summary()).isEqualTo(understanding.get("summary"));
   assertThat(plan.understanding().mustChange()).containsExactly("移除商品表面的 Logo");
   assertThat(plan.items().getFirst().prompt()).contains("移除商品表面的 Logo");
   var finalPrompt=ImageRenderHandler.imagePrompt(creationWithPlan(creation,plan),plan.items().getFirst());
   assertThat(finalPrompt).contains("Must change: 移除商品表面的 Logo", "Requirement priority: apply every Must change exactly")
     .doesNotContain("preserve its real logo","preserve only authentic text");
   verify(gateway).invokeReal(argThat(r->"UNDERSTAND".equals(r.options().get("stage"))));
   verify(gateway).invokeReal(argThat(r->"REFINE".equals(r.options().get("stage"))
     && r.prompt().contains("\"mustChange\":[\"移除商品表面的 Logo\"]")));
 }
 private ImageCreation creationWithPlan(ImageCreation creation,ImageDtos.Plan plan) {
   creation.setPlan(plan);return creation;
 }
 @Test void posterHistoryIsAvailableBeforePromptRefinementStarts() {
   var gateway=mock(com.wuyao.growth.common.gateway.AiGateway.class);
   var request=new ImageDtos.Create("poster-request","POSTER","换一种海报版式，沿用原来的标题。",
     List.of(),"3:4","1K",1,"LOCAL","POSTER","门店海报","清爽",null);
   var creation=new ImageCreation();creation.setId(42L);creation.setTenantId(7L);creation.setRequest(request);
   var understanding=Map.of("summary","更换版式并保留上一版标题","question","",
     "mustChange",List.of("更换版式"),"mustPreserve",List.of("标题：周末特惠"),"mustAvoid",List.of());
   var refined=Map.of("summary","更换版式并保留上一版标题","question","","visualDirection","清爽海报新构图",
     "items",List.of(Map.of("role","门店海报","prompt","更换版式，标题保持周末特惠。",
       "headline","周末特惠","caption","到店体验","mustPreserve",List.of("标题：周末特惠"),"mustAvoid",List.of())));
   when(gateway.invokeReal(argThat(r->r!=null && "UNDERSTAND".equals(r.options().get("stage")))))
     .thenReturn(new com.wuyao.growth.common.gateway.ProviderResult(true,"TEST",null,understanding,null,null));
   when(gateway.invokeReal(argThat(r->r!=null && "REFINE".equals(r.options().get("stage")))))
     .thenReturn(new com.wuyao.growth.common.gateway.ProviderResult(true,"TEST",null,refined,null,null));
   var posterContext=Map.<String,Object>of("previousPoster",Map.of("headline","周末特惠","caption","到店体验"));

   new ImagePlanner(gateway,new ObjectMapper()).plan(creation,List.of(),posterContext);

   var calls=ArgumentCaptor.forClass(com.wuyao.growth.common.gateway.ProviderRequest.class);
   verify(gateway,org.mockito.Mockito.times(2)).invokeReal(calls.capture());
   assertThat(calls.getAllValues().get(0).options().get("stage")).isEqualTo("UNDERSTAND");
   assertThat(calls.getAllValues().get(0).prompt()).contains("\"previousPoster\"");
   assertThat(calls.getAllValues().get(1).options().get("stage")).isEqualTo("REFINE");
   assertThat(calls.getAllValues().get(1).prompt()).contains("\"mustPreserve\":[\"标题：周末特惠\"]");
 }
 @Test void legacyPlannerConfigurationKeepsTheUnifiedTextLayer() {
   var config=new ImageModelProperties();
   assertThat(config.selectedTextAlias()).isEqualTo(com.wuyao.growth.common.gateway.ModelAlias.TEXT_CREATIVE);
 }
}
