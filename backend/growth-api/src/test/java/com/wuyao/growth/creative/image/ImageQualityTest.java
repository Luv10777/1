package com.wuyao.growth.creative.image;

import com.wuyao.growth.common.gateway.ImageModelProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ImageQualityTest {
 private ImageModelProperties config() {
   var p=new ImageModelProperties();p.getGenerator().setModel("gpt-image-2");return p;
 }
 @Test void fluQualityDimensionsMatchStandardSizes() {
   assertThat(ImageQuality.modelDimensions("1K", "1:1", config())).isEqualTo(new ImageDtos.Dimensions(1024, 1024));
   assertThat(ImageQuality.modelDimensions("1K", "3:2", config())).isEqualTo(new ImageDtos.Dimensions(1536, 1024));
   assertThat(ImageQuality.modelDimensions("2K", "16:9", config())).isEqualTo(new ImageDtos.Dimensions(2048, 1152));
   assertThat(ImageQuality.modelDimensions("2K", "9:16", config())).isEqualTo(new ImageDtos.Dimensions(1152, 2048));
 }
 @Test void gptImage25IsRecognizedAsGptImageFamily() {
   var p=config();p.getGenerator().setModel("gpt-image-2.5-flare");
   assertThat(ImageQuality.usesGptImage2(p)).isTrue();
   assertThat(ImageQuality.modelDimensions("4K","16:9",p)).isEqualTo(new ImageDtos.Dimensions(3840,2160));
 }
 @Test void gptImage25NormalizesNonMultipleOf16RatioTiers() {
   var p=config();p.getGenerator().setModel("gpt-image-2.5-flare-ab");
   assertThat(ImageQuality.modelDimensions("1K", "16:9", p))
     .isEqualTo(new ImageDtos.Dimensions(1792,1008));
   assertThat(ImageQuality.modelDimensions("2K", "2:3", p))
     .isEqualTo(new ImageDtos.Dimensions(2048,3072));
   for (String quality : List.of("1K", "2K", "4K")) {
     for (String ratio : List.of("1:1", "3:4", "4:3", "9:16", "16:9", "2:3", "3:2", "3:1")) {
       try {
         var size=ImageQuality.modelDimensions(quality,ratio,p);
         assertThat(size.width()%16).as(quality+" "+ratio).isZero();
         assertThat(size.height()%16).as(quality+" "+ratio).isZero();
         assertThat((long)size.width()*size.height()).as(quality+" "+ratio)
           .isBetween(655_360L,8_294_400L);
         assertThat(Math.max(size.width(),size.height())).as(quality+" "+ratio)
           .isLessThanOrEqualTo(3840);
       } catch (RuntimeException ignored) {
         // 4K only exposes 16:9, 9:16 and the 3:1 banner; 3:1 has no 2K size.
         if (ratio.equals("3:1")) assertThat(quality).isEqualTo("2K");
         else {
           assertThat(quality).isEqualTo("4K");
           assertThat(ratio).isNotIn("16:9", "9:16");
         }
       }
     }
   }
 }
 @Test void fourKRejectsSquareAndSupportsLandscapeAndPortrait() {
   assertThat(ImageQuality.modelDimensions("4K","16:9",config())).isEqualTo(new ImageDtos.Dimensions(3840,2160));
   assertThat(ImageQuality.modelDimensions("4K","9:16",config())).isEqualTo(new ImageDtos.Dimensions(2160,3840));
   for(String ratio:List.of("1:1","3:4","4:3","2:3","3:2"))
     assertThatThrownBy(()->ImageQuality.modelDimensions("4K",ratio,config())).hasMessageContaining("该画质");
 }
 @Test void triptychBannerIsThreeToOneAt1KAnd4KOnly() {
   var p=config();p.getGenerator().setModel("gpt-image-2.5");
   assertThat(ImageQuality.modelDimensions("1K","3:1",p)).isEqualTo(new ImageDtos.Dimensions(3072,1024));
   assertThat(ImageQuality.modelDimensions("4K","3:1",p)).isEqualTo(new ImageDtos.Dimensions(3840,1280));
   assertThatThrownBy(()->ImageQuality.dimensions("2K","3:1")).hasMessageContaining("该画质");
   assertThat(ImageQuality.supportsOutput("2K","3:1",p)).isFalse();
 }
 @Test void portraitPosterUsesTwoKDimensions() {
   assertThat(ImageQuality.modelDimensions("2K","3:4",config()))
     .isEqualTo(new ImageDtos.Dimensions(1536,2048));
 }
}
