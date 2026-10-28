package com.wuyao.growth.copywriting;

import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.creative.image.ImageCreationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * 写 3 条：看图和资料出稿，逐条过硬规则；不合格的条目带上原因再补写一次，
 * 最后至少 2 条合格才算成功并扣费，否则整批失败退回烟火。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CopyGenerateHandler implements TaskHandler {
    public static final String TYPE = "COPY_GENERATE";
    /** 送给模型的图片边长上限，缩小后再编码，控制请求体积。 */
    static final int IMAGE_LONG_EDGE = 1024;
    private final CopyService service;
    private final CopyContexts contexts;
    private final AssetService assets;
    private final ImageCreationService images;
    private final ObjectStorage storage;
    private final CopyModel model;

    @Override public String type() { return TYPE; }
    @Override public Map<String, Object> handle(Task task) {
        long id = ((Number) task.getPayload().get("batchId")).longValue();
        try {
            if (!service.begin(id, task)) return Map.of("status", "STALE");
            var batch = service.processing(id);
            var pictures = new ArrayList<String>();
            for (Long assetId : batch.getImageAssetIds()) pictures.add(dataUrl(assets.imageReference(assetId).storageKey()));
            for (Long itemId : batch.getWorkItemIds()) {
                var work = images.workImage(itemId).orElseThrow(() -> new NonRetryableTaskException("COPY_WORK", "所选作品已删除，烟火已退回", null));
                pictures.add(dataUrl(work.storageKey()));
            }
            var built = contexts.build(batch, pictures.size(), service.previousAngles(batch.getPreviousBatchId()));
            var platform = built.context().platform();
            var accepted = new ArrayList<Map<String, Object>>();
            var reasons = new ArrayList<String>();
            for (int round = 0; round < 2 && accepted.size() < 3; round++) {
                var taken = accepted.stream().map(v -> String.valueOf(v.get("angle"))).toList();
                String prompt = CopyPrompt.prompt(built.context(), 3 - accepted.size(), taken);
                if (!reasons.isEmpty()) prompt += "\n上一次有几条不能用：" + String.join("；", reasons) + "。这次务必避开。";
                var output = model.ask(task.getTenantId(), CopyPrompt.SYSTEM, prompt, pictures, 4000, "copy-generate-" + id + "-" + round);
                reasons.clear();
                for (var version : CopyPrompt.versions(output, platform)) {
                    if (accepted.size() == 3) break;
                    var problem = CopyGuard.violation(platform, version, built.allowedTokens(), built.storeName(), built.context().facts());
                    if (problem.isPresent()) reasons.add(problem.get());
                    else accepted.add(version);
                }
            }
            if (accepted.size() < CopyService.MIN_VERSIONS) {
                log.warn("文案校验不过: batchId={} reasons={}", id, reasons);
                throw new NonRetryableTaskException("COPY_OUTPUT", "这次写出的文案没有通过检查，烟火已退回，请重试", null);
            }
            return Map.of("status", service.completed(id, task, accepted) ? "SUCCEEDED" : "STALE");
        } catch (NonRetryableTaskException e) {
            log.warn("文案生成失败: batchId={} code={} reason={}", id, e.errorCode(), e.getMessage());
            service.failed(id, task, e.getMessage());
            throw e;
        } catch (Exception e) {
            String message = e instanceof BizException ? e.getMessage() : "文案模型或图片读取失败，请稍后重试";
            if (task.getAttempts() >= task.getMaxAttempts()) service.failed(id, task, message);
            throw new IllegalStateException(message);
        }
    }

    private String dataUrl(String storageKey) throws IOException {
        byte[] bytes = storage.read(storageKey, 20 * 1024 * 1024);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg(bytes, IMAGE_LONG_EDGE));
    }

    /** 缩到长边不超过配置值的 JPEG，控制请求体积；透明底铺白。 */
    static byte[] jpeg(byte[] bytes, int edge) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) throw new IllegalStateException("图片无法读取");
        int w = source.getWidth(), h = source.getHeight();
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
