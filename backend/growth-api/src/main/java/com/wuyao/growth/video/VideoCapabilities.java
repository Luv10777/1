package com.wuyao.growth.video;

import java.util.List;
import java.util.Map;

final class VideoCapabilities {
    private VideoCapabilities() {}

    private static final Map<String, VideoDtos.Capability> MODELS = Map.of(
            "SEEDANCE_2_5", new VideoDtos.Capability("SEEDANCE_2_5", "Seedance 2.5", 30,
                    List.of("480p", "720p", "1080p")),
            "SEEDANCE_2_0", new VideoDtos.Capability("SEEDANCE_2_0", "Seedance 2.0", 15,
                    List.of("480p", "720p", "1080p", "4K")),
            "SEEDANCE_2_0_MINI", new VideoDtos.Capability("SEEDANCE_2_0_MINI", "Seedance 2.0 Mini", 15,
                    List.of("480p", "720p")),
            "SEEDANCE_2_0_FAST", new VideoDtos.Capability("SEEDANCE_2_0_FAST", "Seedance 2.0 Fast", 15,
                    List.of("480p", "720p"))
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
        if (request.durationSeconds() > capability.maxDurationSeconds()) {
            throw new IllegalArgumentException(capability.label() + "最长支持 " + capability.maxDurationSeconds() + " 秒");
        }
        if (!capability.resolutions().contains(request.resolution())) {
            throw new IllegalArgumentException(capability.label() + "不支持 " + request.resolution() + " 分辨率");
        }
    }
}
