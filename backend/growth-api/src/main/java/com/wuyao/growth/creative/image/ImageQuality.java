package com.wuyao.growth.creative.image;

import com.wuyao.growth.common.web.*;
import com.wuyao.growth.common.gateway.ImageModelProperties;
import java.util.Map;

public final class ImageQuality {
    private ImageQuality() {}
    public static boolean usesGptImage2(ImageModelProperties config) {
        String model=config.getGenerator().getModel();
        return config.getGenerator().getProtocol()==ImageModelProperties.Protocol.OPENAI
            && (model.equals("gpt-image-2") || model.matches("gpt-image-2([.-].+)?"));
    }
    public static ImageDtos.Dimensions modelDimensions(String quality,String ratio,ImageModelProperties config) {
        var target=dimensions(quality,ratio);
        if(!usesGptImage2(config)) return target;
        String[] parts=ratio.split(":");
        int x=Integer.parseInt(parts[0])*16,y=Integer.parseInt(parts[1])*16;
        int scale=(int)Math.ceil(Math.max((double)target.width()/x,(double)target.height()/y));
        while((long)x*y*scale*scale<655_360) scale++;
        int width=x*scale,height=y*scale;
        if(width>3840 || height>3840 || (long)width*height>8_294_400)
            throw BizException.of(ErrorCode.IMAGE_QUALITY_UNSUPPORTED,"当前模型的 4K 支持 16:9 横屏或 9:16 竖屏，请调整比例");
        return new ImageDtos.Dimensions(width,height);
    }
    public static boolean supportsOutput(String quality,String ratio,ImageModelProperties config) {
        try {
            var size=modelDimensions(quality,ratio,config);
            return config.getMaxOutputPixels()<=0 || (long)size.width()*size.height()<=config.getMaxOutputPixels();
        } catch(BizException e) {
            return false;
        }
    }
    public static String highestQuality(String ratio, ImageModelProperties config) {
        return Map.of("4K", 4, "1080P", 3, "720P", 2, "480P", 1).entrySet().stream()
            .sorted((a,b) -> Integer.compare(b.getValue(), a.getValue()))
            .map(Map.Entry::getKey)
            .filter(config.getQualities()::contains)
            .filter(quality -> supportsOutput(quality, ratio, config))
            .findFirst()
            .orElseThrow(() -> BizException.of(ErrorCode.IMAGE_QUALITY_UNSUPPORTED,
                "当前图片模型不支持该比例的输出"));
    }
    // P tiers use the short edge; 4K uses a 3840px long edge, including square outputs.
    public static ImageDtos.Dimensions dimensions(String quality, String ratio) {
        if (!ratio.matches("1:1|3:4|4:3|9:16|16:9|2:3|3:2"))
            throw BizException.of(ErrorCode.BAD_REQUEST, "不支持的图片比例");
        String[] parts = ratio.split(":");
        int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]);
        Integer base = Map.of("480P",480,"720P",720,"1080P",1080,"4K",3840).get(quality);
        if (base == null) throw BizException.of(ErrorCode.BAD_REQUEST, "不支持的画质");
        double scale = (double) base / ("4K".equals(quality) ? Math.max(x,y) : Math.min(x,y));
        return new ImageDtos.Dimensions((int)Math.round(x*scale), (int)Math.round(y*scale));
    }
}
