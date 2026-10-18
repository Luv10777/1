package com.wuyao.growth.voice;

import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.live.audio.PcmAudio;
import com.wuyao.growth.store.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Base64;
import java.util.List;
import java.time.Duration;

/** External requests intentionally happen outside database transactions. */
@Service @RequiredArgsConstructor
public class VoiceService {
    private final VoiceSampleService samples;
    private final VoiceProvider provider;
    private final ObjectStorage storage;
    private final StoreAccessService stores;
    private final List<VoiceUsageGuard> guards;
    /** 试听固定念这一句。改了文案要把 PREVIEW_VERSION 加一，旧的试听音频才不会被继续播放。 */
    static final String PREVIEW_TEXT = "欢迎来到我们的直播间，今天为大家介绍门店的商品，有任何问题都可以留言。";
    private static final String PREVIEW_VERSION = "v1";
    /** 克隆音色的试听音频紧挨着样本存放：同一个存储、同一个商户目录，删除样本时一起删。 */
    static String previewKey(String sampleKey) { return sampleKey + "-preview-" + PREVIEW_VERSION + ".wav"; }
    private String builtInPreviewKey(String voice) {
        return "voice-previews/%s/%s/%s-%s.wav".formatted(pathPart(provider.code()), pathPart(provider.model()), pathPart(voice), PREVIEW_VERSION);
    }
    private static String pathPart(String value) { return value == null ? "_" : value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    public VoiceDtos.Capabilities capabilities() {
        return new VoiceDtos.Capabilities(provider.code(), provider.configured(), true,
                provider.configured() ? "语音服务已配置，实际音质和延迟需试听验证。" : "尚未配置语音供应商。可以先保存授权样本、配对播报页并测试接线。",
                provider.builtInVoices(), provider.model());
    }
    public VoiceDtos.SampleView cloneVoice(Long id, Long userId) {
        if (!provider.configured()) throw VoiceSampleService.invalid("请先在服务端配置语音供应商账号");
        var sample = samples.getForManage(id, userId);
        // Reject local-only storage before changing a saved sample to CLONING.
        // An existing voice only needs a status query, not a readable sample URL.
        if (sample.providerVoiceId() == null)
            provider.validateSampleUrl(storage.presignGet(sample.storageKey(), Duration.ofMinutes(10)));
        var input = samples.beginClone(id, userId, provider.code());
        String voiceId = input.existingVoiceId();
        try {
            if (voiceId == null) voiceId = provider.createVoice(input.sampleUrl(), input.prefix());
            samples.recordCloneVoiceId(id, userId, voiceId);
        } catch (RuntimeException error) {
            // Preserve a returned ID so status retries do not create another voice.
            if (voiceId == null) samples.finishClone(id, userId, null, "FAILED", error instanceof BizException
                    ? error.getMessage() : "克隆请求失败，请检查供应商配置与样本格式后重试。");
            throw error;
        }
        return refresh(id, userId);
    }
    public VoiceDtos.SampleView refresh(Long id, Long userId) {
        var sample = samples.get(id, userId);
        if (!"CLONING".equals(sample.status()) || sample.providerVoiceId() == null) return sample;
        if (!provider.configured() || !provider.code().equals(sample.providerCode()))
            throw VoiceSampleService.invalid("请配置原语音供应商后查询训练状态");
        // A transient query failure leaves the saved ID and CLONING state intact.
        String status = provider.voiceStatus(sample.providerVoiceId());
        return switch (status) {
            case "OK" -> samples.refreshClone(id, userId, sample.providerVoiceId(), "READY", null);
            case "DEPLOYING" -> samples.refreshClone(id, userId, sample.providerVoiceId(), "CLONING", null);
            case "UNDEPLOYED" -> samples.refreshClone(id, userId, sample.providerVoiceId(), "FAILED", "音色审核未通过，请删除后使用清晰的单人录音重新创建。");
            default -> throw VoiceSampleService.invalid("供应商返回了未知音色状态，请稍后刷新");
        };
    }
    public void delete(Long id, Long userId) {
        var sample = samples.getForManage(id, userId);
        // The voice may be open to several stores; a session on air in any of them keeps it.
        for (Long storeId : sample.storeIds()) {
            for (VoiceUsageGuard guard : guards) {
                guard.inUse(storeId, id).ifPresent(reason -> { throw VoiceSampleService.invalid(reason); });
            }
        }
        var input = samples.beginDelete(id, userId);
        if (input.voiceId() != null) {
            if (!provider.code().equals(input.providerCode()) || !provider.configured())
                throw VoiceSampleService.invalid("需配置原语音供应商后才能同时删除远端音色与本地样本");
            provider.deleteVoice(input.voiceId());
            samples.finishProviderDelete(id, userId, input.voiceId());
        }
        // 试听音频也是这个人的声音，和样本一起删。
        storage.delete(previewKey(input.key()));
        storage.delete(input.key());
        if (storage.stat(input.key()).isPresent()) throw VoiceSampleService.invalid("样本尚未删除，请重试");
        samples.finishDelete(id, userId);
    }
    /**
     * 商户关闭后由删除任务调用，没有操作人。先删本地文件（最敏感，也不依赖供应商），再删远端音色；
     * 每一步都可以重复执行，任何一步失败整体重试即可。
     */
    public void purge(Long id) {
        var input = samples.purgeInput(id).orElse(null);
        if (input == null) return;
        storage.delete(previewKey(input.key()));
        storage.delete(input.key());
        if (storage.stat(input.key()).isPresent()) throw new IllegalStateException("声音样本文件仍未删除: sampleId=" + id);
        if (input.voiceId() != null) {
            if (!provider.code().equals(input.providerCode()) || !provider.configured())
                throw new IllegalStateException("需要配置原语音供应商才能删除远端音色: sampleId=" + id);
            provider.deleteVoice(input.voiceId());
            samples.finishProviderPurge(id, input.voiceId());
        }
        samples.finishPurge(id);
    }
    /**
     * Checks that the requested voice can be used by this store right now and returns the provider's
     * voice ID. No provider request is made, so callers can validate before queueing paid synthesis.
     */
    public String resolveVoice(Long storeId, Long sampleId, String builtInVoice, Long userId) {
        stores.requireAccess(storeId, userId);
        if (!provider.configured()) throw VoiceSampleService.invalid("请先在服务端配置语音供应商账号");
        if (sampleId == null) {
            if (!provider.builtInVoices().contains(builtInVoice)) throw VoiceSampleService.invalid("请选择可用的系统音色");
            return builtInVoice;
        }
        // Access to the store was checked above; what remains is whether the voice is open to this store.
        var sample = samples.openTo(sampleId, storeId);
        if (!"READY".equals(sample.status()) || !provider.code().equals(sample.providerCode()))
            throw VoiceSampleService.invalid("请选择当前门店已就绪的音色");
        if (!provider.supportsVoice(sample.providerVoiceId())) throw VoiceSampleService.invalid("该音色与当前合成模型不匹配，请使用原模型或重新创建音色");
        return sample.providerVoiceId();
    }
    /**
     * 试听地址。同一个音色只在第一次试听时合成，音频存进对象存储，之后直接签发地址。
     * 两个人同时第一次试听会各合成一次、写到同一个位置，结果一样。
     */
    public VoiceDtos.DownloadTicket preview(Long storeId, VoiceDtos.PreviewRequest request, Long userId) {
        String voice = resolveVoice(storeId, request.sampleId(), request.builtInVoice(), userId);
        String key = request.sampleId() == null ? builtInPreviewKey(voice)
                : previewKey(samples.openTo(request.sampleId(), storeId).storageKey());
        if (storage.stat(key).isEmpty()) {
            var audio = provider.synthesize(PREVIEW_TEXT, voice, bytes -> { });
            storage.put(key, PcmAudio.wav(audio.pcm(), audio.sampleRate()), "audio/wav");
        }
        return new VoiceDtos.DownloadTicket(storage.presignGet(key, Duration.ofMinutes(5)));
    }
    public VoiceProvider.Audio synthesizeAudio(Long storeId, VoiceDtos.SpeechRequest request, Long userId) {
        String voice = resolveVoice(storeId, request.sampleId(), request.builtInVoice(), userId);
        return provider.synthesize(request.text(), voice, bytes -> { });
    }
    public VoiceDtos.SpeechResult synthesize(Long storeId, VoiceDtos.SpeechRequest request, Long userId) {
        var audio = synthesizeAudio(storeId, request, userId);
        return new VoiceDtos.SpeechResult(provider.code(), Base64.getEncoder().encodeToString(PcmAudio.wav(audio.pcm(), audio.sampleRate())),
                "audio/wav", PcmAudio.durationMillis(audio.pcm(), audio.sampleRate()), PcmAudio.quietPoints(audio.pcm(), audio.sampleRate()), audio.firstAudioMillis());
    }
}
