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
                    service.previousPlans(plan.getPreviousPlanId()));
            var response = gateway.invokeReal(new ProviderRequest(ModelAlias.VIDEO_PLANNER, task.getTenantId(), prompt,
                    Map.of("system", VideoPlanOutput.SYSTEM, "images", images), "video-plan-" + id));
            if (!response.succeeded() || response.output() == null) throw new IllegalStateException("出方案模型未返回有效结果");
            var result = VideoPlanOutput.validate(response.output(), images.size());
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
