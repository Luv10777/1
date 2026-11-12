package com.wuyao.growth.asset;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 手机拍的 JPEG 常把像素横着存，再用 EXIF Orientation 标记「显示时请旋转」。
 * 浏览器会照做，但图片/视频模型拿到链接后大多不理会这个标记，看到的是躺倒的画面。
 * 这里在上传确认时把像素真正转正，之后所有下游拿到的都是正的。
 */
final class ImageOrientation {

    private static final float JPEG_QUALITY = 0.92f;

    private ImageOrientation() {
    }

    /** 返回 EXIF Orientation（1–8）；不是 JPEG、没有标记或内容残缺时一律返回 1（无需处理）。 */
    static int read(byte[] data) {
        if (data == null || data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) return 1;
        int pos = 2;
        while (pos + 4 <= data.length) {
            if ((data[pos] & 0xFF) != 0xFF) return 1;
            int marker = data[pos + 1] & 0xFF;
            if (marker == 0xFF) { // 填充字节
                pos++;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) return 1; // 进入图像数据，之后不会再有 EXIF
            int length = u16(data, pos + 2, true);
            if (length < 2 || pos + 2 + length > data.length) return 1;
            if (marker == 0xE1 && length >= 8 && isExif(data, pos + 4)) {
                return fromTiff(data, pos + 10, pos + 2 + length);
            }
            pos += 2 + length;
        }
        return 1;
    }

    private static boolean isExif(byte[] d, int at) {
        return at + 6 <= d.length && d[at] == 'E' && d[at + 1] == 'x' && d[at + 2] == 'i' && d[at + 3] == 'f'
                && d[at + 4] == 0 && d[at + 5] == 0;
    }

    private static int fromTiff(byte[] d, int tiff, int end) {
        if (tiff + 8 > end) return 1;
        boolean big;
        if (d[tiff] == 'M' && d[tiff + 1] == 'M') big = true;
        else if (d[tiff] == 'I' && d[tiff + 1] == 'I') big = false;
        else return 1;
        if (u16(d, tiff + 2, big) != 42) return 1;
        long ifd = tiff + u32(d, tiff + 4, big);
        if (ifd + 2 > end) return 1;
        int count = u16(d, (int) ifd, big);
        for (int i = 0; i < count; i++) {
            long entry = ifd + 2 + 12L * i;
            if (entry + 12 > end) return 1;
            if (u16(d, (int) entry, big) == 0x0112) {
                int value = u16(d, (int) entry + 8, big);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] d, int at, boolean big) {
        int a = d[at] & 0xFF, b = d[at + 1] & 0xFF;
        return big ? (a << 8) | b : (b << 8) | a;
    }

    private static long u32(byte[] d, int at, boolean big) {
        long a = d[at] & 0xFF, b = d[at + 1] & 0xFF, c = d[at + 2] & 0xFF, e = d[at + 3] & 0xFF;
        return big ? (a << 24) | (b << 16) | (c << 8) | e : (e << 24) | (c << 16) | (b << 8) | a;
    }

    /** 按 orientation 把像素摆正，返回一张不再需要标记的 RGB 图。 */
    static BufferedImage upright(BufferedImage source, int orientation) {
        int w = source.getWidth(), h = source.getHeight();
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // 水平镜像
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // 旋转 180°
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // 垂直镜像
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // 沿主对角线翻转
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // 顺时针 90°（竖拍最常见）
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // 沿副对角线翻转
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);   // 逆时针 90°
            default -> new AffineTransform();
        };
        boolean swap = orientation >= 5 && orientation <= 8;
        var target = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(source, transform, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    static byte[] encodeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        var out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
