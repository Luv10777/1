package com.wuyao.growth.creative.image;

import com.wuyao.growth.common.gateway.ImageModelProperties;
import org.junit.jupiter.api.Test;
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
 @Test void requestsHighestSupportedQualityForPortraitPoster() {
   assertThat(ImageQuality.highestQuality("3:4",config())).isEqualTo("1080P");
   assertThat(ImageQuality.modelDimensions("1080P","3:4",config()))
     .isEqualTo(new ImageDtos.Dimensions(1104,1472));
 }
}
