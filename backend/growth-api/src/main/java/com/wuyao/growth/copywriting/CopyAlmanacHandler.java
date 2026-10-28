package com.wuyao.growth.copywriting;

import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.product.ProductService;
import com.wuyao.growth.store.StoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** 生成一家门店当天的「今日宜发」：日期事实由程序算，行业、门店商品和最近的门店志交给模型解读。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CopyAlmanacHandler implements TaskHandler {
    public static final String TYPE = "COPY_ALMANAC";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneId.of("Asia/Shanghai"));
    private final CopyAlmanacService service;
    private final CopyJournalRepository journal;
    private final StoreService stores;
    private final ProductService products;
    private final CopyModel model;

    @Override public String type() { return TYPE; }
    @Override public Map<String, Object> handle(Task task) {
        long id = ((Number) task.getPayload().get("almanacId")).longValue();
        try {
            var almanac = service.processing(id);
            if (!"GENERATING".equals(almanac.getStatus())) return Map.of("status", "STALE");
            var facts = CopyCalendar.of(almanac.getDay());
            String storeName = stores.profile(almanac.getStoreId()).map(s -> s.name()).orElse("这家店");
            String prompt = CopyAlmanacPrompt.prompt(facts, almanac.getIndustry(), storeName,
                    productNames(almanac.getStoreId(), task.getCreatedBy()), recent(almanac.getStoreId()));
            var output = model.ask(task.getTenantId(), CopyAlmanacPrompt.SYSTEM, prompt, List.of(), 1500, "copy-almanac-" + id);
            var result = CopyAlmanacPrompt.normalize(output, facts, almanac.getIndustry());
            return Map.of("status", service.ready(id, task.getId(), result) ? "SUCCEEDED" : "STALE");
        } catch (NonRetryableTaskException e) {
            log.warn("今日宜发生成失败: almanacId={} reason={}", id, e.getMessage());
            service.failed(id, e.getMessage());
            throw e;
        } catch (Exception e) {
            String message = e instanceof BizException ? e.getMessage() : "今天的黄历没有生成成功，稍后再打开会重试";
            if (task.getAttempts() >= task.getMaxAttempts()) service.failed(id, message);
            throw new IllegalStateException(message);
        }
    }

    /** 只给商品名，不给价格：黄历和选题不需要数字。 */
    private List<String> productNames(Long storeId, Long userId) {
        if (userId == null) return List.of();
        try {
            return products.list(storeId, null, null, null, 0, 6, userId).items().stream().map(p -> p.name()).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<String> recent(Long storeId) {
        return journal.findByStoreIdOrderByIdDesc(storeId, PageRequest.of(0, 10)).stream()
                .map(e -> DAY.format(e.getFinalizedAt()) + " " + CopyPlatform.valueOf(e.getPlatform()).label() + "："
                        + CopyPrompt.text((e.getTitle() == null ? e.getBody() : e.getTitle()).replace('\n', ' '), 30))
                .toList();
    }
}
