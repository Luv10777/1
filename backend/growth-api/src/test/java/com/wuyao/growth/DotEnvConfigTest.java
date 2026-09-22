package com.wuyao.growth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class DotEnvConfigTest {
    @TempDir Path directory;

    @Test
    void propertiesStyleDotEnvLoadsAndSystemPropertiesOverrideIt() throws Exception {
        Path env = directory.resolve(".env");
        Files.writeString(env, "DB_PASSWORD=local-value\nJWT_SECRET=file-secret\n");
        var runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.import=" + env.toUri() + "[.properties]");
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("DB_PASSWORD")).isEqualTo("local-value");
            assertThat(context.getEnvironment().getProperty("growth.jwt.secret")).isEqualTo("file-secret");
        });
        runner.withSystemProperties("JWT_SECRET=external-secret").run(context ->
                assertThat(context.getEnvironment().getProperty("growth.jwt.secret")).isEqualTo("external-secret"));
    }
}
