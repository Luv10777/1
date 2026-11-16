package com.wuyao.growth.video.plan;

import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.web.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.LinkedHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class VideoPlanGenerateHandler implements TaskHandler {
    public static final String TYPE = "VIDEO_PLAN_GENERATE";
    private final VideoPlanService service;
    private final AssetService assets;
    private final ObjectStorage storage;
    private final AiGateway gateway;
    private final VideoPlanProperties config;

    @Override public String type() { return TYPE; }
    @Override public Map<String, Object> handle(Task task) {
        long id = ((Number) task.getPayload().get("planId")).longValue();
        try {
            if (!service.begin(id, task)) return Map.of("status", "STALE");
            var plan = service.processing(id);
            var images = new ArrayList<String>();
            for (Long assetId : plan.getImageAssetIds()) {
                byte[] bytes = storage.read(assets.imageReference(assetId).storageKey(), 20 * 1024 * 1024);
                images.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg(bytes)));
            }
            String prompt = VideoPlanOutput.prompt(images.size(), plan.isReferenceVideo(), plan.getStyle(), plan.getIdea(),
                    service.previousPlans(plan.getPreviousPlanId()), plan.getSceneKey());
            @SuppressWarnings("unchecked")
            var constraints = (Map<String, Object>) task.getPayload().getOrDefault("constraints", Map.of());
            Integer seconds = constraints.get("durationSeconds") instanceof Number n ? n.intValue() : VideoPlanConstraints.explicitDuration(plan.getIdea());
            int maxSeconds = constraints.get("maxDurationSeconds") instanceof Number n ? n.intValue() : 30;
            String ratio = constraints.get("ratio") instanceof String s ? s : null;
            if (constraints.get("sourceBrief") instanceof String brief)
                prompt += "\n工作台已有的成片需求：" + brief + "\n保留其中没有被本次一句话需求修改的明确要求；本次一句话需求是最新要求。";
            prompt += "\n输出约束：" + (seconds == null ? "时长按需求决定，最长 " + maxSeconds + " 秒" : "三个方案都必须为 " + seconds + " 秒，不得缩短或改长")
                    + (ratio == null ? "" : "；画幅固定 " + ratio) + "。durationSeconds、提示词中的总时长、分镜最后一镜的结束时间必须一致。";
            String answer = constraints.get("clarificationAnswer") instanceof String s ? s : "";
            if (!answer.isBlank()) prompt += "\n用户已回答补充问题：" + answer + "\n不再追问，根据已知事实完成剧本。";
            var stages = savedStages(plan);
            var concept = stages.get("concept") instanceof Map<?, ?> m ? (Map<String, Object>)m : null;
            if (concept == null) {
                concept = VideoPlanScript.concept(invoke(task, id, "concept", VideoPlanScript.CONCEPT_SYSTEM, prompt, images, 4000), images.size(), !answer.isBlank());
                String question = (String)concept.get("question");
                if (!question.isEmpty()) {
                    var details = new LinkedHashMap<String, Object>();
                    details.put("question", question); details.put("brief", concept.get("brief"));
                    details.put("originalIdea", plan.getIdea() == null ? "" : plan.getIdea());
                    details.put("sourceBrief", constraints.getOrDefault("sourceBrief", ""));
                    details.put("style", plan.getStyle() == null ? "" : plan.getStyle());
                    return Map.of("status", service.needsDetails(id, task, details) ? "NEEDS_DETAILS" : "STALE");
                }
                stages.put("concept", concept);
                if (!service.checkpoint(id, task, stages)) return Map.of("status", "STALE");
            }
            var draft = stages.get("draft") instanceof Map<?, ?> m ? (Map<String, Object>)m : null;
            if (draft == null) {
                draft = invoke(task, id, "draft", VideoPlanScript.DRAFT_SYSTEM, prompt + "\n已确定的事实与三个创意：\n" + VideoPlanScript.data(concept), images, 9000);
                if (!(draft.get("plans") instanceof java.util.List<?> list) || list.size() != 3)
                    throw new NonRetryableTaskException("VIDEO_PLAN_SCRIPT", "草稿未包含三个完整剧本，烟火已退回，请重试", null);
                stages.put("draft", Map.of("plans", draft.get("plans")));
                if (!service.checkpoint(id, task, stages)) return Map.of("status", "STALE");
            }
            var reviewed = invoke(task, id, "review", VideoPlanScript.REVIEW_SYSTEM,
                    prompt + "\n原始事实与创意：\n" + VideoPlanScript.data(concept) + "\n待审草稿：\n" + VideoPlanScript.data(stages.get("draft")), images, 12000);
            var result = VideoPlanScript.compile(reviewed, concept, images.size(), seconds, maxSeconds, ratio);
            if (constraints.get("sourceBrief") instanceof String brief) result.forEach(p -> p.put("sourceBrief", brief));
            if (!answer.isBlank()) result.forEach(p -> p.put("clarificationAnswer", answer));
            return Map.of("status", service.completed(id, task, result) ? "SUCCEEDED" : "STALE");
        } catch (NonRetryableTaskException e) {
            log.warn("视频方案结果校验失败: planId={} code={} reason={}", id, e.errorCode(), e.getMessage());
            service.failed(id, task, e.getMessage());
            throw e;
        } catch (Exception e) {
            String message = e instanceof BizException ? e.getMessage() : "出方案模型或图片读取失败，请稍后重试";
            if (task.getAttempts() >= task.getMaxAttempts()) service.failed(id, task, message);
            throw new IllegalStateException(message);
        }
    }

    private Map<String, Object> invoke(Task task, long id, String stage, String system, String prompt, java.util.List<String> images, int tokens) {
        var response = gateway.invokeReal(new ProviderRequest(ModelAlias.VIDEO_PLANNER, task.getTenantId(), prompt,
                Map.of("system", system, "images", images, "maxTokens", tokens), "video-plan-" + id + "-v2-" + stage));
        if (!response.succeeded() || response.output() == null) throw new IllegalStateException("出方案模型未返回有效结果");
        return response.output();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> savedStages(VideoPlan plan) {
        if (plan.getPlans() != null && !plan.getPlans().isEmpty()) {
            var metadata = plan.getPlans().getFirst();
            if (metadata.get("pipelineVersion") instanceof Number n && n.intValue() == VideoPlanScript.VERSION && metadata.get("stages") instanceof Map<?, ?> stages)
                return new LinkedHashMap<>((Map<String, Object>)stages);
        }
        return new LinkedHashMap<>();
    }

    /** 缩到长边不超过配置值的 JPEG，控制请求体积；透明底铺白。 */
    private byte[] jpeg(byte[] bytes) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) throw new IllegalStateException("参考图无法读取");
        int edge = config.getImageLongEdge(), w = source.getWidth(), h = source.getHeight();
        double scale = Math.min(1, (double) edge / Math.max(w, h));
        int width = Math.max(1, (int) Math.round(w * scale)), height = Math.max(1, (int) Math.round(h * scale));
        var target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(target, "jpg", out);
        return out.toByteArray();
    }
}
