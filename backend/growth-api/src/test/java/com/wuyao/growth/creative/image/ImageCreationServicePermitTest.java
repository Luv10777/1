package com.wuyao.growth.creative.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ImageModelProperties;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ImageCreationServicePermitTest {
    private static final Long TENANT_ID = 41L;
    private static final Long CREATION_ID = 11L;
    private final AtomicInteger tenantActive = new AtomicInteger();
    private final AtomicInteger globalActive = new AtomicInteger();
    private final TenantRateLimiter limiter = mock(TenantRateLimiter.class);
    private final ImageCreationRepository creations = mock(ImageCreationRepository.class);
    private final ImageItemRepository items = mock(ImageItemRepository.class);
    private final TaskService tasks = mock(TaskService.class);
    private final List<ImageItem> savedItems = new ArrayList<>();
    private final TestTransactionManager transactionManager = new TestTransactionManager();
    private final TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    private ImageCreationService service;

    @BeforeEach
    void setUp() {
        var spec = new ImageDtos.Spec("product", "product photo", "", "");
        var original = new ImageCreation();
        original.setId(10L);
        original.setTenantId(TENANT_ID);
        original.setRequest(request("original-request", "PRODUCT_SET"));
        original.setPlan(new ImageDtos.Plan("plan", "", "natural light", List.of(spec)));
        var source = new ImageItem();
        source.setId(20L);
        source.setCreationId(10L);
        source.setStatus("SUCCEEDED");
        source.setSpec(spec);
        when(creations.findById(10L)).thenReturn(Optional.of(original));
        when(items.findById(20L)).thenReturn(Optional.of(source));
        when(items.findByCreationIdOrderByOrdinal(10L)).thenReturn(List.of(source));
        when(creations.saveAndFlush(any(ImageCreation.class))).thenAnswer(invocation -> {
            ImageCreation creation = invocation.getArgument(0);
            creation.setId(CREATION_ID);
            when(creations.findById(CREATION_ID)).thenReturn(Optional.of(creation));
            return creation;
        });
        when(items.saveAndFlush(any(ImageItem.class))).thenAnswer(invocation -> {
            ImageItem item = invocation.getArgument(0);
            item.setId(100L + savedItems.size());
            savedItems.add(item);
            when(items.lock(item.getId())).thenReturn(Optional.of(item));
            return item;
        });
        when(items.findByCreationIdOrderByOrdinal(CREATION_ID)).thenReturn(savedItems);
        when(tasks.submit(anyString(), anyString(), anyMap(), anyString(), isNull())).thenAnswer(invocation -> {
            var task = new Task();
            task.setId(30L);
            return task;
        });
        when(limiter.tryAcquireImageGeneration(TENANT_ID, 20, 200)).thenAnswer(invocation -> {
            tenantActive.incrementAndGet();
            globalActive.incrementAndGet();
            return true;
        });
        doAnswer(invocation -> {
            tenantActive.decrementAndGet();
            globalActive.decrementAndGet();
            return null;
        }).when(limiter).releaseImageGeneration(TENANT_ID);
        var gateway = mock(AiGateway.class);
        when(gateway.configured(any())).thenReturn(true);
        service = new ImageCreationService(creations, items, mock(AssetService.class), tasks,
                mock(ObjectStorage.class), gateway, new ImageModelProperties(), new ObjectMapper(), limiter);
        ReflectionTestUtils.setField(service, "tenantMaxConcurrent", 20);
        ReflectionTestUtils.setField(service, "globalMaxConcurrent", 200);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void cleanThreadContext() {
        TenantContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.clear();
    }

    @Test
    void editTextReleasesPermitWhenTheTransactionRollsBackAfterAcquire() {
        doThrow(new IllegalStateException("item write failed")).when(items).saveAndFlush(any(ImageItem.class));

        assertThatThrownBy(() -> transactions.execute(status -> invoke(Operation.EDIT_TEXT)))
                .isInstanceOf(IllegalStateException.class).hasMessage("item write failed");

        assertReleased(1);
    }

    @Test
    void regenerateReleasesPermitWhenTheTransactionRollsBackAfterAcquire() {
        doThrow(new IllegalStateException("creation write failed")).when(creations).saveAndFlush(any(ImageCreation.class));

        assertThatThrownBy(() -> transactions.execute(status -> invoke(Operation.REGENERATE)))
                .isInstanceOf(IllegalStateException.class).hasMessage("creation write failed");

        assertReleased(1);
    }

    @Test
    void createVersionFailureReleasesExactlyOnePermit() {
        doThrow(new IllegalStateException("creation write failed")).when(creations).saveAndFlush(any(ImageCreation.class));

        assertThatThrownBy(() -> transactions.execute(status -> invoke(Operation.CREATE)))
                .isInstanceOf(IllegalStateException.class).hasMessage("creation write failed");

        assertReleased(1);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void allAcquisitionPathsReleaseWhenEnqueueFails(Operation operation) {
        doThrow(new IllegalStateException("enqueue failed")).when(tasks)
                .submit(anyString(), anyString(), anyMap(), anyString(), isNull());

        assertThatThrownBy(() -> transactions.execute(status -> invoke(operation)))
                .isInstanceOf(IllegalStateException.class).hasMessage("enqueue failed");

        assertReleased(1);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void allAcquisitionPathsReleaseWhenViewAssemblyFails(Operation operation) {
        doThrow(new IllegalStateException("view failed")).when(items).findByCreationIdOrderByOrdinal(CREATION_ID);

        assertThatThrownBy(() -> transactions.execute(status -> invoke(operation)))
                .isInstanceOf(IllegalStateException.class).hasMessage("view failed");

        assertReleased(1);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void successfulCommitKeepsThePermitForTheWorkflow(Operation operation) {
        transactions.execute(status -> invoke(operation));

        assertThat(tenantActive).hasValue(1);
        assertThat(globalActive).hasValue(1);
        verify(limiter).tryAcquireImageGeneration(TENANT_ID, 20, 200);
        verify(limiter, never()).releaseImageGeneration(anyLong());
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void callsWithoutATransactionCannotAcquirePermits(Operation operation) {
        assertThatThrownBy(() -> invoke(operation)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(limiter);
        verify(creations, never()).saveAndFlush(any(ImageCreation.class));
    }

    @Test
    void synchronizationWithoutAnActualTransactionCannotAcquirePermits() {
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> invoke(Operation.EDIT_TEXT)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(limiter);
    }

    @Test
    void aRollbackOnlyTransactionReleasesAfterTheServiceReturnsNormally() {
        transactions.execute(status -> {
            var result = invoke(Operation.EDIT_TEXT);
            assertThat(tenantActive).hasValue(1);
            verify(limiter, never()).releaseImageGeneration(anyLong());
            status.setRollbackOnly();
            return result;
        });

        assertReleased(1);
    }

    @Test
    void failureBeforeCommitReleasesAfterTheServiceReturnsNormally() {
        assertThatThrownBy(() -> transactions.execute(status -> {
            var result = invoke(Operation.REGENERATE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("commit validation failed");
                }
            });
            return result;
        })).isInstanceOf(IllegalStateException.class).hasMessage("commit validation failed");

        assertReleased(1);
    }

    @Test
    void anUnknownCommitOutcomeKeepsThePermit() {
        transactionManager.failCommit = true;

        assertThatThrownBy(() -> transactions.execute(status -> invoke(Operation.EDIT_TEXT)))
                .isInstanceOf(TransactionSystemException.class).hasMessage("commit outcome unknown");

        assertThat(tenantActive).hasValue(1);
        assertThat(globalActive).hasValue(1);
        verify(limiter, never()).releaseImageGeneration(anyLong());
    }

    @Test
    void aRejectedAcquireDoesNotReleaseAnotherWorkflowsPermit() {
        tenantActive.set(1);
        globalActive.set(1);
        doReturn(false).when(limiter).tryAcquireImageGeneration(TENANT_ID, 20, 200);

        assertThatThrownBy(() -> transactions.execute(status -> invoke(Operation.EDIT_TEXT)))
                .isInstanceOf(BizException.class);

        assertThat(tenantActive).hasValue(1);
        assertThat(globalActive).hasValue(1);
        verify(limiter, never()).releaseImageGeneration(anyLong());
    }

    @Test
    void multipleAcquiresInOneRollbackEachReleaseTheirOwnPermit() {
        transactions.execute(status -> {
            invoke(Operation.EDIT_TEXT);
            invoke(Operation.REGENERATE);
            assertThat(tenantActive).hasValue(2);
            status.setRollbackOnly();
            return null;
        });

        assertReleased(2);
    }

    @Test
    void aCommittedWorkflowReleasesItsPermitOnlyOnceAtTerminalState() {
        transactions.execute(status -> invoke(Operation.EDIT_TEXT));
        when(creations.clearConcurrencyPermit(CREATION_ID)).thenReturn(1, 0);
        var item = savedItems.getFirst();
        item.setStatus("GENERATING");
        var key = "t41/generated/" + item.getId() + "/0/background.png";

        for (int attempt = 0; attempt < 2; attempt++) {
            transactions.executeWithoutResult(status -> service.markImagePersisted(item.getId(), key, 1024, 1024, null));
        }

        assertReleased(1);
    }

    private void assertReleased(int permits) {
        assertThat(tenantActive).as("tenant active permit count after rollback or completion").hasValue(0);
        assertThat(globalActive).as("global active permit count after rollback or completion").hasValue(0);
        verify(limiter, times(permits)).tryAcquireImageGeneration(TENANT_ID, 20, 200);
        verify(limiter, times(permits)).releaseImageGeneration(TENANT_ID);
    }

    private ImageDtos.View invoke(Operation operation) {
        return switch (operation) {
            case CREATE -> service.create(request("create-request", "POSTER"), null);
            case EDIT_TEXT -> service.editText(10L, 20L, new ImageDtos.TextEdit("edit-request", "title", "caption"), null);
            case REGENERATE -> service.regenerate(10L, 20L, "regenerate-request", null, null);
        };
    }

    private ImageDtos.Create request(String key, String workflow) {
        return new ImageDtos.Create(key, workflow, "brief", List.of(), "1:1", "1K", 1,
                "LOCAL", "PRODUCT_MAIN", "product", "natural", null);
    }

    private enum Operation { CREATE, EDIT_TEXT, REGENERATE }

    // Use Spring's synchronization lifecycle while repositories remain mocked.
    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        private boolean failCommit;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new TransactionSystemException("commit outcome unknown");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
