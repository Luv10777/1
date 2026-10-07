package com.wuyao.growth.voice;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.AccountService;
import com.wuyao.growth.store.StoreAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceSampleServiceTest {
    private final VoiceSampleRepository repository = mock(VoiceSampleRepository.class);
    private final StoreAccessService stores = mock(StoreAccessService.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final VoiceSampleStoreRepository grants = mock(VoiceSampleStoreRepository.class);
    private final AccountService accounts = mock(AccountService.class);
    private final VoiceUsageGuard guard = mock(VoiceUsageGuard.class);
    private final VoiceSampleService service = new VoiceSampleService(repository, grants, stores, accounts, storage, List.of(guard));
    private VoiceSample sample;

    private static VoiceSampleStore openTo(long storeId) {
        VoiceSampleStore grant = new VoiceSampleStore();
        grant.setTenantId(1L); grant.setSampleId(10L); grant.setStoreId(storeId); grant.setGrantedBy(3L);
        return grant;
    }

    @BeforeEach void setup() {
        TenantContext.set(1L);
        sample = new VoiceSample();
        sample.setId(10L); sample.setTenantId(1L); sample.setStoreId(2L);
        sample.setName("门店声音"); sample.setMimeType("audio/wav"); sample.setStorageKey("t1/voice-samples/original");
        sample.setConsentBy(3L); sample.setConsentAt(Instant.parse("2026-09-28T00:00:00Z"));
        sample.setConsentText(VoiceSampleService.CONSENT);
        when(repository.lockById(10L)).thenReturn(Optional.of(sample));
        when(repository.findById(10L)).thenReturn(Optional.of(sample));
        // User 3 is the merchant's owner; the sample is open to store 2 only.
        when(accounts.isOwner(3L)).thenReturn(true);
        when(grants.findBySampleIdOrderByStoreIdAsc(10L)).thenReturn(List.of(openTo(2L)));
        when(grants.existsBySampleIdAndStoreId(10L, 2L)).thenReturn(true);
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void onlyTheOwnerChangesAVoiceWhileAnyoneWhoCanEnterAStoreItIsOpenToMayUseIt() {
        sample.setStatus("READY");
        doThrow(BizException.of(ErrorCode.FORBIDDEN, "只有管理员可以执行这个操作")).when(accounts).requireOwner(7L);
        when(storage.presignGet(anyString(), any())).thenReturn("https://storage.test/sample");

        // A clerk of store 2 can listen to it and see it, and nothing more.
        when(stores.hasAccess(2L, 7L)).thenReturn(true);
        assertThat(service.download(10L, 7L).downloadUrl()).isEqualTo("https://storage.test/sample");
        assertThat(service.get(10L, 7L).storeIds()).containsExactly(2L);
        assertThatThrownBy(() -> service.rename(10L, "改名", 7L)).hasMessageContaining("只有管理员");
        assertThatThrownBy(() -> service.beginClone(10L, 7L, "p")).hasMessageContaining("只有管理员");
        assertThatThrownBy(() -> service.beginDelete(10L, 7L)).hasMessageContaining("只有管理员");
        assertThatThrownBy(() -> service.setStores(10L, List.of(2L, 5L), 7L)).hasMessageContaining("只有管理员");
        assertThatThrownBy(() -> service.upload(2L, new VoiceDtos.UploadRequest("声音", "audio/wav", true), 7L))
                .hasMessageContaining("只有管理员");
        assertThat(sample.getName()).isEqualTo("门店声音");
        assertThat(sample.getStatus()).isEqualTo("READY");
        verify(repository, never()).saveAndFlush(any());
        verify(grants, never()).save(any());

        // A clerk of another store cannot even listen.
        assertThatThrownBy(() -> service.download(10L, 8L)).hasMessageContaining("没有使用这个声音的权限");
        // Whether the voice may speak in a store is decided by the store, not by who asks.
        assertThat(service.openTo(10L, 2L).id()).isEqualTo(10L);
        assertThatThrownBy(() -> service.openTo(10L, 5L)).hasMessageContaining("当前门店");
    }

    @Test void theOwnerOpensAVoiceToMoreStoresButCannotCloseItToOneThatIsUsingItOrToAll() {
        sample.setStatus("READY");
        var opened = service.setStores(10L, List.of(5L, 2L, 5L), 3L);
        assertThat(opened.storeIds()).containsExactly(2L, 5L);
        var saved = ArgumentCaptor.forClass(VoiceSampleStore.class);
        verify(grants).save(saved.capture());
        assertThat(saved.getValue().getStoreId()).isEqualTo(5L);
        assertThat(saved.getValue().getSampleId()).isEqualTo(10L);
        assertThat(saved.getValue().getTenantId()).isEqualTo(1L);
        assertThat(saved.getValue().getGrantedBy()).isEqualTo(3L);
        verify(grants).deleteAll(List.of());

        assertThatThrownBy(() -> service.setStores(10L, List.of(), 3L)).hasMessageContaining("至少保留一家门店");
        // A store that does not exist in this merchant is refused by the store check itself.
        doThrow(BizException.of(ErrorCode.NOT_FOUND, "门店不存在或已停用")).when(stores).requireAccess(99L, 3L);
        assertThatThrownBy(() -> service.setStores(10L, List.of(2L, 99L), 3L)).hasMessageContaining("门店不存在");

        when(guard.inUse(2L, 10L)).thenReturn(Optional.of("这个音色正在场次「晚间场」中使用"));
        assertThatThrownBy(() -> service.setStores(10L, List.of(5L), 3L)).hasMessageContaining("晚间场");
        verify(grants, times(1)).save(any());
        verify(grants, times(1)).deleteAll(any());
    }

    @Test void absentConsentDoesNotCreateSampleOrUploadUrl() {
        assertThatThrownBy(() -> service.upload(2L, new VoiceDtos.UploadRequest("声音", "audio/wav", false), 3L))
                .isInstanceOf(BizException.class).hasMessageContaining("授权");
        verifyNoInteractions(repository, storage);
    }

    @Test void cosUploadsPersistDestinationAlongsideTenantScopedKey() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sampleStorage", "cos");
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            VoiceSample created = call.getArgument(0); created.setId(11L); return created;
        });
        when(storage.presignPut(anyString(), any())).thenReturn("https://bucket.cos.ap-shanghai.myqcloud.com/signed");
        var result = service.upload(2L, new VoiceDtos.UploadRequest("声音", "audio/wav", true), 3L);
        assertThat(result.sample().storageKey()).startsWith("cos/t1/voice-samples/");
        verify(storage).presignPut(eq(result.sample().storageKey()), any());
    }

    @Test void uploadRecordsAuthenticatedTenantActorTimestampAndExactConsentBeforePresigning() {
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            VoiceSample created = call.getArgument(0); created.setId(11L); return created;
        });
        when(storage.presignPut(anyString(), any())).thenReturn("https://storage.test/upload");
        Instant before = Instant.now();
        var result = service.upload(2L, new VoiceDtos.UploadRequest(" 店主声音 ", "audio/wav", true), 3L);

        var capture = ArgumentCaptor.forClass(VoiceSample.class);
        verify(repository).saveAndFlush(capture.capture());
        var saved = capture.getValue();
        assertThat(saved.getTenantId()).isEqualTo(1L);
        assertThat(saved.getStoreId()).isEqualTo(2L);
        assertThat(saved.getName()).isEqualTo("店主声音");
        assertThat(saved.getConsentBy()).isEqualTo(3L);
        assertThat(saved.getConsentAt()).isBetween(before, Instant.now());
        assertThat(saved.getConsentText()).isEqualTo(VoiceSampleService.CONSENT);
        assertThat(saved.getStorageKey()).startsWith("t1/voice-samples/");
        assertThat(result.sample().status()).isEqualTo("PENDING_UPLOAD");
        assertThat(result.uploadUrl()).isEqualTo("https://storage.test/upload");
        var order = inOrder(stores, repository, storage);
        order.verify(stores).requireAccess(2L, 3L);
        order.verify(repository).saveAndFlush(saved);
        order.verify(storage).presignPut(eq(saved.getStorageKey()), any());
        // A new voice starts out open to the store it was uploaded from, and to no other.
        var grant = ArgumentCaptor.forClass(VoiceSampleStore.class);
        verify(grants).save(grant.capture());
        assertThat(grant.getValue().getStoreId()).isEqualTo(2L);
        assertThat(grant.getValue().getSampleId()).isEqualTo(11L);
        assertThat(grant.getValue().getGrantedBy()).isEqualTo(3L);
        assertThat(result.sample().storeIds()).containsExactly(2L);
    }

    @Test void storeDenialPreventsUploadAndSampleDownload() {
        doThrow(BizException.of(ErrorCode.FORBIDDEN, "无门店权限")).when(stores).requireAccess(2L, 9L);
        assertThatThrownBy(() -> service.upload(2L, new VoiceDtos.UploadRequest("声音", "audio/wav", true), 9L))
                .isInstanceOf(BizException.class);
        sample.setStatus("UPLOADED");
        assertThatThrownBy(() -> service.download(10L, 9L)).isInstanceOf(BizException.class);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(storage);
    }

    @Test void uploadUsesPersistedInstanceWhenJpaMergeReturnsADifferentObject() {
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            VoiceSample original = call.getArgument(0);
            VoiceSample managed = new VoiceSample();
            org.springframework.beans.BeanUtils.copyProperties(original, managed);
            managed.setId(71L);
            assertThat(original.getId()).isNull();
            return managed;
        });
        when(storage.presignPut(anyString(), any())).thenReturn("https://storage.test/upload");

        var result = service.upload(2L, new VoiceDtos.UploadRequest("店主声音", "audio/wav", true), 3L);

        assertThat(result.sample().id()).isEqualTo(71L);
        assertThat(result.sample().consentBy()).isEqualTo(3L);
        assertThat(result.sample().storageKey()).startsWith("t1/voice-samples/");
        var submitted = ArgumentCaptor.forClass(VoiceSample.class);
        verify(repository).saveAndFlush(submitted.capture());
        assertThat(submitted.getValue().getId()).isNull();
    }

    @Test void crossTenantSampleIsHiddenBeforeStorageAccess() {
        sample.setTenantId(99L);
        assertThatThrownBy(() -> service.download(10L, 3L)).isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.beginDelete(10L, 3L)).isInstanceOf(BizException.class);
        verifyNoInteractions(stores, storage);
        assertThat(sample.getStatus()).isEqualTo("PENDING_UPLOAD");
    }

    @Test void confirmRequiresExistingObjectExactSizeAndExpectedMime() {
        when(storage.stat(sample.getStorageKey())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.confirm(10L, 100L, 3L)).hasMessageContaining("尚未上传");
        when(storage.stat(sample.getStorageKey())).thenReturn(Optional.of(new ObjectStorage.StoredObject(100, "audio/wav")));
        assertThatThrownBy(() -> service.confirm(10L, 101L, 3L)).hasMessageContaining("大小");
        when(storage.stat(sample.getStorageKey())).thenReturn(Optional.of(new ObjectStorage.StoredObject(100, "image/png")));
        assertThatThrownBy(() -> service.confirm(10L, 100L, 3L)).hasMessageContaining("格式");
        assertThat(sample.getStatus()).isEqualTo("PENDING_UPLOAD");
        when(storage.stat(sample.getStorageKey())).thenReturn(Optional.of(new ObjectStorage.StoredObject(100, "audio/wav")));
        assertThat(service.confirm(10L, 100L, 3L).status()).isEqualTo("UPLOADED");
        assertThat(sample.getSizeBytes()).isEqualTo(100L);
    }

    @Test void failedCloneRetryRetainsProviderIdAndRejectsChangingProvider() {
        sample.setStatus("FAILED"); sample.setProviderCode("original"); sample.setProviderVoiceId("voice-existing");
        assertThatThrownBy(() -> service.beginClone(10L, 3L, "other"))
                .isInstanceOf(BizException.class).hasMessageContaining("原供应商");
        assertThat(sample.getStatus()).isEqualTo("FAILED");
        assertThat(sample.getProviderCode()).isEqualTo("original");
        verifyNoInteractions(storage);

        var retry = service.beginClone(10L, 3L, "original");
        assertThat(retry.existingVoiceId()).isEqualTo("voice-existing");
        assertThat(sample.getStatus()).isEqualTo("CLONING");
        assertThatThrownBy(() -> service.beginDelete(10L, 3L)).hasMessageContaining("等待克隆");
    }

    @Test void aCloneSubmissionThatNeverReportedBackCanBeSubmittedAgainOrDeleted() {
        // What a restart in the middle of a clone request leaves behind.
        sample.setStatus("CLONING"); sample.setProviderCode("original"); sample.setProviderVoiceId(null);
        assertThat(VoiceDtos.SampleView.of(sample).stalled()).isFalse();
        assertThatThrownBy(() -> service.beginClone(10L, 3L, "original")).hasMessageContaining("等待当前克隆完成");
        assertThatThrownBy(() -> service.beginDelete(10L, 3L)).hasMessageContaining("等待克隆");

        sample.setUpdatedAt(Instant.now().minus(VoiceSampleService.CLONE_SUBMIT_GRACE).minusSeconds(1));
        assertThat(service.get(10L, 3L).stalled()).isTrue();
        assertThat(service.beginClone(10L, 3L, "original").existingVoiceId()).isNull();
        assertThat(sample.getStatus()).isEqualTo("CLONING");
        // Resubmitting restarts the clock, so the fresh request is protected again.
        assertThat(service.get(10L, 3L).stalled()).isFalse();

        sample.setUpdatedAt(Instant.now().minus(VoiceSampleService.CLONE_SUBMIT_GRACE).minusSeconds(1));
        assertThat(service.beginDelete(10L, 3L).voiceId()).isNull();
        assertThat(sample.getStatus()).isEqualTo("DELETING");
    }

    @Test void aVoiceStillTrainingAtTheProviderIsNotMistakenForAnInterruptedSubmission() {
        sample.setStatus("CLONING"); sample.setProviderCode("original"); sample.setProviderVoiceId("voice-training");
        sample.setUpdatedAt(Instant.now().minusSeconds(3600));
        assertThat(service.get(10L, 3L).stalled()).isFalse();
        assertThatThrownBy(() -> service.beginDelete(10L, 3L)).hasMessageContaining("等待克隆");
    }

    @Test void deletionPersistsRemoteProgressAndRetainsConsentAuditAndStorageReference() {
        sample.setStatus("READY"); sample.setProviderCode("original"); sample.setProviderVoiceId("voice-existing");
        var consentAt = sample.getConsentAt();
        var input = service.beginDelete(10L, 3L);
        assertThat(input.voiceId()).isEqualTo("voice-existing");
        assertThatThrownBy(() -> service.finishDelete(10L, 3L)).hasMessageContaining("远端");
        assertThatThrownBy(() -> service.download(10L, 3L)).hasMessageContaining("暂不可播放");

        service.finishProviderDelete(10L, 3L, "voice-existing");
        assertThat(service.beginDelete(10L, 3L).voiceId()).isNull();
        service.finishDelete(10L, 3L);
        assertThat(sample.getStatus()).isEqualTo("DELETED");
        assertThat(sample.getConsentAt()).isEqualTo(consentAt);
        assertThat(sample.getConsentBy()).isEqualTo(3L);
        assertThat(sample.getConsentText()).isEqualTo(VoiceSampleService.CONSENT);
        assertThat(sample.getStorageKey()).isEqualTo("t1/voice-samples/original");
        assertThatThrownBy(() -> service.get(10L, 3L)).hasMessageContaining("不存在");
        verify(repository, never()).delete(any());
    }
}
