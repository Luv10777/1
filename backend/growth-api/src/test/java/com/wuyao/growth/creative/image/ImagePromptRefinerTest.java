package com.wuyao.growth.creative.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.gateway.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImagePromptRefinerTest {
 private final AiGateway gateway=mock(AiGateway.class);
 private final ImageModelProperties config=new ImageModelProperties();
 private final ImagePromptRefiner refiner=new ImagePromptRefiner(gateway,new ObjectMapper(),config);
 private final String raw="Purpose: poster\nHeadline: 一方茶5周年\nCaption: 欢迎到店";
 private final String refined="Subject: 一方茶5周年, 欢迎到店; Lighting: studio lighting; Color: warm palette; Composition: asymmetric; Depth: deep focus; Quality: sharp focus, 8K; Avoid: blurry, watermark";
 @Test void refinesWithTenantAndStableIdentityAndRecordsUsage() {
   when(gateway.configured(ModelAlias.TEXT_REFINER)).thenReturn(true);
   when(gateway.invokeReal(any())).thenReturn(new ProviderResult(true,"IMAGE_HTTP",null,
     Map.of("prompt",refined,"_model","gpt-4o","_usage",Map.of("total_tokens",123)),null,null));
   var first=refiner.refineWithTrace(raw,"POSTER","朋友圈",7L);
   refiner.refine(raw,"POSTER","朋友圈",7L);
   var calls=ArgumentCaptor.forClass(ProviderRequest.class);verify(gateway,times(2)).invokeReal(calls.capture());
   assertThat(calls.getValue().alias()).isEqualTo(ModelAlias.TEXT_REFINER);
   assertThat(calls.getValue().tenantId()).isEqualTo(7L);
   assertThat(calls.getAllValues().getFirst().idempotencyKey()).isEqualTo(calls.getValue().idempotencyKey());
   assertThat(first.status()).isEqualTo("REFINED");assertThat(first.model()).isEqualTo("gpt-4o");
   assertThat(first.usage()).containsEntry("total_tokens",123);
   assertThat(first.prompt()).contains("一方茶5周年","欢迎到店","Avoid:");
 }
 @Test void timeoutAndInvalidOrTranslatedCopyFallBackToRawPrompt() {
   when(gateway.configured(ModelAlias.TEXT_REFINER)).thenReturn(true);
   when(gateway.invokeReal(any())).thenThrow(new IllegalStateException("secret provider detail"));
   assertThat(refiner.refineWithTrace(raw,"POSTER","朋友圈",7L).status()).isEqualTo("FAILED");
   doReturn(new ProviderResult(true,"TEST",null,Map.of("prompt",refined.replace("一方茶5周年","5th anniversary")),null,null)).when(gateway).invokeReal(any());
   var result=refiner.refineWithTrace(raw,"POSTER","朋友圈",7L);
   assertThat(result.status()).isEqualTo("INVALID_OUTPUT");assertThat(result.prompt()).startsWith(raw).contains("Avoid:");
 }
 @Test void disabledOrUnconfiguredRefinementDoesNotMakePaidCalls() {
   assertThat(refiner.refineWithTrace(raw,"POSTER","朋友圈",7L).status()).isEqualTo("NOT_CONFIGURED");
   config.getRefiner().setEnabled(false);
   assertThat(refiner.refineWithTrace(raw,"POSTER","朋友圈",7L).status()).isEqualTo("DISABLED");
   verify(gateway,never()).invokeReal(any());
   assertThatThrownBy(()->refiner.refine(raw,"POSTER","朋友圈",null)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void templatesAndFallbackRestrictionsAreWorkflowSpecific() {
   assertThat(ImagePromptRefiner.system("POSTER")).contains("完整图文设计").doesNotContain("产品套图的商业摄影");
   assertThat(ImagePromptRefiner.system("PRODUCT_SET")).contains("商业摄影").doesNotContain("海报视觉规划");
   var product=refiner.refineWithTrace("Subject: 商品; Camera: close-up; Lighting: soft", "PRODUCT_SET", "美团商品图", 7L);
   assertThat(product.status()).isEqualTo("NOT_CONFIGURED");
   assertThat(product.prompt()).contains("deformed packaging").doesNotContain("generic template layout");
   assertThat(product.version()).isEqualTo(ImagePromptRefiner.PRODUCT_SET_VERSION);
 }
 @Test void refinementDoesNotDuplicateWorkflowAvoidRules() {
   when(gateway.configured(ModelAlias.TEXT_REFINER)).thenReturn(true);
   String avoid="Avoid: illegible typography, misspelled Chinese text, broken characters, text behind objects, text in blur, excessive decoration, weak hierarchy, crowded composition, generic template layout, random slogans, random price or date, watermark.";
   when(gateway.invokeReal(any())).thenReturn(new ProviderResult(true,"TEST",null,
     Map.of("prompt",refined+"\n"+avoid,"_model","test-model"),null,null));
   var result=refiner.refineWithTrace(raw,"POSTER","朋友圈",7L);
   assertThat(result.prompt()).containsOnlyOnce(avoid);
 }
}
