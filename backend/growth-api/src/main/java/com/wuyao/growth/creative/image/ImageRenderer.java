package com.wuyao.growth.creative.image;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

@Component
public class ImageRenderer {
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
 public byte[] png(BufferedImage image) {
   try {var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
   catch(IOException e) {throw new IllegalStateException("图片编码失败");}
 }
}
