package com.wuyao.growth.creative.image;
import com.wuyao.growth.common.storage.ObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;

@Slf4j
@Component
public class ImageRenderer {
 /** 列表、侧栏、对话里用的小图：长边不超过 640 像素的 JPEG，放在原图旁边。 */
 public static final int THUMBNAIL_EDGE=640;
 public static String thumbnailKey(String outputKey) {return outputKey==null?null:outputKey+".thumb.jpg";}
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
 /** 缩到长边 640 像素、透明处垫白、质量 0.82 的 JPEG；原图就比这小时不放大。 */
 public byte[] thumbnail(BufferedImage image) {
   double scale=Math.min(1d,(double)THUMBNAIL_EDGE/Math.max(image.getWidth(),image.getHeight()));
   int width=Math.max(1,(int)Math.round(image.getWidth()*scale)),height=Math.max(1,(int)Math.round(image.getHeight()*scale));
   var small=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
   var g=small.createGraphics();
   try {
     g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
     g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
     g.setColor(Color.WHITE);g.fillRect(0,0,width,height);
     g.drawImage(image,0,0,width,height,null);
   } finally {g.dispose();}
   var writer=ImageIO.getImageWritersByFormatName("jpeg").next();
   try(var out=new ByteArrayOutputStream();var stream=ImageIO.createImageOutputStream(out)) {
     var param=writer.getDefaultWriteParam();
     param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);param.setCompressionQuality(0.82f);
     writer.setOutput(stream);writer.write(null,new IIOImage(small,null,null),param);stream.flush();
     return out.toByteArray();
   } catch(IOException e) {throw new IllegalStateException("缩略图编码失败");}
   finally {writer.dispose();}
 }
 /** 尽力而为：小图写不进去只记日志，不影响原图保存和任务结果；重复执行会覆盖同一个键。 */
 public void storeThumbnail(ObjectStorage storage,String outputKey,BufferedImage image) {
   try {storage.put(thumbnailKey(outputKey),thumbnail(image),"image/jpeg");}
   catch(RuntimeException e) {log.warn("缩略图保存失败，列表会退回原图: key={} cause={}",outputKey,e.getClass().getSimpleName());}
 }
}
