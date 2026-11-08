package com.wuyao.growth.creative.inspiration;

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
public class ImageInspirationGenerateHandler implements TaskHandler {
    public static final String TYPE = "IMAGE_INSPIRATION_GENERATE";
    /** 送给模型的参考图长边上限，缩小后再编码，控制请求体积。 */
    private static final int IMAGE_LONG_EDGE = 1024;
    private final ImageInspirationService service;
    private final AssetService assets;
    private final ObjectStorage storage;
    private final AiGateway gateway;

    @Override public String type() { return TYPE; }
    @Override public Map<String, Object> handle(Task task) {
        long id = ((Number) task.getPayload().get("inspirationId")).longValue();
        try {
            if (!service.begin(id, task)) return Map.of("status", "STALE");
            var inspiration = service.processing(id);
            var images = new ArrayList<String>();
            for (Long assetId : inspiration.getImageAssetIds()) {
                byte[] bytes = storage.read(assets.imageReference(assetId).storageKey(), 20 * 1024 * 1024);
                images.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg(bytes)));
            }
            String prompt = ImageInspirationOutput.prompt(images.size(), inspiration.getStyle(), inspiration.getIdea(),
                    service.previousPlans(inspiration.getPreviousId()));
            var response = gateway.invokeReal(new ProviderRequest(ModelAlias.IMAGE_INSPIRATION_PLANNER, task.getTenantId(), prompt,
                    Map.of("system", ImageInspirationOutput.SYSTEM, "images", images), "image-inspiration-" + id));
            if (!response.succeeded() || response.output() == null) throw new IllegalStateException("灵感方案模型未返回有效结果");
            var result = ImageInspirationOutput.validate(response.output(), images.size());
            return Map.of("status", service.completed(id, task, result) ? "SUCCEEDED" : "STALE");
        } catch (NonRetryableTaskException e) {
            log.warn("灵感方案结果校验失败: inspirationId={} code={} reason={}", id, e.errorCode(), e.getMessage());
            service.failed(id, task, e.getMessage());
            throw e;
        } catch (Exception e) {
            String message = e instanceof BizException ? e.getMessage() : "灵感方案模型或图片读取失败，请稍后重试";
            if (task.getAttempts() >= task.getMaxAttempts()) service.failed(id, task, message);
            throw new IllegalStateException(message);
        }
    }

    /** 缩到长边不超过上限的 JPEG；透明底铺白。 */
    private static byte[] jpeg(byte[] bytes) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) throw new IllegalStateException("参考图无法读取");
        int w = source.getWidth(), h = source.getHeight();
        double scale = Math.min(1, (double) IMAGE_LONG_EDGE / Math.max(w, h));
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
