package com.wuyao.growth.video;

import java.util.List;
import java.util.Map;

final class VideoCapabilities {
    private VideoCapabilities() {}

    private static final Map<String, VideoDtos.Capability> MODELS = Map.of(
            "SEEDANCE_2_5", new VideoDtos.Capability("SEEDANCE_2_5", "Seedance 2.5", 30,
                    List.of("480p", "720p", "1080p"), new VideoDtos.MaterialLimits(30, 10, 10)),
            "SEEDANCE_2_0", new VideoDtos.Capability("SEEDANCE_2_0", "Seedance 2.0", 15,
                    List.of("480p", "720p", "1080p", "4K"), new VideoDtos.MaterialLimits(9, 3, 3)),
            "SEEDANCE_2_0_MINI", new VideoDtos.Capability("SEEDANCE_2_0_MINI", "Seedance 2.0 Mini", 15,
                    List.of("480p", "720p"), new VideoDtos.MaterialLimits(9, 3, 3)),
            "SEEDANCE_2_0_FAST", new VideoDtos.Capability("SEEDANCE_2_0_FAST", "Seedance 2.0 Fast", 15,
                    List.of("480p", "720p"), new VideoDtos.MaterialLimits(9, 3, 3))
    );

    static List<VideoDtos.Capability> all() {
        return List.of(MODELS.get("SEEDANCE_2_5"), MODELS.get("SEEDANCE_2_0"),
                MODELS.get("SEEDANCE_2_0_MINI"), MODELS.get("SEEDANCE_2_0_FAST"));
    }

    static VideoDtos.Capability require(String model) {
        VideoDtos.Capability capability = MODELS.get(model);
        if (capability == null) throw new IllegalArgumentException("不支持的视频模型: " + model);
        return capability;
    }

    static void validate(VideoDtos.Create request) {
        VideoDtos.Capability capability = require(request.model());
        VideoDtos.MaterialLimits limits = capability.materialLimits();
        if (request.referenceImageAssetIds().size() > limits.image()) {
            throw new IllegalArgumentException(capability.label() + "最多支持 " + limits.image() + " 张图片");
        }
        if (request.videoReferences().size() > limits.video()) {
            throw new IllegalArgumentException(capability.label() + "最多支持 " + limits.video() + " 个参考视频");
        }
        if (request.referenceAudioAssetIds().size() > limits.audio()) {
            throw new IllegalArgumentException(capability.label() + "最多支持 " + limits.audio() + " 个音频");
        }
        if (request.durationSeconds() > capability.maxDurationSeconds()) {
            throw new IllegalArgumentException(capability.label() + "最长支持 " + capability.maxDurationSeconds() + " 秒");
        }
        if (!capability.resolutions().contains(request.resolution())) {
            throw new IllegalArgumentException(capability.label() + "不支持 " + request.resolution() + " 分辨率");
        }
    }
}
