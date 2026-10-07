package com.wuyao.growth.voice;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.store.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class VoiceSampleService {
    static final String CONSENT = "我确认这是本人声音，或已获得声音所有者授权用于声音克隆与直播播报。";
    /** Longer than any clone submission can take (the provider call times out at 45 seconds). */
    static final Duration CLONE_SUBMIT_GRACE = Duration.ofMinutes(2);
    private final VoiceSampleRepository samples;
    private final StoreAccessService stores;
    private final ObjectStorage storage;
    @Value("${growth.voice.sample-storage:minio}")
    private String sampleStorage = "minio";

    @Transactional(readOnly = true)
    public List<VoiceDtos.SampleView> list(Long storeId, Long userId) {
        stores.requireAccess(storeId, userId);
        return samples.findByStoreIdAndStatusNotOrderByIdDesc(storeId, "DELETED").stream().map(VoiceDtos.SampleView::of).toList();
    }
    @Transactional
    public VoiceDtos.UploadTicket upload(Long storeId, VoiceDtos.UploadRequest request, Long userId) {
        stores.requireAccess(storeId, userId);
        if (!Boolean.TRUE.equals(request.consent())) throw invalid("请确认声音属于本人或已获得授权");
        VoiceSample sample = new VoiceSample();
        sample.setTenantId(TenantContext.require()); sample.setStoreId(storeId);
        sample.setName(request.name().trim()); sample.setMimeType(request.mimeType());
        if (!Set.of("minio", "cos").contains(sampleStorage)) throw invalid("声音样本存储配置必须为 minio 或 cos");
        sample.setStorageKey(("cos".equals(sampleStorage) ? "cos/" : "")
                + "t%d/voice-samples/%s".formatted(TenantContext.require(), UUID.randomUUID()));
        sample.setConsentAt(Instant.now()); sample.setConsentBy(userId); sample.setConsentText(CONSENT);
        sample = samples.saveAndFlush(sample);
        return new VoiceDtos.UploadTicket(VoiceDtos.SampleView.of(sample), storage.presignPut(sample.getStorageKey(), Duration.ofMinutes(10)));
    }
    @Transactional
    public VoiceDtos.SampleView confirm(Long id, long size, Long userId) {
        VoiceSample sample = require(id, userId, true);
        if (!"PENDING_UPLOAD".equals(sample.getStatus())) return VoiceDtos.SampleView.of(sample);
        var object = storage.stat(sample.getStorageKey()).orElseThrow(() -> invalid("文件尚未上传完成"));
        if (size != object.sizeBytes() || size <= 0 || size > 10 * 1024 * 1024) throw invalid("音频大小不符或超过 10 MB");
        if (!sample.getMimeType().equals(object.contentType())) throw invalid("上传音频格式与申请时不一致");
        sample.setSizeBytes(size); sample.setStatus("UPLOADED"); sample.setUpdatedAt(Instant.now());
        return VoiceDtos.SampleView.of(sample);
    }
    @Transactional(readOnly = true)
    public VoiceDtos.DownloadTicket download(Long id, Long userId) {
        VoiceSample sample = require(id, userId, false);
        if (Set.of("PENDING_UPLOAD", "DELETING").contains(sample.getStatus())) throw invalid("样本暂不可播放");
        return new VoiceDtos.DownloadTicket(storage.presignGet(sample.getStorageKey(), Duration.ofMinutes(5)));
    }
    @Transactional
    public VoiceDtos.SampleView rename(Long id, String name, Long userId) {
        VoiceSample sample = require(id, userId, true);
        sample.setName(name.trim()); sample.setUpdatedAt(Instant.now());
        return VoiceDtos.SampleView.of(sample);
    }
    @Transactional
    public CloneInput beginClone(Long id, Long userId, String provider) {
        VoiceSample sample = require(id, userId, true);
        if (!Set.of("UPLOADED", "FAILED").contains(sample.getStatus()) && !stalled(sample)) throw invalid("请先上传样本，或等待当前克隆完成");
        if (sample.getProviderVoiceId() != null && !provider.equals(sample.getProviderCode()))
            throw invalid("该样本已有其他供应商音色，请配置原供应商后重试或先删除样本");
        sample.setStatus("CLONING"); sample.setErrorMessage(null); sample.setProviderCode(provider);
        sample.setUpdatedAt(Instant.now());
        return new CloneInput(id, storage.presignGet(sample.getStorageKey(), Duration.ofMinutes(10)),
                "w" + Long.toString(sample.getId(), 36), sample.getProviderVoiceId());
    }
    @Transactional
    public void recordCloneVoiceId(Long id, Long userId, String voiceId) {
        VoiceSample sample = require(id, userId, true);
        if (!"CLONING".equals(sample.getStatus()) || voiceId == null || voiceId.isBlank()) throw invalid("克隆音色 ID 或状态无效");
        sample.setProviderVoiceId(voiceId); sample.setUpdatedAt(Instant.now());
    }
    @Transactional
    public VoiceDtos.SampleView refreshClone(Long id, Long userId, String voiceId, String status, String error) {
        VoiceSample sample = require(id, userId, true);
        // A concurrent refresh or delete may already have advanced the state.
        if (!"CLONING".equals(sample.getStatus()) || !voiceId.equals(sample.getProviderVoiceId())) return VoiceDtos.SampleView.of(sample);
        sample.setStatus(status); sample.setErrorMessage(error); sample.setUpdatedAt(Instant.now());
        return VoiceDtos.SampleView.of(sample);
    }
    @Transactional
    public VoiceDtos.SampleView finishClone(Long id, Long userId, String voiceId, String status, String error) {
        VoiceSample sample = require(id, userId, true);
        if (!"CLONING".equals(sample.getStatus())) throw invalid("样本状态已改变");
        sample.setProviderVoiceId(voiceId); sample.setStatus(status);
        sample.setErrorMessage(error); sample.setUpdatedAt(Instant.now());
        return VoiceDtos.SampleView.of(sample);
    }
    @Transactional(readOnly = true)
    public VoiceDtos.SampleView get(Long id, Long userId) { return VoiceDtos.SampleView.of(require(id, userId, false)); }
    @Transactional
    public DeleteInput beginDelete(Long id, Long userId) {
        VoiceSample sample = require(id, userId, true);
        if ("CLONING".equals(sample.getStatus()) && !stalled(sample)) throw invalid("请等待克隆请求结束后再删除");
        sample.setStatus("DELETING"); sample.setUpdatedAt(Instant.now());
        return new DeleteInput(sample.getStorageKey(), sample.getProviderVoiceId(), sample.getProviderCode());
    }
    @Transactional
    public void finishProviderDelete(Long id, Long userId, String voiceId) {
        VoiceSample sample = require(id, userId, true);
        if (!"DELETING".equals(sample.getStatus())) throw invalid("样本状态已改变");
        if (sample.getProviderVoiceId() != null && !sample.getProviderVoiceId().equals(voiceId))
            throw invalid("远端音色已改变，请重新删除");
        // Commit remote progress before deleting bytes so a local failure can safely retry.
        sample.setProviderVoiceId(null); sample.setUpdatedAt(Instant.now());
    }
    @Transactional
    public void finishDelete(Long id, Long userId) {
        VoiceSample sample = require(id, userId, true);
        if (!"DELETING".equals(sample.getStatus()) || sample.getProviderVoiceId() != null)
            throw invalid("请先完成远端音色与样本删除");
        sample.setStatus("DELETED"); sample.setProviderVoiceId(null); sample.setUpdatedAt(Instant.now());
        // Keep consent audit and original storage key; object bytes are deleted separately.
    }
    private VoiceSample require(Long id, Long userId, boolean lock) {
        VoiceSample sample = (lock ? samples.lockById(id) : samples.findById(id))
                .filter(value -> TenantContext.require().equals(value.getTenantId()) && !"DELETED".equals(value.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "声音样本不存在"));
        stores.requireAccess(sample.getStoreId(), userId);
        return sample;
    }
    /**
     * CLONING with no provider voice, long after the request should have returned: the process that
     * was submitting it is gone (a restart, a crash). Nothing will ever move it on, so it has to be
     * possible to submit again or delete it. If the provider did create a voice in that window it is
     * left behind there; that is the price of not leaving the sample stuck here.
     */
    static boolean stalled(VoiceSample sample) {
        return "CLONING".equals(sample.getStatus()) && sample.getProviderVoiceId() == null
                && sample.getUpdatedAt().isBefore(Instant.now().minus(CLONE_SUBMIT_GRACE));
    }
    static BizException invalid(String message) { return BizException.of(ErrorCode.BAD_REQUEST, message); }
    record CloneInput(Long id, String sampleUrl, String prefix, String existingVoiceId) { }
    record DeleteInput(String key, String voiceId, String providerCode) { }
}
