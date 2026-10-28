package com.wuyao.growth.copywriting;

import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.web.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 改写一条：不看图，按原文、资料和改写要求重写，同样过硬规则；不过关就保留原文并提示重试。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CopyRewriteHandler implements TaskHandler {
    public static final String TYPE = "COPY_REWRITE";
    private final CopyService service;
    private final CopyContexts contexts;
    private final CopyModel model;

    @Override public String type() { return TYPE; }
    @Override public Map<String, Object> handle(Task task) {
        var payload = task.getPayload();
        long id = ((Number) payload.get("batchId")).longValue();
        int index = ((Number) payload.get("index")).intValue();
        int revision = ((Number) payload.get("revision")).intValue();
        String action = (String) payload.get("action");
        try {
            var batch = service.processing(id);
            if (batch.getVersions() == null || index >= batch.getVersions().size()) return Map.of("status", "STALE");
            var version = batch.getVersions().get(index);
            var built = contexts.build(batch, 0, List.of());
            var platform = built.context().platform();
            String prompt = CopyPrompt.rewritePrompt(built.context(), version, action);
            var output = model.ask(task.getTenantId(), CopyPrompt.REWRITE_SYSTEM, prompt, List.of(), 1500,
                    "copy-rewrite-" + id + "-" + index + "-" + revision);
            var result = CopyPrompt.version(output, platform);
            if (result == null) throw new NonRetryableTaskException("COPY_OUTPUT", "改写结果为空，请重试", null);
            var problem = CopyGuard.violation(platform, result, built.allowedTokens(), built.storeName(), built.context().facts());
            if (problem.isPresent()) throw new NonRetryableTaskException("COPY_OUTPUT", "改写结果没有通过检查（" + problem.get() + "），原文保留，请重试", null);
            return Map.of("status", service.rewritten(id, index, revision, result) ? "SUCCEEDED" : "STALE");
        } catch (NonRetryableTaskException e) {
            log.warn("文案改写失败: batchId={} index={} reason={}", id, index, e.getMessage());
            service.rewriteFailed(id, index, revision, e.getMessage());
            throw e;
        } catch (Exception e) {
            String message = e instanceof BizException ? e.getMessage() : "改写没有完成，请稍后重试";
            if (task.getAttempts() >= task.getMaxAttempts()) service.rewriteFailed(id, index, revision, message);
            throw new IllegalStateException(message);
        }
    }
}
