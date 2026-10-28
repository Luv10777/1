package com.wuyao.growth.copywriting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.brand.BrandDtos;
import com.wuyao.growth.brand.BrandService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.task.TaskTerminalFailure;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.store.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「今日宜发」：每家门店每天一份。商家当天第一次打开首页时登记并交给任务生成；
 * 最近活跃的门店在凌晨预先生成。平台承担，不扣商家烟火。
 */
@Service
@RequiredArgsConstructor
public class CopyAlmanacService {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 生成失败后，过这么久再打开首页会重新生成一次。 */
    static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    static final Duration ACTIVE_WITHIN = Duration.ofDays(7);
    private final CopyAlmanacRepository almanacs;
    private final CopyBatchRepository batches;
    private final StoreAccessService storeAccess;
    private final BrandService brands;
    private final TaskService tasks;
    private final AiGateway gateway;
    private final ObjectMapper json;

    @Transactional
    public CopyDtos.Almanac today(Long storeId, Long userId) {
        storeAccess.requireAccess(storeId, userId);
        var brand = brands.brandForStore(storeId);
        String industry = CopyAlmanacPrompt.industryKey(brand.map(BrandDtos.StoreBrand::industry).orElse(null));
        String status = brand.isEmpty() ? "NO_BRAND" : CopyAlmanacPrompt.GENERAL.equals(industry) ? "MISSING" : "KNOWN";
        var day = LocalDate.now(ZONE);
        var almanac = prepare(storeId, userId, day, industry);
        return view(almanac.orElse(null), CopyCalendar.of(day), industry, status, brand.map(BrandDtos.StoreBrand::id).orElse(null));
    }

    /** 凌晨预生成：给最近一周用过文案创作的门店各登记一份。由 {@link CopyAlmanacPrepareHandler} 在任务里调用。 */
    @Transactional
    public int prepareActive(LocalDate day) {
        int count = 0;
        for (Object[] row : batches.activeStores(Instant.now().minus(ACTIVE_WITHIN))) {
            Long storeId = (Long) row[0], userId = (Long) row[1];
            String industry = CopyAlmanacPrompt.industryKey(brands.brandForStore(storeId).map(BrandDtos.StoreBrand::industry).orElse(null));
            if (prepare(storeId, userId, day, industry).isPresent()) count++;
        }
        return count;
    }

    /** 找到当天这一份；还没有就登记并提交生成任务，失败了一段时间就重新生成。模型没配置时为空。 */
    private Optional<CopyAlmanac> prepare(Long storeId, Long userId, LocalDate day, String industry) {
        var existing = almanacs.findByStoreIdAndDayAndIndustry(storeId, day, industry);
        if (!CopyModel.configured(gateway)) return existing;
        if (existing.isEmpty()) {
            if (almanacs.insertIfAbsent(TenantContext.require(), storeId, day, industry, facts(day)) == 0) {
                return almanacs.findByStoreIdAndDayAndIndustry(storeId, day, industry);
            }
            var created = almanacs.findByStoreIdAndDayAndIndustry(storeId, day, industry).orElseThrow();
            submit(created, userId, 0);
            return Optional.of(created);
        }
        var almanac = existing.get();
        if ("FAILED".equals(almanac.getStatus()) && almanac.getUpdatedAt().isBefore(Instant.now().minus(RETRY_AFTER))) {
            var locked = almanacs.lock(almanac.getId()).orElseThrow();
            if ("FAILED".equals(locked.getStatus())) {
                locked.setStatus("GENERATING");
                locked.setErrorMessage(null);
                submit(locked, userId, locked.getUpdatedAt().getEpochSecond());
            }
            return Optional.of(locked);
        }
        return existing;
    }

    private void submit(CopyAlmanac almanac, Long userId, long attempt) {
        Task task = tasks.submit(CopyAlmanacHandler.TYPE, "DEFAULT", Map.of("almanacId", almanac.getId()),
                "copy-almanac-" + almanac.getId() + "-" + attempt, userId);
        almanac.setTaskId(task.getId());
    }

    @Transactional(readOnly = true)
    public CopyAlmanac processing(Long id) { return almanacs.findById(id).orElseThrow(); }

    @Transactional
    public boolean ready(Long id, Long taskId, Map<String, Object> result) {
        var almanac = almanacs.lock(id).orElse(null);
        if (almanac == null || !"GENERATING".equals(almanac.getStatus()) || !java.util.Objects.equals(almanac.getTaskId(), taskId)) return false;
        @SuppressWarnings("unchecked") var good = (List<String>) result.get("good");
        @SuppressWarnings("unchecked") var avoid = (List<String>) result.get("avoid");
        @SuppressWarnings("unchecked") var topics = (List<Map<String, Object>>) result.get("topics");
        almanac.setGood(good);
        almanac.setAvoid(avoid);
        almanac.setQuote((String) result.get("quote"));
        almanac.setTopics(topics);
        almanac.setJournalHint((String) result.get("journalHint"));
        almanac.setStatus("READY");
        return true;
    }

    @Transactional
    public void failed(Long id, String message) {
        var almanac = almanacs.lock(id).orElse(null);
        if (almanac == null || !"GENERATING".equals(almanac.getStatus())) return;
        almanac.setStatus("FAILED");
        almanac.setErrorMessage(message);
    }

    @Transactional
    public void taskFailed(TaskTerminalFailure event) {
        if (event.payload().get("almanacId") instanceof Number id) failed(id.longValue(), "今天的黄历没有生成成功，稍后再打开会重试");
    }

    private CopyDtos.Almanac view(CopyAlmanac almanac, CopyCalendar.Facts facts, String industry, String industryStatus, Long brandId) {
        boolean ready = almanac != null && "READY".equals(almanac.getStatus());
        var fallback = CopyAlmanacPrompt.fallback(facts, industry);
        @SuppressWarnings("unchecked") List<String> good = ready ? almanac.getGood() : (List<String>) fallback.get("good");
        @SuppressWarnings("unchecked") List<String> avoid = ready ? almanac.getAvoid() : (List<String>) fallback.get("avoid");
        String status = almanac == null ? "UNAVAILABLE" : almanac.getStatus();
        return new CopyDtos.Almanac(status, !ready, facts.date().toString(), facts.date().getDayOfMonth(), facts.month(),
                facts.weekday(), facts.termLabel(), facts.todays(),
                CopyAlmanacPrompt.GENERAL.equals(industry) ? null : industry, industryStatus, brandId, good, avoid,
                ready ? almanac.getQuote() : null, ready ? almanac.getTopics() : List.of(), ready ? almanac.getJournalHint() : null);
    }

    private String facts(LocalDate day) {
        try {
            return json.writeValueAsString(CopyCalendar.of(day).asMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
