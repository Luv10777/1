package com.wuyao.growth.asset;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.iam.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetReferenceLifetimeTest {
    private final AssetRepository repository = mock(AssetRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private AssetService service;

    @BeforeEach
    void setUp() {
        service = new AssetService(repository, storage, mock(TaskService.class), mock(TenantRepository.class),
                mock(TransactionTemplate.class));
        Asset asset = new Asset();
        asset.setId(7L);
        asset.setType("IMAGE");
        asset.setStatus("READY");
        asset.setStorageKey("t1/image/reference");
        when(repository.findById(7L)).thenReturn(Optional.of(asset));
        when(storage.presignGet(anyString(), any(Duration.class))).thenReturn("https://signed/reference");
    }

    @Test
    void defaultsToSixHoursWithoutChangingThePublicSignature() {
        service.validateReferencePresignLifetime();
        assertThat(service.presignedReference(7L, "IMAGE")).isEqualTo("https://signed/reference");
        verify(storage).presignGet("t1/image/reference", Duration.ofHours(6));
    }

    @ParameterizedTest
    @CsvSource({"3600,7200,60,7500", "21600,36000,60,36300", "3600,7200,600,7825"})
    void aShortConfiguredTtlIsRaisedToCoverTheWorkflowAndSubmissionWindow(
            long configuredTtl, long maxDuration, long providerTimeout, long expectedTtl) {
        ReflectionTestUtils.setField(service, "referencePresignTtlSeconds", configuredTtl);
        ReflectionTestUtils.setField(service, "videoMaxDurationSeconds", maxDuration);
        ReflectionTestUtils.setField(service, "videoProviderTimeoutSeconds", providerTimeout);
        service.validateReferencePresignLifetime();

        service.presignedReference(7L, "IMAGE");

        verify(storage).presignGet("t1/image/reference", Duration.ofSeconds(expectedTtl));
    }

    @Test
    void aLifetimeBeyondTheStorageLimitFailsAtStartup() {
        ReflectionTestUtils.setField(service, "videoMaxDurationSeconds", 604800L);
        assertThatThrownBy(service::validateReferencePresignLifetime)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("7 天上限");
        verifyNoInteractions(storage);
    }

    @Test
    void invalidLifetimeConfigurationFailsAtStartup() {
        ReflectionTestUtils.setField(service, "referenceUrlSafetySeconds", 0L);
        assertThatThrownBy(service::validateReferencePresignLifetime)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 到 604800");
        verifyNoInteractions(storage);
    }
}
