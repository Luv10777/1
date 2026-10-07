package com.wuyao.growth.voice;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.AccountService;
import com.wuyao.growth.store.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 声音样本归商户所有，能在哪些门店使用由 voice_sample_stores 决定。
 * 上传、克隆、改名、删除和调整开放范围只有老板能做；能进某家门店的人可以使用开放给这家店的声音。
 */
@Service @RequiredArgsConstructor
public class VoiceSampleService {
    static final String CONSENT = "我确认这是本人声音，或已获得声音所有者授权用于声音克隆与直播播报。";
    /** Longer than any clone submission can take (the provider call times out at 45 seconds). */
    static final Duration CLONE_SUBMIT_GRACE = Duration.ofMinutes(2);
    private final VoiceSampleRepository samples;
    private final VoiceSampleStoreRepository grants;
    private final StoreAccessService stores;
    private final AccountService accounts;
    private final ObjectStorage storage;
    private final List<VoiceUsageGuard> guards;
    @Value("${growth.voice.sample-storage:minio}")
    private String sampleStorage = "minio";

    @Transactional(readOnly = true)
    public List<VoiceDtos.SampleView> list(Long storeId, Long userId) {
        stores.requireAccess(storeId, userId);
        List<VoiceSample> open = samples.findOpenTo(storeId);
        Map<Long, List<Long>> storeIds = grants.findBySampleIdInOrderByStoreIdAsc(open.stream().map(VoiceSample::getId).toList())
                .stream().collect(Collectors.groupingBy(VoiceSampleStore::getSampleId,
                        Collectors.mapping(VoiceSampleStore::getStoreId, Collectors.toList())));
        return open.stream().map(sample -> VoiceDtos.SampleView.of(sample, storeIds.getOrDefault(sample.getId(), List.of()))).toList();
    }
    @Transactional
    public VoiceDtos.UploadTicket upload(Long storeId, VoiceDtos.UploadRequest request, Long userId) {
        // 先确认门店，再确认身份：别的商户的人得到的是"门店不存在"，而不是一句暴露门店存在的"无权操作"。
        stores.requireAccess(storeId, userId);
        accounts.requireOwner(userId);
        if (!Boolean.TRUE.equals(request.consent())) throw invalid("请确认声音属于本人或已获得授权");
        VoiceSample sample = new VoiceSample();
        sample.setTenantId(TenantContext.require()); sample.setStoreId(storeId);
        sample.setName(request.name().trim()); sample.setMimeType(request.mimeType());
        if (!Set.of("minio", "cos").contains(sampleStorage)) throw invalid("声音样本存储配置必须为 minio 或 cos");
        sample.setStorageKey(("cos".equals(sampleStorage) ? "cos/" : "")
                + "t%d/voice-samples/%s".formatted(TenantContext.require(), UUID.randomUUID()));
        sample.setConsentAt(Instant.now()); sample.setConsentBy(userId); sample.setConsentText(CONSENT);
        sample = samples.saveAndFlush(sample);
        // 新样本先只开放给上传时所在的门店；要给别的门店用，由老板另外开放。
        grant(sample, storeId, userId);
        return new VoiceDtos.UploadTicket(VoiceDtos.SampleView.of(sample, List.of(storeId)), storage.presignPut(sample.getStorageKey(), Duration.ofMinutes(10)));
    }
    @Transactional
    public VoiceDtos.SampleView confirm(Long id, long size, Long userId) {
        VoiceSample sample = manage(id, userId);
        if (!"PENDING_UPLOAD".equals(sample.getStatus())) return view(sample);
        var object = storage.stat(sample.getStorageKey()).orElseThrow(() -> invalid("文件尚未上传完成"));
        if (size != object.sizeBytes() || size <= 0 || size > 10 * 1024 * 1024) throw invalid("音频大小不符或超过 10 MB");
        if (!sample.getMimeType().equals(object.contentType())) throw invalid("上传音频格式与申请时不一致");
        sample.setSizeBytes(size); sample.setStatus("UPLOADED"); sample.setUpdatedAt(Instant.now());
        return view(sample);
    }
    @Transactional(readOnly = true)
    public VoiceDtos.DownloadTicket download(Long id, Long userId) {
        VoiceSample sample = use(id, userId, false);
        if (Set.of("PENDING_UPLOAD", "DELETING").contains(sample.getStatus())) throw invalid("样本暂不可播放");
        return new VoiceDtos.DownloadTicket(storage.presignGet(sample.getStorageKey(), Duration.ofMinutes(5)));
    }
    @Transactional
    public VoiceDtos.SampleView rename(Long id, String name, Long userId) {
        VoiceSample sample = manage(id, userId);
        sample.setName(name.trim()); sample.setUpdatedAt(Instant.now());
        return view(sample);
    }
    /**
     * 老板决定这个声音开放给哪些门店。至少保留一家；正在某家店的直播里使用时，不能把那家店去掉。
     */
    @Transactional
    public VoiceDtos.SampleView setStores(Long id, List<Long> storeIds, Long userId) {
        VoiceSample sample = manage(id, userId);
        Set<Long> wanted = new LinkedHashSet<>(storeIds);
        if (wanted.isEmpty()) throw invalid("至少保留一家门店；不再需要这个声音时请直接删除");
        // 老板对本商户所有营业中的门店都有权限，这里实际核对的是门店存在且属于本商户。
        wanted.forEach(storeId -> stores.requireAccess(storeId, userId));
        List<VoiceSampleStore> current = grants.findBySampleIdOrderByStoreIdAsc(id);
        List<VoiceSampleStore> revoked = current.stream().filter(grant -> !wanted.contains(grant.getStoreId())).toList();
        for (VoiceSampleStore grant : revoked) {
            for (VoiceUsageGuard guard : guards) {
                guard.inUse(grant.getStoreId(), id).ifPresent(reason -> { throw invalid(reason); });
            }
        }
        grants.deleteAll(revoked);
        grants.flush();
        Set<Long> kept = current.stream().map(VoiceSampleStore::getStoreId).collect(Collectors.toSet());
        wanted.stream().filter(storeId -> !kept.contains(storeId)).forEach(storeId -> grant(sample, storeId, userId));
        sample.setUpdatedAt(Instant.now());
        return VoiceDtos.SampleView.of(sample, wanted.stream().sorted().toList());
    }
    /** 这个样本是否开放给这家门店；不是的话按"选错了音色"处理，不透露它是否存在。 */
    @Transactional(readOnly = true)
    public VoiceDtos.SampleView openTo(Long id, Long storeId) {
        VoiceSample sample = samples.findById(id)
                .filter(value -> TenantContext.require().equals(value.getTenantId()) && !"DELETED".equals(value.getStatus()))
                .filter(value -> grants.existsBySampleIdAndStoreId(id, storeId))
                .orElseThrow(() -> invalid("请选择当前门店已就绪的音色"));
        return view(sample);
    }
    @Transactional
    public CloneInput beginClone(Long id, Long userId, String provider) {
        VoiceSample sample = manage(id, userId);
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
        VoiceSample sample = manage(id, userId);
        if (!"CLONING".equals(sample.getStatus()) || voiceId == null || voiceId.isBlank()) throw invalid("克隆音色 ID 或状态无效");
        sample.setProviderVoiceId(voiceId); sample.setUpdatedAt(Instant.now());
    }
    @Transactional
    public VoiceDtos.SampleView refreshClone(Long id, Long userId, String voiceId, String status, String error) {
        // Recording what the provider reports is not a management decision: anyone who can use the voice may trigger it.
        VoiceSample sample = use(id, userId, true);
        // A concurrent refresh or delete may already have advanced the state.
        if (!"CLONING".equals(sample.getStatus()) || !voiceId.equals(sample.getProviderVoiceId())) return view(sample);
        sample.setStatus(status); sample.setErrorMessage(error); sample.setUpdatedAt(Instant.now());
        return view(sample);
    }
    @Transactional
    public VoiceDtos.SampleView finishClone(Long id, Long userId, String voiceId, String status, String error) {
        VoiceSample sample = manage(id, userId);
        if (!"CLONING".equals(sample.getStatus())) throw invalid("样本状态已改变");
        sample.setProviderVoiceId(voiceId); sample.setStatus(status);
        sample.setErrorMessage(error); sample.setUpdatedAt(Instant.now());
        return view(sample);
    }
    @Transactional(readOnly = true)
    public VoiceDtos.SampleView get(Long id, Long userId) { return view(use(id, userId, false)); }
    /** The same sample, for someone about to change it: refused unless they are the owner. */
    @Transactional(readOnly = true)
    public VoiceDtos.SampleView getForManage(Long id, Long userId) {
        VoiceSample sample = find(id, false);
        accounts.requireOwner(userId);
        return view(sample);
    }
    @Transactional
    public DeleteInput beginDelete(Long id, Long userId) {
        VoiceSample sample = manage(id, userId);
        if ("CLONING".equals(sample.getStatus()) && !stalled(sample)) throw invalid("请等待克隆请求结束后再删除");
        sample.setStatus("DELETING"); sample.setUpdatedAt(Instant.now());
        return new DeleteInput(sample.getStorageKey(), sample.getProviderVoiceId(), sample.getProviderCode());
    }
    @Transactional
    public void finishProviderDelete(Long id, Long userId, String voiceId) {
        VoiceSample sample = manage(id, userId);
        if (!"DELETING".equals(sample.getStatus())) throw invalid("样本状态已改变");
        if (sample.getProviderVoiceId() != null && !sample.getProviderVoiceId().equals(voiceId))
            throw invalid("远端音色已改变，请重新删除");
        // Commit remote progress before deleting bytes so a local failure can safely retry.
        sample.setProviderVoiceId(null); sample.setUpdatedAt(Instant.now());
    }
    @Transactional
    public void finishDelete(Long id, Long userId) {
        VoiceSample sample = manage(id, userId);
        if (!"DELETING".equals(sample.getStatus()) || sample.getProviderVoiceId() != null)
            throw invalid("请先完成远端音色与样本删除");
        sample.setStatus("DELETED"); sample.setProviderVoiceId(null); sample.setUpdatedAt(Instant.now());
        // Keep consent audit and original storage key; object bytes are deleted separately.
        // A deleted voice is open to no store.
        grants.deleteAll(grants.findBySampleIdOrderByStoreIdAsc(id));
    }
    /** For changing the sample itself or where it may be used: the owner only. */
    private VoiceSample manage(Long id, Long userId) {
        VoiceSample sample = find(id, true);
        accounts.requireOwner(userId);
        return sample;
    }
    /** For listening to it or speaking with it: the owner, or anyone who can enter a store it is open to. */
    private VoiceSample use(Long id, Long userId, boolean lock) {
        VoiceSample sample = find(id, lock);
        if (!accounts.isOwner(userId) && grants.findBySampleIdOrderByStoreIdAsc(id).stream()
                .noneMatch(grant -> stores.hasAccess(grant.getStoreId(), userId))) {
            throw BizException.of(ErrorCode.FORBIDDEN, "没有使用这个声音的权限");
        }
        return sample;
    }
    private VoiceSample find(Long id, boolean lock) {
        return (lock ? samples.lockById(id) : samples.findById(id))
                .filter(value -> TenantContext.require().equals(value.getTenantId()) && !"DELETED".equals(value.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "声音样本不存在"));
    }
    private void grant(VoiceSample sample, Long storeId, Long userId) {
        VoiceSampleStore grant = new VoiceSampleStore();
        grant.setTenantId(sample.getTenantId()); grant.setSampleId(sample.getId());
        grant.setStoreId(storeId); grant.setGrantedBy(userId);
        grants.save(grant);
    }
    private VoiceDtos.SampleView view(VoiceSample sample) {
        return VoiceDtos.SampleView.of(sample, grants.findBySampleIdOrderByStoreIdAsc(sample.getId()).stream()
                .map(VoiceSampleStore::getStoreId).toList());
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
