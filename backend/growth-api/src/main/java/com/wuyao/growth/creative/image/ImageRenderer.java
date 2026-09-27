package com.wuyao.growth.creative.image;
import com.wuyao.growth.common.gateway.ImageModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;

@Component @RequiredArgsConstructor
public class ImageRenderer {
 private final ImageModelProperties config;
 public BufferedImage normalize(BufferedImage source,ImageDtos.Dimensions nativeSize,ImageDtos.Dimensions outputSize) {
   if(!ImageQuality.usesGptImage2(config)
       && (source.getWidth()!=nativeSize.width() || source.getHeight()!=nativeSize.height()))
      throw new IllegalArgumentException("模型返回尺寸与请求不符：实际 "+source.getWidth()+"×"+source.getHeight()
        +"，请求 "+nativeSize.width()+"×"+nativeSize.height()+"，请检查中转站尺寸支持");
   if(source.getWidth()<outputSize.width() || source.getHeight()<outputSize.height())
     throw new IllegalArgumentException("模型实际返回 "+source.getWidth()+"×"+source.getHeight()
       +"，与请求不符：低于所选输出 "+outputSize.width()+"×"+outputSize.height()+"，不允许把低分辨率图片放大为高清");
   double ratioError=Math.abs((double)source.getWidth()*nativeSize.height()
       -(double)source.getHeight()*nativeSize.width())/((double)source.getHeight()*nativeSize.width());
   if(ratioError>0.001)
     throw new IllegalArgumentException("模型返回比例与请求不符：请求 "+nativeSize.width()+"×"+nativeSize.height()
       +"，实际 "+source.getWidth()+"×"+source.getHeight()+"，请检查中转站尺寸支持");
   if(source.getWidth()==outputSize.width() && source.getHeight()==outputSize.height()) return source;
   var out=new BufferedImage(outputSize.width(),outputSize.height(),BufferedImage.TYPE_INT_ARGB);
   var g=out.createGraphics();
   try {
     g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
     g.drawImage(source,0,0,outputSize.width(),outputSize.height(),null);
   } finally {g.dispose();}
   return out;
 }
 public boolean matchesAspect(BufferedImage source, ImageDtos.Dimensions requested) {
   double ratioError=Math.abs((double)source.getWidth()*requested.height()
     -(double)source.getHeight()*requested.width())/((double)source.getHeight()*requested.width());
   return ratioError<=0.001;
 }
 public BufferedImage decode(byte[] bytes) {
   try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
     var readers=ImageIO.getImageReaders(stream);
     if(!readers.hasNext()) throw new IllegalArgumentException("图片必须是可读取的 PNG 或 JPG");
     var reader=readers.next();
     try {
       if(!java.util.Set.of("png","jpeg","jpg").contains(reader.getFormatName().toLowerCase(java.util.Locale.ROOT)))
         throw new IllegalArgumentException("仅支持 JPG 或 PNG 图片");
       reader.setInput(stream);
       if((long)reader.getWidth(0)*reader.getHeight(0)>32_000_000L)
         throw new IllegalArgumentException("图片像素超过 3200 万，请缩小后重试");
       return reader.read(0);
     } finally { reader.dispose(); }
   } catch(IOException e) { throw new IllegalArgumentException("无法读取图片"); }
 }
 public byte[] render(byte[] background, ImageDtos.Dimensions size, ImageDtos.Spec spec) {
   BufferedImage source=decode(background);
   if(source.getWidth()!=size.width() || source.getHeight()!=size.height())
     throw new IllegalArgumentException("模型返回尺寸与所选画质不符，未将低分辨率图片标为高清");
   return png(source);
 }
 public byte[] png(BufferedImage image) {
   try {var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
   catch(IOException e) {throw new IllegalStateException("图片编码失败");}
 }
}
