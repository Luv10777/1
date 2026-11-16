package com.wuyao.growth.video.plan;

import com.wuyao.growth.common.web.BizException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class VideoPlanConstraintsTest {
    @Test void campaignDaysDoNotOverrideTheExplicitTwentySecondVideo() {
        var request = new VideoPlanDtos.Create("duration-test", List.of(), false, "高级质感",
                "这个是双十一充值活动，开展10天的充值活动，为我这个充值活动生成20s的宣传推广视频",
                null, "local_life", 10, 30, "9:16", null);
        assertThat(VideoPlanConstraints.resolve(request)).containsEntry("durationSeconds", 20).containsEntry("ratio", "9:16");
        assertThat(VideoPlanConstraints.explicitDuration("活动开展10天")).isNull();
        assertThat(VideoPlanConstraints.explicitDuration("生成20秒的推广视频")).isEqualTo(20);
        assertThat(VideoPlanConstraints.explicitDuration("20 seconds promo")).isEqualTo(20);
        assertThat(VideoPlanConstraints.explicitDuration("每镜3秒，成片时长20秒")).isEqualTo(20);
    }

    @Test void unsupportedDurationIsRejectedBeforeCreatingAPlan() {
        var request = new VideoPlanDtos.Create("duration-test", List.of(), false, null, "20s推广视频",
                null, null, 10, 15, "auto", null);
        assertThatThrownBy(() -> VideoPlanConstraints.resolve(request)).isInstanceOf(BizException.class)
                .hasMessageContaining("20 秒").hasMessageContaining("15 秒");
    }

    @Test void newestIdeaOverridesAnOlderBriefAndOtherwiseKeepsTheWorkbenchDuration() {
        var request = new VideoPlanDtos.Create("duration-test", List.of(), false, null, "改成20s",
                null, null, 10, 30, "auto", "原来是15秒，活动10天");
        assertThat(VideoPlanConstraints.resolve(request)).containsEntry("durationSeconds", 20).containsKey("sourceBrief");
        var settings = new VideoPlanDtos.Create("duration-test", List.of(), false, null, "推广活动10天",
                null, null, 25, 30, "auto", null);
        assertThat(VideoPlanConstraints.resolve(settings)).containsEntry("durationSeconds", 25);
    }
}
