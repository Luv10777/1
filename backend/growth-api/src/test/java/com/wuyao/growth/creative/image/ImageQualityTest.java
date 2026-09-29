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
 @Test void fourKRejectsSquareAndSupportsLandscapeAndPortrait() {
   assertThat(ImageQuality.modelDimensions("4K","16:9",config())).isEqualTo(new ImageDtos.Dimensions(3840,2160));
   assertThat(ImageQuality.modelDimensions("4K","9:16",config())).isEqualTo(new ImageDtos.Dimensions(2160,3840));
   for(String ratio:List.of("1:1","3:4","4:3","2:3","3:2"))
     assertThatThrownBy(()->ImageQuality.modelDimensions("4K",ratio,config())).hasMessageContaining("该画质");
 }
 @Test void requestsHighestSupportedQualityForPortraitPoster() {
   assertThat(ImageQuality.highestQuality("3:4",config())).isEqualTo("2K");
   assertThat(ImageQuality.modelDimensions("2K","3:4",config()))
     .isEqualTo(new ImageDtos.Dimensions(1536,2048));
 }
}
