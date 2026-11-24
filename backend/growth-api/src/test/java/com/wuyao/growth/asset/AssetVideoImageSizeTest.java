package com.wuyao.growth.asset;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.iam.repository.TenantRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetVideoImageSizeTest {
    private final AssetRepository repository = mock(AssetRepository.class);
    private final AssetService service = new AssetService(repository, mock(ObjectStorage.class), mock(TaskService.class),
            mock(TenantRepository.class), mock(TransactionTemplate.class));

    private void image(int width, int height) {
        Asset asset = new Asset();
        asset.setId(7L);
        asset.setName("cover.png");
        asset.setType("IMAGE");
        asset.setStatus("READY");
        asset.setStorageKey("t1/image/cover");
        asset.setWidth(width);
        asset.setHeight(height);
        when(repository.findById(7L)).thenReturn(Optional.of(asset));
    }

    @ParameterizedTest
    @CsvSource({"300,300", "6000,6000", "1330,1773", "300,6000"})
    void imagesWithinTheProviderRangeAreAccepted(int width, int height) {
        image(width, height);
        assertThatCode(() -> service.mediaReference(7L, "IMAGE")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"288,384", "299,1000", "1000,299", "6001,1000", "1000,6001"})
    void imagesOutsideTheProviderRangeAreRejectedBeforeSubmission(int width, int height) {
        image(width, height);
        assertThatThrownBy(() -> service.mediaReference(7L, "IMAGE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("cover.png")
                .hasMessageContaining(width + "×" + height)
                .hasMessageContaining("300–6000");
    }
}
