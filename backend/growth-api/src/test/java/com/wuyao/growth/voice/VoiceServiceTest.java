package com.wuyao.growth.voice;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.store.StoreAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceServiceTest {
    private final VoiceSampleRepository repository = mock(VoiceSampleRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final StoreAccessService stores = mock(StoreAccessService.class);
    private final VoiceProvider provider = mock(VoiceProvider.class);
    private final VoiceSampleService samples = new VoiceSampleService(repository, stores, storage);
    private final VoiceUsageGuard guard = mock(VoiceUsageGuard.class);
    private final VoiceService service = new VoiceService(samples, provider, storage, stores, List.of(guard));
    private VoiceSample sample;

    @BeforeEach void setup() {
        TenantContext.set(1L);
        sample = new VoiceSample(); sample.setId(10L); sample.setTenantId(1L); sample.setStoreId(2L);
        sample.setName("声音"); sample.setStatus("UPLOADED"); sample.setStorageKey("sample-key");
        sample.setConsentAt(Instant.now()); sample.setConsentBy(3L); sample.setConsentText(VoiceSampleService.CONSENT);
        when(repository.lockById(10L)).thenReturn(Optional.of(sample));
        when(repository.findById(10L)).thenReturn(Optional.of(sample));
        when(storage.presignGet(eq("sample-key"), any())).thenReturn("https://storage.test/sample");
        when(provider.code()).thenReturn("test-provider"); when(provider.configured()).thenReturn(true);
        when(provider.builtInVoices()).thenReturn(List.of("builtin"));
        when(provider.supportsVoice(anyString())).thenReturn(true);
        when(provider.createVoice(anyString(), anyString())).thenReturn("voice-new");
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void unconfiguredProviderCannotMarkAnyVoiceReady() {
        when(provider.configured()).thenReturn(false);
        assertThatThrownBy(() -> service.cloneVoice(10L, 3L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.synthesizeAudio(2L, new VoiceDtos.SpeechRequest("你好", null, "builtin"), 3L))
                .isInstanceOf(BizException.class);
        assertThat(sample.getStatus()).isEqualTo("UPLOADED");
        verifyNoInteractions(repository, storage);
        verify(provider, never()).createVoice(anyString(), anyString());
        verify(provider, never()).synthesize(anyString(), anyString(), any());
    }

    @Test void notYetReadyCloneRetainsItsIdAndRetryOnlyQueriesExistingVoice() {
        when(provider.voiceStatus("voice-new")).thenReturn("DEPLOYING", "OK");
        assertThat(service.cloneVoice(10L, 3L).status()).isEqualTo("CLONING");
        assertThat(sample.getProviderVoiceId()).isEqualTo("voice-new");
        assertThat(service.refresh(10L, 3L).status()).isEqualTo("READY");
        verify(provider, times(1)).createVoice("https://storage.test/sample", "wa");
        verify(provider, times(2)).voiceStatus("voice-new");
        assertThat(sample.getErrorMessage()).isNull();
    }

    @Test void inaccessibleSampleUrlDoesNotStartOrFailTraining() {
        when(storage.presignGet(eq("sample-key"), any())).thenReturn("http://localhost:39000/sample");
        doThrow(VoiceSampleService.invalid("样本已保存，请配置公网 HTTPS 地址"))
                .when(provider).validateSampleUrl("http://localhost:39000/sample");
        assertThatThrownBy(() -> service.cloneVoice(10L, 3L)).hasMessageContaining("公网 HTTPS");
        assertThat(sample.getStatus()).isEqualTo("UPLOADED");
        assertThat(sample.getProviderVoiceId()).isNull();
        verify(repository, never()).lockById(anyLong());
        verify(provider, never()).createVoice(anyString(), anyString());
    }

    @Test void knownCloneFailureRetainsActionableMessage() {
        when(provider.createVoice(anyString(), anyString())).thenThrow(VoiceSampleService.invalid("音频存储地址不可访问"));
        assertThatThrownBy(() -> service.cloneVoice(10L, 3L)).hasMessageContaining("音频存储地址不可访问");
        assertThat(sample.getErrorMessage()).isEqualTo("音频存储地址不可访问");
    }

    @Test void existingVoiceDoesNotRequirePublicSampleToQueryAgain() {
        sample.setStatus("FAILED"); sample.setProviderCode("test-provider"); sample.setProviderVoiceId("voice-existing");
        when(provider.voiceStatus("voice-existing")).thenReturn("OK");
        assertThat(service.cloneVoice(10L, 3L).status()).isEqualTo("READY");
        verify(provider, never()).validateSampleUrl(anyString());
        verify(provider, never()).createVoice(anyString(), anyString());
    }

    @Test void queryFailureRetainsCreatedIdSoRetryDoesNotCreateAnotherBillableVoice() {
        when(provider.voiceStatus("voice-new")).thenThrow(new IllegalStateException("network"));
        assertThatThrownBy(() -> service.cloneVoice(10L, 3L)).isInstanceOf(IllegalStateException.class);
        assertThat(sample.getStatus()).isEqualTo("CLONING");
        assertThat(sample.getProviderVoiceId()).isEqualTo("voice-new");
        doReturn("OK").when(provider).voiceStatus("voice-new");
        assertThat(service.refresh(10L, 3L).status()).isEqualTo("READY");
        verify(provider, times(1)).createVoice(anyString(), anyString());
    }

    @Test void failedRemoteDeleteKeepsBytesAndRemoteIdForRetry() {
        readySample();
        doThrow(new IllegalStateException("remote unavailable")).doNothing().when(provider).deleteVoice("voice-existing");
        assertThatThrownBy(() -> service.delete(10L, 3L)).isInstanceOf(IllegalStateException.class);
        assertThat(sample.getStatus()).isEqualTo("DELETING");
        assertThat(sample.getProviderVoiceId()).isEqualTo("voice-existing");
        verify(storage, never()).delete(anyString());

        service.delete(10L, 3L);
        assertThat(sample.getStatus()).isEqualTo("DELETED");
        verify(provider, times(2)).deleteVoice("voice-existing");
        verify(storage).delete("sample-key");
    }

    @Test void aVoiceThatSomethingStillDependsOnIsNotDeletedAndNothingIsTouched() {
        readySample();
        when(guard.inUse(2L, 10L)).thenReturn(Optional.of("这个音色正在场次「晚间场」中使用，结束这一场后才能删除"));

        assertThatThrownBy(() -> service.delete(10L, 3L)).isInstanceOf(BizException.class).hasMessageContaining("晚间场");

        assertThat(sample.getStatus()).isEqualTo("READY");
        verify(provider, never()).deleteVoice(anyString());
        verify(storage, never()).delete(anyString());
    }

    @Test void failedLocalDeleteRetriesWithoutDeletingRemoteVoiceTwiceAndPreservesAudit() {
        readySample();
        var consentAt = sample.getConsentAt();
        doThrow(new IllegalStateException("storage unavailable")).doNothing().when(storage).delete("sample-key");
        assertThatThrownBy(() -> service.delete(10L, 3L)).isInstanceOf(IllegalStateException.class);
        assertThat(sample.getStatus()).isEqualTo("DELETING");
        assertThat(sample.getProviderVoiceId()).isNull();

        service.delete(10L, 3L);
        verify(provider, times(1)).deleteVoice("voice-existing");
        verify(storage, times(2)).delete("sample-key");
        assertThat(sample.getStatus()).isEqualTo("DELETED");
        assertThat(sample.getConsentAt()).isEqualTo(consentAt);
        assertThat(sample.getConsentBy()).isEqualTo(3L);
        assertThat(sample.getStorageKey()).isEqualTo("sample-key");
    }

    @Test void deletionIsNotCompleteUntilStorageConfirmsAbsence() {
        when(storage.stat("sample-key")).thenReturn(Optional.of(new ObjectStorage.StoredObject(100, "audio/wav")), Optional.empty());
        assertThatThrownBy(() -> service.delete(10L, 3L)).hasMessageContaining("尚未删除");
        assertThat(sample.getStatus()).isEqualTo("DELETING");
        service.delete(10L, 3L);
        assertThat(sample.getStatus()).isEqualTo("DELETED");
    }

    @Test void synthesisRejectsOtherStoreUnreadyAndDifferentProviderVoices() {
        readySample();
        var request = new VoiceDtos.SpeechRequest("你好", 10L, null);
        assertThatThrownBy(() -> service.synthesizeAudio(99L, request, 3L)).hasMessageContaining("当前门店");
        sample.setStatus("FAILED");
        assertThatThrownBy(() -> service.synthesizeAudio(2L, request, 3L)).hasMessageContaining("已就绪");
        sample.setStatus("READY"); sample.setProviderCode("other-provider");
        assertThatThrownBy(() -> service.synthesizeAudio(2L, request, 3L)).hasMessageContaining("已就绪");
        verify(provider, never()).synthesize(anyString(), anyString(), any());
    }

    @Test void synthesisUsesApprovedVoiceAndReturnsRealAudioMetadata() {
        var pcm = new byte[24000 * 2];
        when(provider.synthesize(eq("你好"), eq("builtin"), any())).thenReturn(new VoiceProvider.Audio(pcm, 24000, 123));
        var result = service.synthesize(2L, new VoiceDtos.SpeechRequest("你好", null, "builtin"), 3L);
        assertThat(result.durationMillis()).isEqualTo(1000);
        assertThat(result.firstAudioMillis()).isEqualTo(123);
        assertThat(java.util.Base64.getDecoder().decode(result.audioBase64())).hasSize(pcm.length + 44);
        assertThat(result.pauseOffsetsMillis()).containsExactly(620L);
    }

    @Test void createdIdIsSavedBeforeFirstStatusRequest() {
        when(provider.voiceStatus("voice-new")).thenAnswer(call -> {
            assertThat(sample.getProviderVoiceId()).isEqualTo("voice-new");
            assertThat(sample.getStatus()).isEqualTo("CLONING");
            return "DEPLOYING";
        });
        service.cloneVoice(10L, 3L);
        assertThat(sample.getErrorMessage()).isNull();
    }

    @Test void rejectedAndUnknownProviderStatesAreNotReady() {
        when(provider.voiceStatus("voice-new")).thenReturn("unexpected", "UNDEPLOYED");
        assertThatThrownBy(() -> service.cloneVoice(10L, 3L)).hasMessageContaining("未知");
        assertThat(sample.getStatus()).isEqualTo("CLONING");
        assertThat(service.refresh(10L, 3L).status()).isEqualTo("FAILED");
        assertThat(sample.getErrorMessage()).contains("审核未通过");
        verify(provider, times(1)).createVoice(anyString(), anyString());
    }

    @Test void refreshEnforcesStoreAndTenantAccessBeforeQueryingProvider() {
        sample.setStatus("CLONING"); sample.setProviderCode("test-provider"); sample.setProviderVoiceId("voice-new");
        sample.setTenantId(99L);
        assertThatThrownBy(() -> service.refresh(10L, 3L)).hasMessageContaining("不存在");
        sample.setTenantId(1L);
        doThrow(new IllegalStateException("store denied")).when(stores).requireAccess(2L, 3L);
        assertThatThrownBy(() -> service.refresh(10L, 3L)).hasMessageContaining("store denied");
        verify(provider, never()).voiceStatus(anyString());
    }

    @Test void clonedSynthesisUsesPersistedProviderIdAndRejectsModelMismatch() {
        readySample();
        var request = new VoiceDtos.SpeechRequest("你好", 10L, null);
        when(provider.synthesize(eq("你好"), eq("voice-existing"), any())).thenReturn(new VoiceProvider.Audio(new byte[2], 24000, 1));
        service.synthesizeAudio(2L, request, 3L);
        verify(provider).synthesize(eq("你好"), eq("voice-existing"), any());
        when(provider.supportsVoice("voice-existing")).thenReturn(false);
        assertThatThrownBy(() -> service.synthesizeAudio(2L, request, 3L)).hasMessageContaining("模型不匹配");
        verify(provider, times(1)).synthesize(anyString(), anyString(), any());
    }

    private void readySample() {
        sample.setStatus("READY"); sample.setProviderCode("test-provider"); sample.setProviderVoiceId("voice-existing");
    }
}
