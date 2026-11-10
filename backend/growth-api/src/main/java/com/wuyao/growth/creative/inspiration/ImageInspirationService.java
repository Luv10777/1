package com.wuyao.growth.creative.inspiration;

import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskRepository;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.task.TaskTerminalFailure;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 灵感绘图「灵感方案」：每次出 3 个画面描述，预占 3 烟火，成功扣除，失败或校验不过退回。 */
@Service
@RequiredArgsConstructor
public class ImageInspirationService {
    public static final String CAPABILITY = "IMAGE_INSPIRATION";
    private final ImageInspirationRepository inspirations;
    private final AssetService assets;
    private final TaskService tasks;
    private final TaskRepository taskRepository;
    private final AiGateway gateway;
    private final BillingService billing;

    public ImageInspirationDtos.Config config() {
        return new ImageInspirationDtos.Config(gateway.configured(ModelAlias.IMAGE_INSPIRATION_PLANNER),
                BillingService.IMAGE_INSPIRATION_CREDITS, ImageInspirationDtos.MAX_IMAGES);
    }

    @Transactional
    public ImageInspirationDtos.View create(ImageInspirationDtos.Create request, Long userId) {
        billing.lockForGeneration();
        if (!gateway.configured(ModelAlias.IMAGE_INSPIRATION_PLANNER)) throw BizException.of(ErrorCode.IMAGE_NOT_CONFIGURED, "灵感方案模型尚未配置");
        String style = trim(request.style()), idea = trim(request.idea());
        var images = List.copyOf(request.imageAssetIds());
        var existing = inspirations.findByRequestKey(request.requestKey());
        if (existing.isPresent()) {
            var prior = existing.get();
            if (!prior.getImageAssetIds().equals(images) || !Objects.equals(prior.getStyle(), style)
                    || !Objects.equals(prior.getIdea(), idea) || !Objects.equals(prior.getPreviousId(), request.previousId())) {
                throw BizException.of(ErrorCode.CONFLICT, "请求编号已用于不同的方案，请重新提交");
            }
            return view(prior);
        }
        if (images.isEmpty() && idea == null) throw BizException.of(ErrorCode.BAD_REQUEST, "请添加参考图或写一句想要的画面");
        if (images.stream().distinct().count() != images.size()) throw BizException.of(ErrorCode.BAD_REQUEST, "参考图重复");
        billing.requireMembership(CAPABILITY);
        images.forEach(assets::imageReference);
        if (request.previousId() != null) {
            var previous = inspirations.findById(request.previousId())
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "上一批方案不存在"));
            if (!"SUCCEEDED".equals(previous.getStatus())) throw BizException.of(ErrorCode.BAD_REQUEST, "上一批方案还没有完成");
        }
        var inspiration = new ImageInspiration();
        inspiration.setTenantId(TenantContext.require());
        inspiration.setCreatedBy(userId);
        inspiration.setRequestKey(request.requestKey());
        inspiration.setImageAssetIds(images);
        inspiration.setStyle(style);
        inspiration.setIdea(idea);
        inspiration.setPreviousId(request.previousId());
        inspiration.setBillingReservationId(billing.reserve("image-inspiration:" + request.requestKey(), CAPABILITY,
                BillingService.IMAGE_INSPIRATION_CREDITS, (request.previousId() == null ? "灵感方案" : "灵感方案换一批") + " · 预占 " + BillingService.IMAGE_INSPIRATION_CREDITS + " 烟火"));
        inspirations.saveAndFlush(inspiration);
        Task task = tasks.submit(ImageInspirationGenerateHandler.TYPE, "DEFAULT", Map.of("inspirationId", inspiration.getId()),
                "image-inspiration-generate-" + inspiration.getId(), userId);
        inspiration.setTaskId(task.getId());
        return view(inspiration);
    }

    @Transactional(readOnly = true)
    public ImageInspirationDtos.View get(Long id) { return view(find(id)); }

    /** 灵感记录：本店出成功的方案，新的在前。 */
    @Transactional(readOnly = true)
    public PageResult<ImageInspirationDtos.History> history(int page, int size) {
        var result = inspirations.findByStatusOrderByIdDesc("SUCCEEDED", PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size))));
        return PageResult.of(result, result.getContent().stream().map(p -> new ImageInspirationDtos.History(
                p.getId(), p.getStyle(), p.getIdea(),
                p.getImageAssetIds().stream().map(id -> assets.imagePreview(id)
                        .map(image -> new ImageInspirationDtos.Image(id, image.name(), image.url()))
                        .orElse(new ImageInspirationDtos.Image(id, "参考图已不可用", null))).toList(),
                p.getPlans(), p.getCreatedAt())).toList());
    }

    @Transactional(readOnly = true)
    public ImageInspiration processing(Long id) { return find(id); }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> previousPlans(Long previousId) {
        if (previousId == null) return List.of();
        return inspirations.findById(previousId).map(ImageInspiration::getPlans).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<Long> billingRecoveryTaskIds(List<String> reservations) {
        return reservations.isEmpty() ? List.of() : inspirations.billingRecoveryTaskIds(reservations);
    }

    @Transactional
    public boolean begin(Long id, Task task) {
        var inspiration = owned(id, task);
        if (inspiration == null) return false;
        inspiration.setStatus("GENERATING");
        inspiration.setErrorMessage(null);
        return true;
    }

    @Transactional
    public boolean completed(Long id, Task task, List<Map<String, Object>> result) {
        var inspiration = owned(id, task);
        if (inspiration == null) return false;
        if (inspiration.getBillingReservationId() != null) billing.capture(inspiration.getBillingReservationId(),
                BillingService.IMAGE_INSPIRATION_CREDITS, "image-inspiration:" + id + ":capture", "灵感方案 · 已出 3 个画面");
        inspiration.setPlans(result);
        inspiration.setStatus("SUCCEEDED");
        return true;
    }

    @Transactional
    public void failed(Long id, Task task, String message) {
        var inspiration = owned(id, task);
        if (inspiration != null) markFailed(inspiration, message);
    }

    @Transactional
    public void taskFailed(TaskTerminalFailure event) {
        if (!(event.payload().get("inspirationId") instanceof Number id)) return;
        billing.lockForGeneration();
        var inspiration = inspirations.lock(id.longValue()).orElse(null);
        if (inspiration == null || inspiration.terminal() || !Objects.equals(inspiration.getTaskId(), event.taskId())) return;
        markFailed(inspiration, "灵感方案没有完成，预占烟火已退回，请重试");
    }

    private void markFailed(ImageInspiration inspiration, String message) {
        if (inspiration.getBillingReservationId() != null) billing.release(inspiration.getBillingReservationId(),
                "image-inspiration:" + inspiration.getId() + ":release", "灵感方案 · 未完成，退回烟火");
        inspiration.setStatus("FAILED");
        inspiration.setErrorMessage(message);
    }

    private ImageInspiration owned(Long id, Task task) {
        // 状态变化和结算与其他生成功能一样先锁账户，再锁记录。
        billing.lockForGeneration();
        if (taskRepository.findOwnedRunning(task.getId(), task.getAttempts()).isEmpty()) return null;
        var inspiration = inspirations.lock(id).orElseThrow();
        return inspiration.terminal() || !Objects.equals(inspiration.getTaskId(), task.getId()) ? null : inspiration;
    }

    private ImageInspiration find(Long id) {
        return inspirations.findById(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "方案不存在"));
    }

    private static ImageInspirationDtos.View view(ImageInspiration p) {
        return new ImageInspirationDtos.View(p.getId(), p.getStatus(), p.getPlans(), p.getErrorMessage(), p.getPreviousId(), p.getCreatedAt());
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
