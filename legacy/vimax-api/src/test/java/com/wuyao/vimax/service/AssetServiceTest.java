package com.wuyao.vimax.service;

import com.wuyao.vimax.config.MinioConfig;
import com.wuyao.vimax.dto.GetUploadUrlRequest;
import com.wuyao.vimax.dto.UploadUrlResponse;
import com.wuyao.vimax.entity.Asset;
import com.wuyao.vimax.repository.AssetRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AssetService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private MinioClient minioClient;

    @Mock
    private MinioConfig minioConfig;

    @InjectMocks
    private AssetService assetService;

    private Asset testAsset;

    @BeforeEach
    void setUp() {
        testAsset = new Asset();
        testAsset.setId(1L);
        testAsset.setCode("test123");
        testAsset.setName("test.jpg");
        testAsset.setType("IMAGE");
        testAsset.setMerchantId(100L);
        testAsset.setStatus("AVAILABLE");

        when(minioConfig.getBucketAssets()).thenReturn("vimax-assets");
        when(minioConfig.getBucketVideos()).thenReturn("vimax-videos");
        when(minioConfig.getBucketTemp()).thenReturn("vimax-temp");
        when(minioConfig.getEndpoint()).thenReturn("http://localhost:9000");
    }

    @Test
    void testGetUploadUrl() throws Exception {
        // Given
        GetUploadUrlRequest request = new GetUploadUrlRequest();
        request.setFileName("test.jpg");
        request.setFileType("IMAGE");

        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/bucket/key?presigned");

        // When
        UploadUrlResponse result = assetService.getUploadUrl(request, 1L);

        // Then
        assertNotNull(result);
        assertNotNull(result.getUploadUrl());
        assertNotNull(result.getAssetCode());
        assertEquals(900L, result.getExpiresIn());
        verify(minioClient, times(1)).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    void testCreateAsset() {
        // Given
        when(assetRepository.existsByCode(anyString())).thenReturn(false);
        when(assetRepository.save(any(Asset.class))).thenReturn(testAsset);

        // When
        Asset result = assetService.createAsset(
                "test123", "test.jpg", "IMAGE", "image/jpeg", 1024L, 100L, 1L
        );

        // Then
        assertNotNull(result);
        assertEquals("test123", result.getCode());
        verify(assetRepository, times(1)).save(any(Asset.class));
    }

    @Test
    void testCreateAssetDuplicateCode() {
        // Given
        when(assetRepository.existsByCode(anyString())).thenReturn(true);

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            assetService.createAsset(
                    "test123", "test.jpg", "IMAGE", "image/jpeg", 1024L, 100L, 1L
            );
        });
    }

    @Test
    void testGetAssetsByMerchant() {
        // Given
        List<Asset> assets = Arrays.asList(testAsset);
        when(assetRepository.findByMerchantIdAndStatus(anyLong(), anyString()))
                .thenReturn(assets);

        // When
        List<Asset> results = assetService.getAssetsByMerchant(100L, null);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        verify(assetRepository, times(1)).findByMerchantIdAndStatus(100L, "AVAILABLE");
    }

    @Test
    void testGetAssetsByMerchantWithType() {
        // Given
        List<Asset> assets = Arrays.asList(testAsset);
        when(assetRepository.findByMerchantIdAndTypeAndStatus(anyLong(), anyString(), anyString()))
                .thenReturn(assets);

        // When
        List<Asset> results = assetService.getAssetsByMerchant(100L, "IMAGE");

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        verify(assetRepository, times(1))
                .findByMerchantIdAndTypeAndStatus(100L, "IMAGE", "AVAILABLE");
    }

    @Test
    void testDeleteAsset() {
        // Given
        when(assetRepository.findById(anyLong())).thenReturn(Optional.of(testAsset));
        when(assetRepository.save(any(Asset.class))).thenReturn(testAsset);

        // When
        assetService.deleteAsset(1L);

        // Then
        verify(assetRepository, times(1)).findById(1L);
        verify(assetRepository, times(1)).save(argThat(asset ->
                "DELETED".equals(asset.getStatus()) && asset.getDeletedAt() != null
        ));
    }

    @Test
    void testDeleteAssetNotFound() {
        // Given
        when(assetRepository.findById(anyLong())).thenReturn(Optional.empty());

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            assetService.deleteAsset(999L);
        });
    }
}
