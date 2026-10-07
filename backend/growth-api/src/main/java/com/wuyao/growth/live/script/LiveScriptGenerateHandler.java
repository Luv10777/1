package com.wuyao.growth.live.script;

import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.gateway.ProviderRequest;
import com.wuyao.growth.common.gateway.ProviderResult;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.live.LiveDtos;
import com.wuyao.growth.live.LiveSession;
import com.wuyao.growth.live.LiveSessionRepository;
import com.wuyao.growth.live.speech.LiveSpeechItem;
import com.wuyao.growth.live.speech.LiveSpeechItemRepository;
import com.wuyao.growth.live.speech.LiveSpeechQueue;
import com.wuyao.growth.live.speech.LiveSpeechSynthesizeHandler;
import com.wuyao.growth.product.ProductDtos;
import com.wuyao.growth.product.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes one segment of automatic narration from the product's saved facts, then queues it for
 * synthesis. The text model is reached only through the gateway's TEXT_WRITER capability.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveScriptGenerateHandler implements TaskHandler {
    public static final String TYPE = "LIVE_SCRIPT_GENERATE";
    /** One rewrite is allowed when the first draft fails the checks. */
    static final int MAX_DRAFTS = 2;
    private static final Duration MAX_AGE = Duration.ofMinutes(10);
    private static final List<String> SPOKEN = List.of(
            LiveSpeechItem.PENDING, LiveSpeechItem.READY, LiveSpeechItem.PLAYED);

    private final LiveSpeechItemRepository items;
    private final LiveSessionRepository sessions;
    private final LiveScriptStateRepository states;
    private final LiveSpeechQueue queue;
    private final LiveScriptService scripts;
    private final ProductService products;
    private final AiGateway gateway;
    private final TaskService tasks;
    private final TransactionTemplate transactions;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> handle(Task task) {
        Long itemId = ((Number) task.getPayload().get("itemId")).longValue();
        Job job = transactions.execute(tx -> prepare(itemId));
        if (job == null) return result(itemId, "SKIPPED", null);

        String text;
        try {
            text = write(job, task.getId());
        } catch (ModelUnavailable e) {
            return fail(job, itemId, e.getMessage(), true);
        } catch (BizException e) {
            return fail(job, itemId, e.getMessage(), false);
        } catch (RuntimeException e) {
            log.warn("直播话术生成异常: itemId={}", itemId, e);
            return fail(job, itemId, "话术生成未完成，请稍后重试", false);
        }

        boolean queued = Boolean.TRUE.equals(transactions.execute(tx -> {
            // Synthesis is submitted with the text, so a segment never waits for audio nobody asked for.
            if (!queue.attachText(itemId, text, LiveScriptService.sha256(text))) return false;
            tasks.submit(LiveSpeechSynthesizeHandler.TYPE, LiveSpeechSynthesizeHandler.QUEUE,
                    Map.of("itemId", itemId), "live-speech:" + itemId, job.userId());
            return true;
        }));
        return result(itemId, queued ? LiveSpeechItem.PENDING : LiveSpeechItem.DISCARDED, null);
    }

    private Job prepare(Long itemId) {
        LiveSpeechItem item = items.findForUpdate(itemId).orElse(null);
        if (item == null || !LiveSpeechItem.GENERATING.equals(item.getStatus())) return null;
        LiveSession session = sessions.findById(item.getSessionId()).orElse(null);
        boolean wanted = session != null && "LIVE".equals(session.getStatus())
                && states.findById(item.getSessionId()).map(LiveScriptState::isEnabled).orElse(false);
        if (!wanted) {
            queue.discard(itemId);
            return null;
        }
        if (item.getCreatedAt().isBefore(Instant.now().minus(MAX_AGE))) {
            queue.markFailed(itemId, "等待生成超时，已跳过");
            return null;
        }
        ScriptPlan.Beat beat = ScriptPlan.beat(item.getBeat());
        if (beat == null || item.getProductId() == null) {
            queue.markFailed(itemId, "讲解环节无效");
            return null;
        }
        queue.markStarted(item);
        LiveDtos.Config config = session.getConfig();
        // Variety is opt-out: only an explicit "off" lets a segment ignore what was said before.
        List<String> recent = config != null && Boolean.FALSE.equals(config.antiRepeat()) ? List.of()
                : items.findTop3BySessionIdAndKindAndProductIdAndStatusInOrderByIdDesc(
                        item.getSessionId(), LiveSpeechItem.SCRIPT, item.getProductId(), SPOKEN)
                .stream().map(LiveSpeechItem::getText).toList();
        // Whatever was said last, about this product or another: the new segment has to follow it.
        String previous = items.findFirstBySessionIdAndKindAndStatusInAndIdLessThanOrderByIdDesc(
                        item.getSessionId(), LiveSpeechItem.SCRIPT, SPOKEN, item.getId())
                .map(LiveSpeechItem::getText).orElse(null);
        return new Job(item.getTenantId(), item.getSessionId(), item.getProductId(), beat,
                ScriptPlan.urgencyLevel(config), recent, previous, item.getCreatedBy(),
                config == null ? null : config.persona());
    }

    private String write(Job job, Long taskId) {
        ProductDtos.View product = products.get(job.productId(), job.userId());
        String facts = ScriptPrompt.facts(product);
        String rejection = null;
        for (int draft = 1; draft <= MAX_DRAFTS; draft++) {
            ProviderResult result = gateway.invoke(new ProviderRequest(ModelAlias.TEXT_WRITER, job.tenantId(),
                    ScriptPrompt.user(product, job.beat(), job.urgency(), job.recent(), job.previous(), rejection),
                    Map.of("system", ScriptPrompt.system(job.persona())),
                    // Stable per task and draft, so a retried task does not look like a new request.
                    "live-script-" + taskId + "-" + draft));
            // The placeholder adapter answers every capability but writes nothing: it has no "text".
            if (!result.succeeded() || result.output() == null
                    || !(result.output().get("text") instanceof String raw)) {
                throw new ModelUnavailable("尚未配置文本大模型，无法自动生成话术。请在服务端配置 TEXT_WRITER 模型后重试");
            }
            String text = ScriptGuard.clean(raw);
            if (job.previous() != null && !ScriptPrompt.opens(job.beat())) text = ScriptGuard.withoutLeadingAddress(text);
            Optional<String> violation = ScriptGuard.violation(text, facts);
            if (violation.isEmpty()) return text;
            rejection = violation.get();
            // What was refused is kept nowhere else; without it a wrong refusal cannot be told from a right one.
            log.info("直播话术未通过校验: reason={} draft={} text={}", rejection, draft, text);
        }
        throw BizException.of(com.wuyao.growth.common.web.ErrorCode.CONFLICT, "生成的话术未通过校验（" + rejection + "），已跳过这一段");
    }

    private Map<String, Object> fail(Job job, Long itemId, String message, boolean fatal) {
        queue.markFailed(itemId, message);
        scripts.generationFailed(job.sessionId(), message, fatal);
        return result(itemId, LiveSpeechItem.FAILED, message);
    }

    private static Map<String, Object> result(Long itemId, String status, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemId", itemId);
        result.put("status", status);
        if (error != null) result.put("error", error);
        return result;
    }

    private record Job(Long tenantId, Long sessionId, Long productId, ScriptPlan.Beat beat, int urgency,
                       List<String> recent, String previous, Long userId, LiveDtos.Persona persona) { }

    private static final class ModelUnavailable extends RuntimeException {
        private ModelUnavailable(String message) { super(message); }
    }
}
