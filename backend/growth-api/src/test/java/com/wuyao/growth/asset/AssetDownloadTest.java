package com.wuyao.growth.asset;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AssetDownloadTest {
    private final AssetRepository assets = mock(AssetRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final AssetService service = new AssetService(assets, storage, mock(TaskService.class));
    private Asset asset;

    @BeforeEach
    void setup() {
        TenantContext.set(7L);
        ReflectionTestUtils.setField(service, "presignTtl", Duration.ofMinutes(15));
        asset = new Asset();
        asset.setId(11L);
        asset.setTenantId(7L);
        asset.setStatus("READY");
        asset.setStorageKey("t7/image/photo");
        when(assets.findById(11L)).thenReturn(Optional.of(asset));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void signsReadyAssetsWithinTheAuthenticatedTenant() {
        when(storage.presignGet("t7/image/photo", Duration.ofMinutes(15))).thenReturn("https://storage.test/signed");
        var ticket = service.presignDownload(11L);
        assertThat(ticket.assetId()).isEqualTo(11L);
        assertThat(ticket.downloadUrl()).isEqualTo("https://storage.test/signed");
        assertThat(ticket.expiresAt()).isAfter(Instant.now().plusSeconds(14 * 60));
    }

    @Test
    void refusesOtherTenantsEvenIfRepositoryReturnsTheRecord() {
        asset.setTenantId(8L);
        assertThatThrownBy(() -> service.presignDownload(11L)).isInstanceOf(BizException.class).hasMessage("素材不存在");
        verifyNoInteractions(storage);
    }

    @Test
    void doesNotSignMissingOrUnconfirmedAssets() {
        when(assets.findById(12L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.presignDownload(12L)).isInstanceOf(BizException.class).hasMessage("素材不存在");
        asset.setStatus("PENDING");
        assertThatThrownBy(() -> service.presignDownload(11L)).isInstanceOf(BizException.class).hasMessage("素材尚未上传完成");
        verifyNoInteractions(storage);
    }
}
