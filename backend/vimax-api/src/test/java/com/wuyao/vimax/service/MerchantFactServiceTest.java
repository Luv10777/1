package com.wuyao.vimax.service;

import com.wuyao.vimax.entity.MerchantFact;
import com.wuyao.vimax.repository.MerchantFactRepository;
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
 * MerchantFactService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class MerchantFactServiceTest {

    @Mock
    private MerchantFactRepository factRepository;

    @InjectMocks
    private MerchantFactService factService;

    private MerchantFact testFact;

    @BeforeEach
    void setUp() {
        testFact = new MerchantFact();
        testFact.setId(1L);
        testFact.setMerchantId(100L);
        testFact.setFactType("PRODUCT");
        testFact.setFactKey("main_product");
        testFact.setFactValue("{\"name\":\"Coffee\"}");
        testFact.setStatus("ACTIVE");
        testFact.setCreatedAt(LocalDateTime.now());
    }

    @Test
    void testCreateFact() {
        // Given
        when(factRepository.save(any(MerchantFact.class))).thenReturn(testFact);

        // When
        MerchantFact result = factService.createFact(testFact);

        // Then
        assertNotNull(result);
        assertEquals(testFact.getId(), result.getId());
        verify(factRepository, times(1)).save(any(MerchantFact.class));
    }

    @Test
    void testGetEffectiveFacts() {
        // Given
        List<MerchantFact> facts = Arrays.asList(testFact);
        when(factRepository.findEffectiveFacts(anyLong(), any(LocalDateTime.class)))
                .thenReturn(facts);

        // When
        List<MerchantFact> results = factService.getEffectiveFacts(100L);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals(testFact.getId(), results.get(0).getId());
        verify(factRepository, times(1)).findEffectiveFacts(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void testGetAllFacts() {
        // Given
        List<MerchantFact> facts = Arrays.asList(testFact);
        when(factRepository.findByMerchantIdAndStatus(anyLong(), anyString()))
                .thenReturn(facts);

        // When
        List<MerchantFact> results = factService.getAllFacts(100L);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        verify(factRepository, times(1)).findByMerchantIdAndStatus(100L, "ACTIVE");
    }

    @Test
    void testUpdateFact() {
        // Given
        when(factRepository.findById(anyLong())).thenReturn(Optional.of(testFact));
        when(factRepository.save(any(MerchantFact.class))).thenReturn(testFact);

        MerchantFact updates = new MerchantFact();
        updates.setFactValue("{\"name\":\"Tea\"}");

        // When
        MerchantFact result = factService.updateFact(1L, updates);

        // Then
        assertNotNull(result);
        assertEquals("{\"name\":\"Tea\"}", result.getFactValue());
        verify(factRepository, times(1)).findById(1L);
        verify(factRepository, times(1)).save(any(MerchantFact.class));
    }

    @Test
    void testUpdateFactNotFound() {
        // Given
        when(factRepository.findById(anyLong())).thenReturn(Optional.empty());

        MerchantFact updates = new MerchantFact();

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            factService.updateFact(999L, updates);
        });
    }

    @Test
    void testDeleteFact() {
        // Given
        when(factRepository.findById(anyLong())).thenReturn(Optional.of(testFact));
        when(factRepository.save(any(MerchantFact.class))).thenReturn(testFact);

        // When
        factService.deleteFact(1L);

        // Then
        verify(factRepository, times(1)).findById(1L);
        verify(factRepository, times(1)).save(argThat(fact ->
                "DELETED".equals(fact.getStatus()) && fact.getDeletedAt() != null
        ));
    }

    @Test
    void testDeleteFactNotFound() {
        // Given
        when(factRepository.findById(anyLong())).thenReturn(Optional.empty());

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            factService.deleteFact(999L);
        });
    }
}
