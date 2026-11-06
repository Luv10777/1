package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;

class VideoCapabilitiesTest {
    @ParameterizedTest
    @CsvSource({"SEEDANCE_2_5,30,10,10", "SEEDANCE_2_0,9,3,3", "SEEDANCE_2_0_MINI,9,3,3", "SEEDANCE_2_0_FAST,9,3,3",
            "SEEDANCE_2_5_SPECIAL,30,10,10", "SEEDANCE_2_0_SPECIAL,9,3,3"})
    void materialLimitsApplyToEveryMediaType(String model, int images, int videos, int audios) {
        assertThatCode(() -> VideoCapabilities.validate(request(model, images, videos, audios))).doesNotThrowAnyException();
        assertThatThrownBy(() -> VideoCapabilities.validate(request(model, images + 1, videos, audios)))
                .hasMessageContaining(images + " 张图片");
        assertThatThrownBy(() -> VideoCapabilities.validate(request(model, images, videos + 1, audios)))
                .hasMessageContaining(videos + " 个参考视频");
        assertThatThrownBy(() -> VideoCapabilities.validate(request(model, images, videos, audios + 1)))
                .hasMessageContaining(audios + " 个音频");
    }

    @ParameterizedTest
    @CsvSource({"SEEDANCE_2_5_SPECIAL,Seedance 2.5-特价,30", "SEEDANCE_2_0_SPECIAL,Seedance 2.0-特价,15"})
    void specialModelsOnlyOfferTheirTwoUpscaleResolutions(String model, String label, int maxSeconds) {
        var capability = VideoCapabilities.require(model);
        assertThat(capability.label()).isEqualTo(label);
        assertThat(capability.resolutions()).containsExactly("720p", "1080p");
        assertThat(capability.maxDurationSeconds()).isEqualTo(maxSeconds);
        assertThat(VideoCapabilities.all()).contains(capability);
        for (String allowed : List.of("720p", "1080p")) {
            assertThatCode(() -> VideoCapabilities.validate(new VideoDtos.Create("special-request", "展示商品", List.of(), null, model, "9:16", 5, allowed)))
                    .doesNotThrowAnyException();
        }
        for (String refused : List.of("480p", "4K")) {
            assertThatThrownBy(() -> VideoCapabilities.validate(new VideoDtos.Create("special-request", "展示商品", List.of(), null, model, "9:16", 5, refused)))
                    .hasMessageContaining("不支持 " + refused);
        }
    }

    @Test
    void legacyRequestsKeepTheirSerializedShapeAndVideoReference() throws Exception {
        var json = new ObjectMapper();
        var legacy = new VideoDtos.Create("legacy-request", "展示商品", List.of(1L), 2L, "SEEDANCE_2_0", "auto", 10, "720p");
        var serialized = json.valueToTree(legacy);
        assertThat(serialized.has("referenceVideoAssetIds")).isFalse();
        assertThat(serialized.has("referenceAudioAssetIds")).isFalse();
        var restored = json.treeToValue(serialized, VideoDtos.Create.class);
        assertThat(restored.videoReferences()).containsExactly(2L);
        assertThat(restored.referenceAudioAssetIds()).isEmpty();
        var combined = new VideoDtos.Create("combined-request", "展示商品", List.of(), 2L, "SEEDANCE_2_0", "auto", 10, "720p", null, null, List.of(2L, 3L), List.of());
        assertThat(combined.videoReferences()).containsExactly(2L, 3L);
    }

    private VideoDtos.Create request(String model, int images, int videos, int audios) {
        return new VideoDtos.Create("materials-request", "展示商品", ids(images), null, model, "auto", 10, "720p", null, null, ids(videos), ids(audios));
    }

    private List<Long> ids(int count) { return LongStream.rangeClosed(1, count).boxed().toList(); }
}
