package com.wuyao.growth.copywriting;

import com.wuyao.growth.brand.BrandService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.task.TaskTerminalFailure;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.store.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 学我口吻：门店贴几条自己发过的文案，模型总结口吻，之后生成时照着写。免费，每家店每天最多更新 10 次。 */
@Service
@RequiredArgsConstructor
public class CopyVoiceService {
    static final int DAILY_UPDATES = 10;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final CopyVoiceRepository voices;
    private final StoreAccessService storeAccess;
    private final BrandService brands;
    private final TaskService tasks;
    private final AiGateway gateway;

    @Transactional(readOnly = true)
    public CopyDtos.Voice get(Long storeId, Long userId) {
        storeAccess.requireAccess(storeId, userId);
        return view(voices.findByStoreId(storeId).orElse(null), storeId);
    }

    @Transactional
    public CopyDtos.Voice update(CopyDtos.VoiceUpdate request, Long userId) {
        storeAccess.requireAccess(request.storeId(), userId);
        if (!CopyModel.configured(gateway)) throw BizException.of(ErrorCode.COPY_NOT_CONFIGURED, "文案模型尚未配置");
        var samples = request.samples().stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        if (samples.size() < 3) throw BizException.of(ErrorCode.BAD_REQUEST, "至少贴 3 条不同的文案");
        var voice = voices.lockByStoreId(request.storeId()).orElseGet(() -> {
            var created = new CopyVoiceProfile();
            created.setTenantId(TenantContext.require());
            created.setStoreId(request.storeId());
            return created;
        });
        var today = LocalDate.now(ZONE);
        int used = today.equals(voice.getUpdateDay()) ? voice.getUpdateCount() : 0;
        if (used >= DAILY_UPDATES) throw BizException.of(ErrorCode.RATE_LIMITED, "今天已经更新 " + DAILY_UPDATES + " 次了，明天再来");
        voice.setUpdateDay(today);
        voice.setUpdateCount(used + 1);
        voice.setSamples(new ArrayList<>(samples));
        voice.setRevision(voice.getRevision() + 1);
        voice.setStatus("LEARNING");
        voice.setErrorMessage(null);
        voices.saveAndFlush(voice);
        var task = tasks.submit(CopyVoiceProfileHandler.TYPE, "DEFAULT",
                Map.of("storeId", request.storeId(), "revision", voice.getRevision()),
                "copy-voice-" + voice.getId() + "-" + voice.getRevision(), userId);
        voice.setTaskId(task.getId());
        return view(voice, request.storeId());
    }

    @Transactional(readOnly = true)
    public CopyVoiceProfile processing(Long storeId) {
        return voices.findByStoreId(storeId).orElseThrow(() -> new IllegalStateException("口吻样本不存在"));
    }

    /** 只写回同一版样本的总结；总结期间样本又改了的，丢弃这次结果。 */
    @Transactional
    public boolean learned(Long storeId, int revision, Map<String, Object> profile) {
        var voice = voices.lockByStoreId(storeId).orElse(null);
        if (voice == null || voice.getRevision() != revision || !"LEARNING".equals(voice.getStatus())) return false;
        voice.setProfile(profile);
        voice.setStatus("READY");
        return true;
    }

    @Transactional
    public void learnFailed(Long storeId, int revision, String message) {
        var voice = voices.lockByStoreId(storeId).orElse(null);
        if (voice == null || voice.getRevision() != revision || !"LEARNING".equals(voice.getStatus())) return;
        // 之前学会的口吻继续可用，只记下这次没学成。
        voice.setStatus(voice.getProfile() == null ? "FAILED" : "READY");
        voice.setErrorMessage(message);
    }

    @Transactional
    public void taskFailed(TaskTerminalFailure event) {
        if (event.payload().get("storeId") instanceof Number store && event.payload().get("revision") instanceof Number revision) {
            learnFailed(store.longValue(), revision.intValue(), "口吻没有学成，请稍后再试");
        }
    }

    private CopyDtos.Voice view(CopyVoiceProfile voice, Long storeId) {
        String brandStyle = brands.profileForStore(storeId).map(b -> b.languageStyle()).filter(Objects::nonNull)
                .map(String::strip).filter(s -> !s.isEmpty()).orElse(null);
        if (voice == null) return new CopyDtos.Voice("EMPTY", List.of(), null, List.of(), brandStyle, DAILY_UPDATES, null, null);
        var profile = voice.getProfile();
        List<String> traits = profile != null && profile.get("traits") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        int used = LocalDate.now(ZONE).equals(voice.getUpdateDay()) ? voice.getUpdateCount() : 0;
        return new CopyDtos.Voice(voice.getStatus(), voice.getSamples(), profile == null ? null : String.valueOf(profile.get("summary")),
                traits, brandStyle, Math.max(0, DAILY_UPDATES - used), voice.getErrorMessage(), voice.getUpdatedAt());
    }
}
