package com.wuyao.growth.video;

import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoWorkflowObservationsTest {
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final VideoWorkflowRepository workflows = mock(VideoWorkflowRepository.class);
    private final TenantRateLimiter permits = mock(TenantRateLimiter.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    @AfterEach void clearContext() { TenantContext.clear(); }

    private VideoWorkflowObservations observations(long cacheSeconds) {
        when(transactions.getTransaction(any())).thenAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return new SimpleTransactionStatus();
        });
        return new VideoWorkflowObservations(tenants, workflows, permits, transactions, 7200, cacheSeconds);
    }

    @Test
    void aggregatesTenantsUnderTheirOwnContextsAndRestoresTheCallerContext() {
        var observations = observations(15);
        TenantContext.set(99L);
        when(tenants.idsAfter(eq(0L), any())).thenAnswer(invocation -> {
            assertThat(TenantContext.get()).isNull();
            return List.of(1L, 2L);
        });
        when(workflows.countStuck(anyLong(), any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            assertThat(TenantContext.get()).isEqualTo(id);
            Instant cutoff = invocation.getArgument(1);
            assertThat(cutoff).isBetween(Instant.now().minusSeconds(7202), Instant.now().minusSeconds(7198));
            return id;
        });
        when(permits.getVideoActiveCount(1L)).thenReturn(1L);
        when(permits.getVideoActiveCount(2L)).thenReturn(2L);
        var sample = observations.snapshot();
        assertThat(sample.stuckWorkflows()).isEqualTo(3);
        assertThat(sample.maxTenantActive()).isEqualTo(2);
        assertThat(TenantContext.get()).isEqualTo(99L);
        assertThat(observations.snapshot()).isSameAs(sample);
        verify(workflows, times(2)).countStuck(anyLong(), any());
    }

    @Test
    void failedRefreshDoesNotServeAnOldSuccessAndRestoresTenantContext() {
        var observations = observations(0);
        TenantContext.set(99L);
        assertThat(observations.snapshot().stuckWorkflows()).isZero();
        when(tenants.idsAfter(anyLong(), any())).thenReturn(List.of(7L));
        when(workflows.countStuck(eq(7L), any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(observations::snapshot).hasMessageContaining("database unavailable");
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void unavailableTenantPermitCountIsMissingDataButDoesNotHideStuckWorkflows() {
        var observations = observations(0);
        when(tenants.idsAfter(eq(0L), any())).thenReturn(List.of(7L, 8L));
        when(workflows.countStuck(anyLong(), any())).thenReturn(1L);
        when(permits.getVideoActiveCount(7L)).thenReturn(-1L);
        var sample = observations.snapshot();
        assertThat(sample.stuckWorkflows()).isEqualTo(2);
        assertThat(sample.maxTenantActive()).isNaN();
        // Avoid paying one Redis connection timeout per tenant after the first failed read.
        verify(permits, never()).getVideoActiveCount(8L);
    }
}
