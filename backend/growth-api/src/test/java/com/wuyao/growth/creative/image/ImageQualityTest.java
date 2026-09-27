package com.wuyao.growth.creative.image;

import com.wuyao.growth.common.gateway.ImageModelProperties;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ImageQualityTest {
 private ImageModelProperties config() {
   var p=new ImageModelProperties();p.getGenerator().setModel("gpt-image-2");return p;
 }
 @Test void gptImage2RequestsMeetStepMinimumAndMaximumWithoutUpscaling() {
   for(var quality:List.of("480P","720P","1080P")) for(var ratio:List.of("1:1","3:4","4:3","9:16","16:9","2:3","3:2")) {
     var nativeSize=ImageQuality.modelDimensions(quality,ratio,config());var target=ImageQuality.dimensions(quality,ratio);
     assertThat(nativeSize.width()%16).isZero();assertThat(nativeSize.height()%16).isZero();
     assertThat(nativeSize.width()).isGreaterThanOrEqualTo(target.width());assertThat(nativeSize.height()).isGreaterThanOrEqualTo(target.height());
     assertThat((long)nativeSize.width()*nativeSize.height()).isBetween(655_360L,8_294_400L);
   }
   assertThat(ImageQuality.modelDimensions("1080P","3:4",config())).isEqualTo(new ImageDtos.Dimensions(1104,1472));
 }
 @Test void gptImage25IsRecognizedAsGptImageFamily() {
   var p=config();p.getGenerator().setModel("gpt-image-2.5-flare");
   assertThat(ImageQuality.usesGptImage2(p)).isTrue();
   assertThat(ImageQuality.modelDimensions("4K","16:9",p)).isEqualTo(new ImageDtos.Dimensions(3840,2160));
 }
 @Test void fourKRejectsSquareAndSupportsLandscapeAndPortrait() {
   assertThat(ImageQuality.modelDimensions("4K","16:9",config())).isEqualTo(new ImageDtos.Dimensions(3840,2160));
   assertThat(ImageQuality.modelDimensions("4K","9:16",config())).isEqualTo(new ImageDtos.Dimensions(2160,3840));
   for(String ratio:List.of("1:1","3:4","4:3","2:3","3:2"))
     assertThatThrownBy(()->ImageQuality.modelDimensions("4K",ratio,config())).hasMessageContaining("4K 支持");
 }
 @Test void normalizationDownsamplesAndRejectsProviderDimensionMismatch() {
   var renderer=new ImageRenderer(config());var nativeSize=new ImageDtos.Dimensions(1104,1472);var target=new ImageDtos.Dimensions(1080,1440);
   var normalized=renderer.normalize(new BufferedImage(1104,1472,BufferedImage.TYPE_INT_RGB),nativeSize,target);
   assertThat(normalized.getWidth()).isEqualTo(1080);assertThat(normalized.getHeight()).isEqualTo(1440);
   assertThatThrownBy(()->renderer.normalize(new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB),nativeSize,target)).hasMessageContaining("请求不符");
   assertThatThrownBy(()->renderer.normalize(normalized,target,nativeSize)).hasMessageContaining("不允许");
 }
 @Test void relayMayReturnDifferentResolutionWithSameAspectRatio() {
   var renderer=new ImageRenderer(config());
   var returned=new BufferedImage(1086,1448,BufferedImage.TYPE_INT_RGB);
   var target=new ImageDtos.Dimensions(480,640);
   var normalized=renderer.normalize(returned,new ImageDtos.Dimensions(720,960),target);
   assertThat(normalized.getWidth()).isEqualTo(480);
   assertThat(normalized.getHeight()).isEqualTo(640);
   // Even if requested and output sizes match, the returned image must be resized.
   assertThat(renderer.normalize(returned,target,target).getWidth()).isEqualTo(480);
   assertThatThrownBy(()->renderer.normalize(returned,new ImageDtos.Dimensions(2160,2880),
     new ImageDtos.Dimensions(2160,2880))).hasMessageContaining("不允许");
   var strict=new ImageRenderer(new ImageModelProperties());
   assertThatThrownBy(()->strict.normalize(returned,new ImageDtos.Dimensions(720,960),target))
     .hasMessageContaining("请求不符");
 }
}
