package com.wuyao.vimax.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.vimax.entity.MerchantFact;
import com.wuyao.vimax.entity.MerchantFactSnapshot;
import com.wuyao.vimax.repository.MerchantFactRepository;
import com.wuyao.vimax.repository.MerchantFactSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MerchantFactSnapshotService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class MerchantFactSnapshotServiceTest {

    @Mock
    private MerchantFactRepository factRepository;

    @Mock
    private MerchantFactSnapshotRepository snapshotRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private MerchantFactSnapshotService snapshotService;

    private MerchantFact testFact;
    private MerchantFactSnapshot testSnapshot;

    @BeforeEach
    void setUp() {
        testFact = new MerchantFact();
        testFact.setId(1L);
        testFact.setMerchantId(100L);
        testFact.setFactType("PRODUCT");
        testFact.setFactKey("main_product");
        testFact.setFactValue("{\"name\":\"Coffee\"}");
        testFact.setStatus("ACTIVE");

        testSnapshot = new MerchantFactSnapshot();
        testSnapshot.setId(1L);
        testSnapshot.setMerchantId(100L);
        testSnapshot.setSnapshotHash("abc123");
        testSnapshot.setFactsSummary("{\"PRODUCT\":[]}");
        testSnapshot.setIsComplete(true);
    }

    @Test
    void testCreateSnapshot() throws Exception {
        // Given
        List<MerchantFact> facts = Arrays.asList(testFact);
        when(factRepository.findEffectiveFacts(anyLong(), any(LocalDateTime.class)))
                .thenReturn(facts);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"PRODUCT\":[]}");
        when(snapshotRepository.existsBySnapshotHash(anyString())).thenReturn(false);
        when(snapshotRepository.save(any(MerchantFactSnapshot.class))).thenReturn(testSnapshot);

        // When
        MerchantFactSnapshot result = snapshotService.createSnapshot(100L, 1L);

        // Then
        assertNotNull(result);
        assertEquals(100L, result.getMerchantId());
        verify(factRepository, times(1)).findEffectiveFacts(anyLong(), any(LocalDateTime.class));
        verify(snapshotRepository, times(1)).save(any(MerchantFactSnapshot.class));
    }

    @Test
    void testCreateSnapshotDuplicate() throws Exception {
        // Given
        List<MerchantFact> facts = Arrays.asList(testFact);
        when(factRepository.findEffectiveFacts(anyLong(), any(LocalDateTime.class)))
                .thenReturn(facts);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"PRODUCT\":[]}");
        when(snapshotRepository.existsBySnapshotHash(anyString())).thenReturn(true);
        when(snapshotRepository.findBySnapshotHash(anyString())).thenReturn(Optional.of(testSnapshot));

        // When
        MerchantFactSnapshot result = snapshotService.createSnapshot(100L, 1L);

        // Then
        assertNotNull(result);
        assertEquals(testSnapshot.getId(), result.getId());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void testCreateSnapshotNoFacts() {
        // Given
        when(factRepository.findEffectiveFacts(anyLong(), any(LocalDateTime.class)))
                .thenReturn(Arrays.asList());

        // When & Then
        assertThrows(IllegalStateException.class, () -> {
            snapshotService.createSnapshot(100L, 1L);
        });
    }

    @Test
    void testGetSnapshotsByMerchant() {
        // Given
        List<MerchantFactSnapshot> snapshots = Arrays.asList(testSnapshot);
        when(snapshotRepository.findByMerchantIdOrderByCreatedAtDesc(anyLong()))
                .thenReturn(snapshots);

        // When
        List<MerchantFactSnapshot> results = snapshotService.getSnapshotsByMerchant(100L);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        verify(snapshotRepository, times(1)).findByMerchantIdOrderByCreatedAtDesc(100L);
    }

    @Test
    void testGetSnapshotById() {
        // Given
        when(snapshotRepository.findById(anyLong())).thenReturn(Optional.of(testSnapshot));

        // When
        MerchantFactSnapshot result = snapshotService.getSnapshotById(1L);

        // Then
        assertNotNull(result);
        assertEquals(testSnapshot.getId(), result.getId());
        verify(snapshotRepository, times(1)).findById(1L);
    }

    @Test
    void testGetSnapshotByIdNotFound() {
        // Given
        when(snapshotRepository.findById(anyLong())).thenReturn(Optional.empty());

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            snapshotService.getSnapshotById(999L);
        });
    }
}
