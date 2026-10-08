package com.wuyao.growth.video.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.task.NonRetryableTaskException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VideoFrameExtractorTest {
    @TempDir Path directory;
    @Test void realFfmpegProducesBoundedFramesWithUsableTimestamps() throws Exception {
        Path video = video(12);
        var config = new VideoAnalysisProperties();
        var media = new VideoFrameExtractor(config, new ObjectMapper()).extract(video, directory);
        assertThat(media.frames()).hasSize(24);
        assertThat(media.durationMs()).isEqualTo(12000);
        assertThat(media.frames().get(23).seconds()).isEqualTo(11.5);
        var image = ImageIO.read(media.frames().getFirst().path().toFile());
        assertThat(Math.max(image.getWidth(), image.getHeight())).isLessThanOrEqualTo(1280);
        assertThat(image.getWidth()).isEqualTo(160);
        assertThat(image.getHeight()).isEqualTo(90);
    }
    @Test void longerVideosRespectTheFrameBudget() throws Exception {
        var media = new VideoFrameExtractor(new VideoAnalysisProperties(), new ObjectMapper()).extract(video(60), directory);
        assertThat(media.frames()).hasSize(48);
        assertThat(media.frames().getLast().seconds()).isLessThan(60);
    }
    @Test void overlongAndDisguisedVideosFailBeforeModelAnalysis() throws Exception {
        var extractor = new VideoFrameExtractor(new VideoAnalysisProperties(), new ObjectMapper());
        assertThatThrownBy(() -> extractor.extract(video(61), directory))
                .isInstanceOf(NonRetryableTaskException.class).hasMessageContaining("60 秒");
        Path bad = directory.resolve("fake.mp4"); java.nio.file.Files.writeString(bad, "not a video");
        assertThatThrownBy(() -> extractor.extract(bad, directory)).isInstanceOf(NonRetryableTaskException.class);
    }
    private Path video(int seconds) throws Exception {
        Path video = directory.resolve("source-" + seconds + ".mp4");
        Process process;
        try {
            process = new ProcessBuilder("ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i",
                    "color=c=red:s=160x90:r=10:d=" + seconds, "-c:v", "libx264", "-pix_fmt", "yuv420p", video.toString())
                    .redirectError(directory.resolve("generate.log").toFile()).start();
        } catch (java.io.IOException e) { assumeTrue(false, "FFmpeg not installed"); return null; }
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return video;
    }
}
