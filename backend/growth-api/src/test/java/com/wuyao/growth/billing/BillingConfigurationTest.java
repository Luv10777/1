package com.wuyao.growth.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class BillingConfigurationTest {
    @Test void onlyWorkersRegisterTheRecoveryScheduler() {
        var context=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean(BillingRecovery.class,()->org.mockito.Mockito.mock(BillingRecovery.class))
            .withUserConfiguration(BillingRecoveryScheduler.class);
        context.withPropertyValues("growth.worker.enabled=false").run(c->assertThat(c).doesNotHaveBean(BillingRecoveryScheduler.class));
        context.withPropertyValues("growth.worker.enabled=true").run(c->assertThat(c).hasSingleBean(BillingRecoveryScheduler.class));
    }
    @TempDir Path folder;
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties({BillingProperties.class,VideoPricingProperties.class})
    static class ConfigurationOnly {}

    @Test void importedServerPropertiesCanImportVerifiedRatesAndLeaveOperatorAllowlistEmpty() throws Exception {
        Path application=folder.resolve("application.yml"),environment=folder.resolve("growth-api.env"),rates=folder.resolve("billing-rates.yml");
        Files.writeString(application,"""
            growth:
              billing:
                operator-user-ids: ${BILLING_OPERATOR_USER_IDS:}
                video:
                  rates: []
            """);
        Files.writeString(environment,"BILLING_OPERATOR_USER_IDS=\nspring.config.import="+rates.toUri()+"\n");
        // Deliberately disabled values verify loading and nullable fields without adding production rates.
        Files.writeString(rates,"""
            growth:
              billing:
                video:
                  rates:
                    - provider-base-url: https://test.invalid
                      provider-model: test-only
                      resolution: 720p
                      video-input: v2v
                      max-output-pixels: 0
                      reference-video-billed: null
            """);
        var boot=new SpringApplication(ConfigurationOnly.class);
        boot.setWebApplicationType(WebApplicationType.NONE);
        boot.setDefaultProperties(Map.of("spring.main.banner-mode","off","logging.level.root","ERROR"));
        try(var context=boot.run("--spring.config.location="+application.toUri(),
                "--spring.config.import="+environment.toUri()+"[.properties]")) {
            assertThat(context.getBean(BillingProperties.class).getOperatorUserIds()).isEmpty();
            var loaded=context.getBean(VideoPricingProperties.class).getRates();
            assertThat(loaded).hasSize(1);
            assertThat(loaded.getFirst().getProviderModel()).isEqualTo("test-only");
            assertThat(loaded.getFirst().getReferenceVideoBilled()).isNull();
        }
    }
}
