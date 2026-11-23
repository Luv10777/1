package com.wuyao.growth.asset;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 手机原图常常二三十 MB、几千万像素，超过模型和供应商能收的大小。
 * 上传时不拒绝，而是在这里缩到下游都能用的大小。
 */
final class ImageFit {

    record Decoded(BufferedImage image, boolean subsampled) {}

    private ImageFit() {
    }

    /** 解码图片；像素超过 maxPixels 时隔行隔列抽样解码，不把整张原图读进内存。不是可识别的图片时返回 null。 */
    static Decoded decode(byte[] data, long maxPixels) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                int step = 1;
                while ((long) ceilDiv(width, step) * ceilDiv(height, step) > maxPixels) step++;
                ImageReadParam param = reader.getDefaultReadParam();
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                return new Decoded(reader.read(0, param), step > 1);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 编码成不超过 maxBytes 的 JPEG，放不下就按比例缩小再编码。 */
    static Fitted jpegWithin(BufferedImage image, int maxBytes) throws IOException {
        BufferedImage current = opaque(image);
        byte[] jpeg = ImageOrientation.encodeJpeg(current);
        while (jpeg.length > maxBytes) {
            current = scaled(current, Math.sqrt((double) maxBytes / jpeg.length) * 0.9);
            jpeg = ImageOrientation.encodeJpeg(current);
        }
        return new Fitted(current, jpeg);
    }

    record Fitted(BufferedImage image, byte[] jpeg) {}

    private static int ceilDiv(int value, int step) {
        return (value + step - 1) / step;
    }

    /** JPEG 没有透明通道：透明处铺白底。 */
    private static BufferedImage opaque(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR || image.getType() == BufferedImage.TYPE_INT_RGB) return image;
        var target = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = target.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static BufferedImage scaled(BufferedImage image, double scale) {
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        var target = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }
}
