package com.wuyao.growth.creative.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Small perceptual fingerprint for finding near-identical reference and poster images. */
public final class ImageFingerprint {
 private ImageFingerprint() {}

 public static String of(BufferedImage source) {
   var scaled=new BufferedImage(9,8,BufferedImage.TYPE_INT_RGB);
   Graphics2D g=scaled.createGraphics();
   try {
     g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
     g.drawImage(source,0,0,9,8,null);
   } finally {g.dispose();}
   long bits=0;
   for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
     bits<<=1;
     if(luma(scaled.getRGB(x,y))>luma(scaled.getRGB(x+1,y))) bits|=1;
   }
   return "%016x".formatted(bits);
 }

 public static int distance(String a,String b) {
   return Long.bitCount(Long.parseUnsignedLong(a,16)^Long.parseUnsignedLong(b,16));
 }

 private static int luma(int rgb) {
   return 299*((rgb>>>16)&255)+587*((rgb>>>8)&255)+114*(rgb&255);
 }
}
