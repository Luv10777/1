package com.wuyao.growth.asset;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.iam.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetImageOrientationTest {

    private static final int RED = 0xFF0000, GREEN = 0x00FF00, BLUE = 0x0000FF, WHITE = 0xFFFFFF;

    /** 2×2 四角异色：左上红、右上绿、左下蓝、右下白。转正后四角落在哪里一目了然。 */
    private static BufferedImage corners() {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, RED);
        image.setRGB(1, 0, GREEN);
        image.setRGB(0, 1, BLUE);
        image.setRGB(1, 1, WHITE);
        return image;
    }

    @ParameterizedTest
    // 期望的 左上,右上,左下,右下
    @CsvSource({
            "1, RED,GREEN,BLUE,WHITE",
            "2, GREEN,RED,WHITE,BLUE",
            "3, WHITE,BLUE,GREEN,RED",
            "4, BLUE,WHITE,RED,GREEN",
            "5, RED,BLUE,GREEN,WHITE",
            "6, BLUE,RED,WHITE,GREEN",
            "7, WHITE,GREEN,BLUE,RED",
            "8, GREEN,WHITE,RED,BLUE"})
    void everyOrientationLandsEachCornerWhereAViewerWouldSeeIt(int orientation, String tl, String tr, String bl, String br) {
        BufferedImage out = ImageOrientation.upright(corners(), orientation);
        assertThat(new int[]{rgb(out, 0, 0), rgb(out, 1, 0), rgb(out, 0, 1), rgb(out, 1, 1)})
                .containsExactly(color(tl), color(tr), color(bl), color(br));
    }

    @Test
    void aPortraitShotStoredSidewaysBecomesPortraitPixels() {
        BufferedImage out = ImageOrientation.upright(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), 6);
        assertThat(out.getWidth()).isEqualTo(30);
        assertThat(out.getHeight()).isEqualTo(40);
    }

    @ParameterizedTest
    @CsvSource({"true,6", "false,6", "true,3", "false,8"})
    void readsTheTagInEitherByteOrder(boolean bigEndian, int orientation) throws Exception {
        assertThat(ImageOrientation.read(jpegWithOrientation(40, 30, orientation, bigEndian))).isEqualTo(orientation);
    }

    @Test
    void anythingWithoutAUsableTagNeedsNoWork() throws Exception {
        assertThat(ImageOrientation.read(plainJpeg(40, 30))).isEqualTo(1);
        assertThat(ImageOrientation.read(new byte[]{(byte) 0x89, 'P', 'N', 'G'})).isEqualTo(1);
        assertThat(ImageOrientation.read(new byte[0])).isEqualTo(1);
        byte[] truncated = java.util.Arrays.copyOf(jpegWithOrientation(40, 30, 6, true), 20);
        assertThat(ImageOrientation.read(truncated)).isEqualTo(1);
        assertThat(ImageOrientation.read(jpegWithOrientation(40, 30, 9, true))).isEqualTo(1);
    }

    @Test
    void confirmingASidewaysPhotoStoresUprightPixelsAndRecordsTheirSize() throws Exception {
        // 上半红、下半蓝的横图，标记为「顺时针转 90° 显示」→ 转正后应是 30×40，左蓝右红。
        var source = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        var g = source.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 40, 15);
        g.setColor(Color.BLUE);
        g.fillRect(0, 15, 40, 15);
        g.dispose();
        byte[] upload = withOrientation(jpeg(source), 6, true);

        var fixture = new Fixture(upload);
        var view = fixture.confirm();

        var stored = ArgumentCaptor.forClass(byte[].class);
        verify(fixture.storage).put(eq("t1/image/a"), stored.capture(), eq("image/jpeg"));
        BufferedImage saved = ImageIO.read(new ByteArrayInputStream(stored.getValue()));
        assertThat(saved.getWidth()).isEqualTo(30);
        assertThat(saved.getHeight()).isEqualTo(40);
        assertThat(ImageOrientation.read(stored.getValue())).isEqualTo(1);
        assertThat(new Color(saved.getRGB(4, 20)).getBlue()).isGreaterThan(200);
        assertThat(new Color(saved.getRGB(4, 20)).getRed()).isLessThan(60);
        assertThat(new Color(saved.getRGB(25, 20)).getRed()).isGreaterThan(200);
        assertThat(new Color(saved.getRGB(25, 20)).getBlue()).isLessThan(60);
        assertThat(fixture.asset.getWidth()).isEqualTo(30);
        assertThat(fixture.asset.getHeight()).isEqualTo(40);
        assertThat(view.sizeBytes()).isEqualTo(stored.getValue().length);
        assertThat(fixture.asset.getSizeBytes()).isEqualTo(stored.getValue().length);
        assertThat(fixture.asset.getMimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void anAlreadyUprightPhotoIsLeftUntouched() throws Exception {
        byte[] upload = plainJpeg(30, 40);
        var fixture = new Fixture(upload);

        fixture.confirm();

        verify(fixture.storage, never()).put(anyString(), any(byte[].class), anyString());
        assertThat(fixture.asset.getWidth()).isEqualTo(30);
        assertThat(fixture.asset.getSizeBytes()).isEqualTo(upload.length);
    }

    private static final class Fixture {
        final AssetRepository repository = mock(AssetRepository.class);
        final ObjectStorage storage = mock(ObjectStorage.class);
        final Asset asset = new Asset();
        final AssetService service = new AssetService(repository, storage, mock(TaskService.class),
                mock(TenantRepository.class), mock(TransactionTemplate.class));

        Fixture(byte[] upload) {
            ReflectionTestUtils.setField(service, "maxImagePixels", 50_000_000L);
            asset.setId(7L);
            asset.setTenantId(1L);
            asset.setName("door.jpg");
            asset.setType("IMAGE");
            asset.setStatus("PENDING");
            asset.setStorageKey("t1/image/a");
            when(repository.findForUpdate(7L)).thenReturn(Optional.of(asset));
            when(storage.stat("t1/image/a"))
                    .thenReturn(Optional.of(new ObjectStorage.StoredObject(upload.length, "image/jpeg")));
            when(storage.read(eq("t1/image/a"), anyInt())).thenReturn(upload);
        }

        AssetDtos.AssetView confirm() {
            return service.confirmUpload(7L, new AssetDtos.ConfirmRequest(null, null), 3L);
        }
    }

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    private static int color(String name) {
        return switch (name) {
            case "RED" -> RED;
            case "GREEN" -> GREEN;
            case "BLUE" -> BLUE;
            default -> WHITE;
        };
    }

    private static byte[] plainJpeg(int width, int height) throws Exception {
        return jpeg(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB));
    }

    private static byte[] jpeg(BufferedImage image) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] jpegWithOrientation(int width, int height, int orientation, boolean bigEndian) throws Exception {
        return withOrientation(plainJpeg(width, height), orientation, bigEndian);
    }

    /** 在 SOI 之后插入一个只含 Orientation 的最小 EXIF 段，等同手机相机写出的标记。 */
    private static byte[] withOrientation(byte[] jpeg, int orientation, boolean bigEndian) {
        var tiff = new ByteArrayOutputStream();
        tiff.writeBytes(bigEndian ? new byte[]{'M', 'M'} : new byte[]{'I', 'I'});
        put16(tiff, 42, bigEndian);
        put32(tiff, 8, bigEndian);
        put16(tiff, 1, bigEndian);          // 一条记录
        put16(tiff, 0x0112, bigEndian);     // Orientation
        put16(tiff, 3, bigEndian);          // SHORT
        put32(tiff, 1, bigEndian);
        put16(tiff, orientation, bigEndian);
        put16(tiff, 0, bigEndian);
        put32(tiff, 0, bigEndian);          // 没有下一个 IFD
        byte[] body = tiff.toByteArray();

        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        put16(out, 2 + 6 + body.length, true);
        out.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(body);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static void put16(ByteArrayOutputStream out, int value, boolean bigEndian) {
        if (bigEndian) {
            out.write(value >> 8);
            out.write(value);
        } else {
            out.write(value);
            out.write(value >> 8);
        }
    }

    private static void put32(ByteArrayOutputStream out, int value, boolean bigEndian) {
        if (bigEndian) {
            put16(out, value >>> 16, true);
            put16(out, value & 0xFFFF, true);
        } else {
            put16(out, value & 0xFFFF, false);
            put16(out, value >>> 16, false);
        }
    }
}
