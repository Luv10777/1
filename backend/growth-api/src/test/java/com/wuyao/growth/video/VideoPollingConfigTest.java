package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetRepository;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.asset.VideoMediaProbe;
import com.wuyao.growth.common.metrics.VideoMetrics;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
import org.apache.hc.client5.http.classic.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VideoPollingConfigTest {
    @ParameterizedTest
    @ValueSource(ints = {7, 37})
    void yamlResolvesEnvironmentVariableAndInjectsTheWorkflowPollingInterval(int seconds) {
        runner(Map.of("VIDEO_POLL_SECONDS", Integer.toString(seconds))).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("growth.video.poll-seconds", Integer.class)).isEqualTo(seconds);
            assertThat(context.getEnvironment().containsProperty("growth.video.provider.poll-seconds")).isFalse();
            var service = context.getBean(VideoWorkflowService.class);
            assertThat(ReflectionTestUtils.getField(service, "pollSeconds")).isEqualTo(seconds);
            assertThat((Integer) ReflectionTestUtils.invokeMethod(service, "nextPollDelay", 1)).isEqualTo(seconds);
            assertThat((Integer) ReflectionTestUtils.invokeMethod(service, "nextPollDelay", 2)).isEqualTo(Math.min(60, seconds * 2));
        });
    }

    @Test
    void yamlKeepsTheExistingDefaultWhenTheEnvironmentVariableIsAbsent() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("growth.video.poll-seconds", Integer.class)).isEqualTo(15);
            assertThat(ReflectionTestUtils.getField(context.getBean(VideoWorkflowService.class), "pollSeconds")).isEqualTo(15);
        });
    }

    private ApplicationContextRunner runner(Map<String, Object> environmentVariables) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables)))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.import=")
                .withBean(VideoWorkflowService.class, () -> new VideoWorkflowService(
                        mock(VideoWorkflowRepository.class), mock(VideoProviderJobRepository.class), mock(AssetRepository.class),
                        mock(AssetService.class), mock(ObjectStorage.class), mock(TaskService.class), new ObjectMapper(),
                        mock(TenantRateLimiter.class), mock(HttpClient.class), mock(VideoMediaProbe.class), mock(VideoMetrics.class)));
    }
}
