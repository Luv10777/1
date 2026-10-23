package com.wuyao.growth.video.plan;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 视频工作台「帮我出方案」：每次出 3 个版本，预占 1 烟火，成功扣除，失败或校验不过退回。 */
@Service
@RequiredArgsConstructor
public class VideoPlanService {
    private final VideoPlanRepository plans;
    private final AssetService assets;
    private final TaskService tasks;
    private final TaskRepository taskRepository;
    private final AiGateway gateway;
    private final BillingService billing;

    public VideoPlanDtos.Config config() {
        return new VideoPlanDtos.Config(gateway.configured(ModelAlias.VIDEO_PLANNER), BillingService.VIDEO_PLAN_CREDITS, 6);
    }

    @Transactional
    public VideoPlanDtos.View create(VideoPlanDtos.Create request, Long userId) {
        billing.lockForGeneration();
        if (!gateway.configured(ModelAlias.VIDEO_PLANNER)) throw BizException.of(ErrorCode.VIDEO_NOT_CONFIGURED, "出方案模型尚未配置");
        String style = trim(request.style()), idea = trim(request.idea());
        var images = List.copyOf(request.imageAssetIds());
        var existing = plans.findByRequestKey(request.requestKey());
        if (existing.isPresent()) {
            var prior = existing.get();
            if (!prior.getImageAssetIds().equals(images) || prior.isReferenceVideo() != request.referenceVideo()
                    || !Objects.equals(prior.getStyle(), style)
                    || !Objects.equals(prior.getIdea(), idea) || !Objects.equals(prior.getPreviousPlanId(), request.previousPlanId())) {
                throw BizException.of(ErrorCode.CONFLICT, "请求编号已用于不同的方案，请重新提交");
            }
            return view(prior);
        }
        if (images.isEmpty() && idea == null) throw BizException.of(ErrorCode.BAD_REQUEST, "请上传参考图或写一句想要的效果");
        if (images.stream().distinct().count() != images.size()) throw BizException.of(ErrorCode.BAD_REQUEST, "参考图重复");
        billing.requireMembership("VIDEO_PLAN");
        images.forEach(assets::imageReference);
        if (request.previousPlanId() != null) {
            var previous = plans.findById(request.previousPlanId())
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "上一批方案不存在"));
            if (!"SUCCEEDED".equals(previous.getStatus())) throw BizException.of(ErrorCode.BAD_REQUEST, "上一批方案还没有完成");
        }
        var plan = new VideoPlan();
        plan.setTenantId(TenantContext.require());
        plan.setCreatedBy(userId);
        plan.setRequestKey(request.requestKey());
        plan.setImageAssetIds(images);
        plan.setReferenceVideo(request.referenceVideo());
        plan.setStyle(style);
        plan.setIdea(idea);
        plan.setPreviousPlanId(request.previousPlanId());
        plan.setBillingReservationId(billing.reserve("video-plan:" + request.requestKey(), "VIDEO_PLAN",
                BillingService.VIDEO_PLAN_CREDITS, (request.previousPlanId() == null ? "视频方案" : "视频方案换一批") + " · 预占 1 烟火"));
        plans.saveAndFlush(plan);
        Task task = tasks.submit(VideoPlanGenerateHandler.TYPE, "DEFAULT", Map.of("planId", plan.getId()),
                "video-plan-generate-" + plan.getId(), userId);
        plan.setTaskId(task.getId());
        return view(plan);
    }

    @Transactional(readOnly = true)
    public VideoPlanDtos.View get(Long id) { return view(find(id)); }

    @Transactional(readOnly = true)
    public VideoPlan processing(Long id) { return find(id); }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> previousPlans(Long previousPlanId) {
        if (previousPlanId == null) return List.of();
        return plans.findById(previousPlanId).map(VideoPlan::getPlans).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<Long> billingRecoveryTaskIds(List<String> reservations) {
        return reservations.isEmpty() ? List.of() : plans.billingRecoveryTaskIds(reservations);
    }

    @Transactional
    public boolean begin(Long id, Task task) {
        var plan = owned(id, task);
        if (plan == null) return false;
        plan.setStatus("GENERATING");
        plan.setErrorMessage(null);
        return true;
    }

    @Transactional
    public boolean completed(Long id, Task task, List<Map<String, Object>> result) {
        var plan = owned(id, task);
        if (plan == null) return false;
        if (plan.getBillingReservationId() != null) billing.capture(plan.getBillingReservationId(), BillingService.VIDEO_PLAN_CREDITS,
                "video-plan:" + id + ":capture", "视频方案 · 已出 3 个版本");
        plan.setPlans(result);
        plan.setStatus("SUCCEEDED");
        return true;
    }

    @Transactional
    public void failed(Long id, Task task, String message) {
        var plan = owned(id, task);
        if (plan != null) markFailed(plan, message);
    }

    @Transactional
    public void taskFailed(TaskTerminalFailure event) {
        if (!(event.payload().get("planId") instanceof Number id)) return;
        billing.lockForGeneration();
        var plan = plans.lock(id.longValue()).orElse(null);
        if (plan == null || plan.terminal() || !Objects.equals(plan.getTaskId(), event.taskId())) return;
        markFailed(plan, "出方案没有完成，预占烟火已退回，请重试");
    }

    private void markFailed(VideoPlan plan, String message) {
        if (plan.getBillingReservationId() != null) billing.release(plan.getBillingReservationId(),
                "video-plan:" + plan.getId() + ":release", "视频方案 · 未完成，退回烟火");
        plan.setStatus("FAILED");
        plan.setErrorMessage(message);
    }

    private VideoPlan owned(Long id, Task task) {
        // 状态变化和结算与其他生成功能一样先锁账户，再锁记录。
        billing.lockForGeneration();
        if (taskRepository.findOwnedRunning(task.getId(), task.getAttempts()).isEmpty()) return null;
        var plan = plans.lock(id).orElseThrow();
        return plan.terminal() || !Objects.equals(plan.getTaskId(), task.getId()) ? null : plan;
    }

    private VideoPlan find(Long id) {
        return plans.findById(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "方案不存在"));
    }

    private static VideoPlanDtos.View view(VideoPlan p) {
        return new VideoPlanDtos.View(p.getId(), p.getStatus(), p.getPlans(), p.getErrorMessage(), p.getPreviousPlanId(), p.getCreatedAt());
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
