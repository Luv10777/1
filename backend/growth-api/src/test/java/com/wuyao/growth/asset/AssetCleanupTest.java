package com.wuyao.growth.asset;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssetCleanupTest {
    @Test void setsTenantBeforeEachTransactionRetainsFailuresAndRechecksConfirmedAssets() {
        var tenants = mock(TenantRepository.class);
        when(tenants.idsAfter(eq(0L), any())).thenReturn(List.of(2L, 3L));
        when(tenants.idsAfter(eq(3L), any())).thenReturn(List.of());
        var repo = mock(AssetRepository.class);
        var storage = mock(ObjectStorage.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> {
            assertThat(TenantContext.require()).isIn(2L, 3L);
            return new SimpleTransactionStatus();
        });
        when(repo.abandonedUploadIds(any(), any())).thenAnswer(invocation ->
                TenantContext.require() == 2L ? List.of(20L, 21L, 22L) : List.of(30L));
        var abandoned = asset(20L, "PENDING");
        var failed = asset(21L, "PENDING");
        var confirmed = asset(22L, "READY");
        var otherTenant = asset(30L, "PENDING");
        when(repo.findForUpdate(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            assertThat(TenantContext.require()).isEqualTo(id / 10);
            return Optional.of(id == 20 ? abandoned : id == 21 ? failed : id == 22 ? confirmed : otherTenant);
        });
        doThrow(new IllegalStateException("storage unavailable")).when(storage).delete("key21");
        var service = new AssetService(repo, storage, mock(TaskService.class), tenants, new TransactionTemplate(manager));
        TenantContext.runAs(99L, () -> { service.cleanupAbandonedUploads(); return null; });
        assertThat(TenantContext.get()).isNull();
        verify(repo).delete(abandoned);
        verify(repo).delete(otherTenant);
        verify(repo, never()).delete(failed);
        verify(repo, never()).delete(confirmed);
        verify(storage, never()).delete("key22");
        verify(manager).rollback(any());
    }
    private Asset asset(long id, String status) {
        var asset = new Asset(); asset.setId(id); asset.setStatus(status); asset.setStorageKey("key" + id);
        asset.setCreatedAt(Instant.now().minusSeconds(10800)); return asset;
    }
}
