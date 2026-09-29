package com.wuyao.growth.creative.image;

import com.wuyao.growth.common.web.*;
import com.wuyao.growth.common.gateway.ImageModelProperties;
import java.util.Map;
import java.util.Set;

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
        return target;
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
        return Map.of("4K", 4, "2K", 2, "1K", 1).entrySet().stream()
            .sorted((a,b) -> Integer.compare(b.getValue(), a.getValue()))
            .map(Map.Entry::getKey)
            .filter(config.getQualities()::contains)
            .filter(quality -> supportsOutput(quality, ratio, config))
            .findFirst()
            .orElseThrow(() -> BizException.of(ErrorCode.IMAGE_QUALITY_UNSUPPORTED,
                "当前图片模型不支持该比例的输出"));
    }
    // 1K and 2K use the short edge; 4K uses a 3840px long edge for landscape/portrait.
    public static ImageDtos.Dimensions dimensions(String quality, String ratio) {
        if (!ratio.matches("1:1|3:4|4:3|9:16|16:9|2:3|3:2"))
            throw BizException.of(ErrorCode.BAD_REQUEST, "不支持的图片比例");
        Map<String, Map<String, ImageDtos.Dimensions>> sizes = Map.of(
            "1K", Map.of("1:1", new ImageDtos.Dimensions(1024, 1024), "3:2", new ImageDtos.Dimensions(1536, 1024), "2:3", new ImageDtos.Dimensions(1024, 1536)),
            "2K", Map.of("1:1", new ImageDtos.Dimensions(2048, 2048), "16:9", new ImageDtos.Dimensions(2048, 1152), "9:16", new ImageDtos.Dimensions(1152, 2048)),
            "4K", Map.of("16:9", new ImageDtos.Dimensions(3840, 2160), "9:16", new ImageDtos.Dimensions(2160, 3840))
        );
        ImageDtos.Dimensions size = sizes.getOrDefault(quality, Map.of()).get(ratio);
        if (size == null && !"4K".equals(quality)) {
            String[] parts = ratio.split(":");
            int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]);
            int base = "1K".equals(quality) ? 1024 : 2048;
            double scale = (double) base / (Set.of("3:4", "4:3").contains(ratio) ? Math.max(x, y) : Math.min(x, y));
            size = new ImageDtos.Dimensions((int) Math.round(x * scale), (int) Math.round(y * scale));
        }
        if (size == null) throw BizException.of(ErrorCode.IMAGE_QUALITY_UNSUPPORTED,
            "该画质不支持此比例，请选择 1K/2K/4K 对应的标准比例");
        return size;
    }
}
