package com.wuyao.growth.creative.image;

import com.wuyao.growth.common.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 生成图的小图：长边 640 的 JPEG，写在原图旁边；写失败不影响原图。 */
class ImageThumbnailTest {
    private final ImageRenderer renderer = new ImageRenderer();

    @Test
    void largeImagesShrinkToA640EdgeJpeg() {
        var source = new BufferedImage(1536, 2048, BufferedImage.TYPE_INT_ARGB);
        byte[] bytes = renderer.thumbnail(source);

        assertThat(bytes[0] & 0xff).isEqualTo(0xff);
        assertThat(bytes[1] & 0xff).isEqualTo(0xd8);
        var decoded = renderer.decode(bytes);
        assertThat(decoded.getHeight()).isEqualTo(640);
        assertThat(decoded.getWidth()).isEqualTo(480);
    }

    @Test
    void smallImagesAreNotEnlarged() {
        var decoded = renderer.decode(renderer.thumbnail(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB)));
        assertThat(decoded.getWidth()).isEqualTo(300);
        assertThat(decoded.getHeight()).isEqualTo(200);
    }

    @Test
    void thumbnailsSitNextToTheOriginal() {
        assertThat(ImageRenderer.thumbnailKey("t2/generated/8/0/background.png")).isEqualTo("t2/generated/8/0/background.png.thumb.jpg");
        assertThat(ImageRenderer.thumbnailKey(null)).isNull();
    }

    @Test
    void aFailedThumbnailWriteNeverBreaksTheSave() {
        var storage = mock(ObjectStorage.class);
        doThrow(new IllegalStateException("storage down")).when(storage).put(anyString(), any(byte[].class), anyString());

        assertThatCode(() -> renderer.storeThumbnail(storage, "t2/x/background.png", new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)))
                .doesNotThrowAnyException();
        verify(storage).put(eq("t2/x/background.png.thumb.jpg"), any(byte[].class), eq("image/jpeg"));
    }
}
