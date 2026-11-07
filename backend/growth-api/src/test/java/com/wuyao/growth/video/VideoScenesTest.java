package com.wuyao.growth.video;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoScenesTest {
    private static VideoDtos.Create request(String prompt, String sceneKey, Long planId) {
        return new VideoDtos.Create("video-key-1", prompt, List.of(), null, "SEEDANCE_2_5", "9:16", 10, "720p",
                null, null, null, null, sceneKey, planId);
    }

    @Test void viewsStartWithFreeCreationAndNeverExposeTheGuide() {
        var views = VideoScenes.views();
        assertThat(views.getFirst().key()).isEqualTo(VideoScenes.FREE);
        assertThat(views).extracting(VideoScenes.View::key)
                .containsExactly("free", "ecommerce", "local_life", "event", "brand", "drama");
        assertThat(VideoScenes.View.class.getRecordComponents()).extracting(c -> c.getName()).doesNotContain("guide");
    }

    @Test void userPromptStaysFirstAndUntouchedWithTheSceneAppendedAsAYieldingReference() {
        String prompt = VideoScenes.providerPrompt(request("先拍锅底沸腾，最后老板说话", "local_life", null));
        assertThat(prompt).startsWith("先拍锅底沸腾，最后老板说话\n\n【创作场景：本地生活探店】")
                .contains("只用来补充上面没写到的部分", "一律以上面的描述为准", "门头");
    }

    @Test void blankPromptGetsTheSceneOnItsOwn() {
        assertThat(VideoScenes.providerPrompt(request("", "ecommerce", null))).startsWith("【创作场景：电商带货】请按参考素材来拍");
    }

    @Test void freeUnknownOrAdoptedPlanPromptsAreSentAsIs() {
        assertThat(VideoScenes.providerPrompt(request("原文", null, null))).isEqualTo("原文");
        assertThat(VideoScenes.providerPrompt(request("原文", "free", null))).isEqualTo("原文");
        assertThat(VideoScenes.providerPrompt(request("原文", "no_such_scene", null))).isEqualTo("原文");
        assertThat(VideoScenes.providerPrompt(request("方案提示词", "drama", 42L))).isEqualTo("方案提示词");
    }

    @Test void normalizeKeepsOnlyGuidedScenes() {
        assertThat(VideoScenes.normalize("brand")).isEqualTo("brand");
        assertThat(VideoScenes.normalize("free")).isNull();
        assertThat(VideoScenes.normalize("nope")).isNull();
        assertThat(VideoScenes.normalize(null)).isNull();
    }
}
