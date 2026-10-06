package com.wuyao.growth.video;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.Asset;
import com.wuyao.growth.asset.AssetRepository;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.asset.VideoMediaProbe;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.metrics.VideoMetrics;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.task.TaskStatus;
import com.wuyao.growth.common.task.NonRetryableTaskException;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoWorkflowService {
    private static final int MAX_REDIRECTS = 5;
    private static final DateTimeFormatter SIGNING_DATE = DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT).withZone(ZoneOffset.UTC);

    private final VideoWorkflowRepository workflows;
    private final VideoProviderJobRepository providerJobs;
    private final AssetRepository assets;
    private final AssetService assetService;
    private final ObjectStorage storage;
    private final TaskService tasks;
    private final ObjectMapper json;
    private final TenantRateLimiter rateLimiter;
    private final HttpClient httpClient;
    private final VideoMediaProbe videoProbe;
    private final VideoMetrics metrics;

    @Value("${growth.video.poll-seconds:15}") private int pollSeconds;
    @Value("${growth.video.max-polls:360}") private int maxPolls;
    @Value("${growth.video.max-duration-seconds:7200}") private long maxDurationSeconds;
    @Value("${growth.video.max-provider-bytes:1073741824}") private long maxProviderBytes;
    @Value("${growth.video.tenant-max-concurrent:2}") private int tenantMaxConcurrent;
    @Value("${growth.video.global-max-concurrent:20}") private int globalMaxConcurrent;
    @Value("${growth.video.concurrency-permit-ttl-seconds:21600}") private long concurrencyPermitTtlSeconds;
    @Value("${growth.video.reference-url-safety-seconds:300}") private long referenceUrlSafetySeconds = 300;
    @Value("${growth.video.provider.timeout-seconds:60}") private long providerTimeoutSeconds = 60;

    @Transactional
    public VideoDtos.View create(VideoDtos.Create request, Long userId) {
        try {
            VideoCapabilities.validate(request);
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.VIDEO_PARAMETER_INVALID, e.getMessage());
        }
        if (request.prompt().isBlank() && request.referenceImageAssetIds().isEmpty() && request.referenceVideoAssetId() == null) {
            throw BizException.of(ErrorCode.VIDEO_PARAMETER_INVALID, "请至少描述需求或添加一个参考素材");
        }
        request.referenceImageAssetIds().forEach(id -> assetService.mediaReference(id, "IMAGE"));
        if (request.referenceVideoAssetId() != null) assetService.mediaReference(request.referenceVideoAssetId(), "VIDEO");

        Long tenantId = TenantContext.require();
        workflows.lockRequest(tenantId + ":" + request.requestKey());
        String hash = hash(request);
        var existing = workflows.findByRequestKey(request.requestKey());
        if (existing.isPresent()) {
            if (!hash.equals(existing.get().getRequestHash())) throw BizException.of(ErrorCode.CONFLICT, "同一提交标识不能用于不同的视频配置");
            return view(existing.get());
        }

        acquirePermit(tenantId);
        VideoWorkflow workflow = new VideoWorkflow();
        workflow.setTenantId(tenantId);
        workflow.setCreatedBy(userId);
        workflow.setRequestKey(request.requestKey());
        workflow.setRequestHash(hash);
        workflow.setRequest(request);
        workflow.setModel(request.model());
        workflow.setRatio(request.ratio());
        workflow.setDurationSeconds(request.durationSeconds());
        workflow.setResolution(request.resolution());
        workflow.setVideoConcurrencyPermitHeld(true);
        workflow.setStageStartedAt(workflow.getCreatedAt());
        workflows.saveAndFlush(workflow);
        Task task = tasks.submit(VideoSubmitHandler.TYPE, "VIDEO_PROVIDER", Map.of("workflowId", workflow.getId()),
                taskKey(workflow, "submit"), userId);
        workflow.setTaskId(task.getId());
        return view(workflow);
    }

    @Transactional(readOnly = true)
    public VideoDtos.View get(Long id) { return view(find(id)); }

    @Transactional
    public VideoDtos.View retry(Long id, Long expectedTaskId, Long userId) {
        if (expectedTaskId == null) throw BizException.of(ErrorCode.BAD_REQUEST, "请提供当前视频任务标识");
        VideoWorkflow workflow = lock(id);
        // The row lock serializes two clients; the loser observes the newly assigned task.
        if (!Objects.equals(workflow.getTaskId(), expectedTaskId)) return view(workflow);
        RetryPlan plan = retryPlan(workflow, effectiveStatus(workflow));
        if (plan.stage() == null) throw BizException.of(ErrorCode.BAD_REQUEST, plan.hint());
        if ("SUBMIT".equals(plan.stage())) {
            VideoDtos.Create request = workflow.getRequest();
            try { VideoCapabilities.validate(request); }
            catch (IllegalArgumentException e) { throw BizException.of(ErrorCode.VIDEO_PARAMETER_INVALID, e.getMessage()); }
            request.referenceImageAssetIds().forEach(assetId -> assetService.mediaReference(assetId, "IMAGE"));
            if (request.referenceVideoAssetId() != null) assetService.mediaReference(request.referenceVideoAssetId(), "VIDEO");
        }
        if (!workflow.isVideoConcurrencyPermitHeld()) {
            acquirePermit(TenantContext.require());
            workflow.setVideoConcurrencyPermitHeld(true);
        }
        if ("FAILED".equals(workflow.getStatus())) cleanupUnpublishedOutput(workflow);
        if (plan.newSubmission()) {
            workflow.setSubmissionGeneration(workflow.getSubmissionGeneration() + 1);
            workflow.setProviderJobId(null);
            workflow.setProviderStatus(null);
            workflow.setProviderResultUrl(null);
        }
        if ("SUBMIT".equals(plan.stage())) {
            // Only a definitely unsent request or a confirmed failed provider job reaches here.
            workflow.setProviderSubmitRequest(null);
            workflow.setProviderSubmitBody(null);
            workflow.setProviderSubmitStartedAt(null);
        }
        // Failed stages were already observed; an interrupted active stage ends only on recovery.
        if (VideoWorkflowObservations.ACTIVE_STATES.contains(workflow.getStatus())) {
            finishStage(workflow, VideoMetrics.Outcome.INTERRUPTED);
        }
        workflow.setRetryRound(workflow.getRetryRound() + 1);
        workflow.setAttemptStartedAt(Instant.now());
        workflow.setStageStartedAt(workflow.getAttemptStartedAt());
        workflow.setPollRound(0);
        workflow.setErrorCode(null);
        workflow.setErrorMessage(null);
        workflow.setStage(plan.stage());
        switch (plan.stage()) {
            case "SUBMIT" -> {
                workflow.setStatus("QUEUED");
                workflow.setProgress(0);
                workflow.setTaskId(tasks.submit(VideoSubmitHandler.TYPE, "VIDEO_PROVIDER", Map.of("workflowId", id),
                        taskKey(workflow, "submit"), userId).getId());
            }
            case "POLL" -> {
                // Refresh the temporary result URL through the existing provider job, never resubmit it.
                workflow.setProviderResultUrl(null);
                workflow.setStatus("GENERATING");
                workflow.setProgress(18);
                enqueuePoll(workflow, 0);
            }
            case "QA" -> {
                workflow.setStatus("QA");
                workflow.setProgress(94);
                workflow.setTaskId(tasks.submit(VideoQaHandler.TYPE, "MEDIA_CPU", Map.of("workflowId", id),
                        taskKey(workflow, "qa"), userId).getId());
            }
            default -> throw new IllegalStateException("未知视频恢复阶段");
        }
        return view(workflow);
    }

    private record RetryPlan(String stage, boolean newSubmission, String hint) {}

    private RetryPlan retryPlan(VideoWorkflow workflow, String status) {
        if (published(workflow)) return new RetryPlan(null, false, "视频已通过校验并交付，不允许重试或清理");
        if (!Set.of("FAILED", "INTERRUPTED").contains(status)) {
            return new RetryPlan(null, false, "当前视频状态不允许重试");
        }
        String code = Objects.toString(workflow.getErrorCode(), "");
        boolean legacyProviderFailure = "VIDEO_PROVIDER_FAILED".equals(code);
        if ("FAILED".equals(status) && !Set.of("VIDEO_SUBMIT_FAILED", "VIDEO_POLL_FAILED", "VIDEO_IMPORT_FAILED",
                "VIDEO_TIMEOUT", "VIDEO_ASSET_MISSING", "VIDEO_ASSET_INVALID", "VIDEO_QA_FAILED", "VIDEO_PROVIDER_GENERATION_FAILED",
                "VIDEO_PROVIDER_FAILED").contains(code)) {
            return new RetryPlan(null, false, "该失败不能直接重试，请先修正参数或供应商配置；必要时联系管理员核对");
        }
        if (workflow.getProviderJobId() != null) {
            var job = providerJobs.findByWorkflowIdAndProviderJobId(workflow.getId(), workflow.getProviderJobId());
            boolean confirmedFailure = job.map(j -> Set.of("FAILED", "CANCELLED").contains(j.getStatus())).orElse(false);
            if (confirmedFailure) {
                return new RetryPlan("SUBMIT", true, "供应商已确认原任务失败；重试会创建新的供应商任务，可能再次计费");
            }
            if (legacyProviderFailure || "VIDEO_PROVIDER_GENERATION_FAILED".equals(code)
                    || Set.of("FAILED", "CANCELLED").contains(Objects.toString(workflow.getProviderStatus(), ""))) {
                return new RetryPlan(null, false, "缺少可确认的供应商终态记录，请联系管理员核对原任务后再处理");
            }
            if ("INTERRUPTED".equals(status) && "QA".equals(workflow.getStage())
                    && workflow.getOutputAssetId() != null && workflow.getOutputStorageKey() != null) {
                return new RetryPlan("QA", false, "恢复原视频的媒体校验，不重新提交供应商任务");
            }
            return new RetryPlan("POLL", false, "继续查询原供应商任务并刷新下载地址，不重新提交；媒体校验仍会完整执行");
        }
        if (workflow.getProviderSubmitStartedAt() != null) {
            return new RetryPlan(null, false, "上次提交结果尚未确认，不能安全创建新任务。请联系管理员先核对供应商记录，避免重复计费");
        }
        if (legacyProviderFailure || "VIDEO_PROVIDER_GENERATION_FAILED".equals(code)) {
            return new RetryPlan(null, false, "供应商失败记录不完整，请联系管理员核对");
        }
        return new RetryPlan("SUBMIT", false, "原请求尚未发出；重试将提交视频生成请求，供应商可能计费");
    }

    private String effectiveStatus(VideoWorkflow workflow) {
        if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(workflow.getStatus()) && workflow.getTaskId() != null) {
            TaskStatus status = tasks.statusForTenant(workflow.getTaskId());
            if (status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) return "INTERRUPTED";
        }
        return workflow.getStatus();
    }

    private void acquirePermit(Long tenantId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("视频并发许可必须在事务中申请");
        }
        if (!rateLimiter.tryAcquireVideoGeneration(tenantId, tenantMaxConcurrent, globalMaxConcurrent, concurrencyPermitTtlSeconds)) {
            throw BizException.of(ErrorCode.RATE_LIMITED, "当前有较多视频任务正在生成，请稍后重试");
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) rateLimiter.releaseVideoGeneration(tenantId);
                }
            });
        } catch (RuntimeException e) {
            rateLimiter.releaseVideoGeneration(tenantId);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public PageResult<VideoDtos.History> history(int page, int size) {
        var result = workflows.findAllByOrderByIdDesc(PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size))));
        return PageResult.of(result, result.getContent().stream().map(w -> new VideoDtos.History(
                w.getId(), w.getRequest().prompt(), w.getModel(), w.getRatio(), w.getDurationSeconds(),
                w.getResolution(), w.getStatus(), w.getCreatedAt())).toList());
    }

    @Transactional(readOnly = true)
    public List<VideoDtos.Capability> capabilities() { return VideoCapabilities.all(); }

    @Transactional
    public boolean beginSubmit(Long workflowId, Task task) {
        if (!tasks.ownsExecution(task)) return false;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId()) || Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(workflow.getStatus())) return false;
        if (expired(workflow)) {
            markFailed(workflow, "VIDEO_TIMEOUT", "视频生成超过最大处理时长");
            return false;
        }
        workflow.setStatus("SUBMITTING");
        workflow.setStage("SUBMIT");
        workflow.setProgress(8);
        return true;
    }

    /**
     * Commit the exact body and a conservative dispatch marker before the HTTP call.
     * Until this marker exists, a persisted candidate is safe to refresh. Afterwards even
     * a timeout or crash may hide a successful submission, so retries must use identical bytes.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> prepareSubmissionRequest(Long workflowId, Task task, Map<String, Object> candidate) {
        if (!tasks.ownsExecution(task)) return Optional.empty();
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId())
                || Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(workflow.getStatus())) {
            return Optional.empty();
        }
        if (expired(workflow)) {
            markFailed(workflow, "VIDEO_TIMEOUT", "视频生成超过最大处理时长");
            return Optional.empty();
        }
        boolean dispatched = workflow.getProviderSubmitStartedAt() != null;
        String body = workflow.getProviderSubmitBody();
        if (!dispatched) {
            if (candidate == null || candidate.isEmpty()) {
                throw new SubmissionRequestException("VIDEO_SUBMIT_REQUEST_INVALID", "视频提交请求为空，尚未向供应商提交，请重新创建视频");
            }
            try {
                body = json.writeValueAsString(candidate);
            } catch (JsonProcessingException e) {
                throw new SubmissionRequestException("VIDEO_SUBMIT_REQUEST_INVALID", "视频提交请求无法保存，尚未向供应商提交，请联系管理员");
            }
        } else if (body == null || body.isBlank()) {
            // V17 JSONB cannot recover the original wire bytes or prove that HTTP never ran.
            throw new SubmissionRequestException("VIDEO_SUBMIT_REPLAY_UNSAFE",
                    "历史视频提交缺少原始请求，无法安全重试。请联系管理员先核对供应商是否已创建任务；确认未创建后，请重新创建视频");
        }
        validateReferenceUrls(body, dispatched);
        if (!dispatched) {
            workflow.setProviderSubmitRequest(candidate);
            workflow.setProviderSubmitBody(body);
            workflow.setProviderSubmitStartedAt(Instant.now());
        }
        return Optional.of(body);
    }

    private void validateReferenceUrls(String body, boolean dispatched) {
        String guidance = dispatched
                ? "请联系管理员先核对供应商是否已创建任务；确认未创建后，请重新创建视频"
                : "尚未向供应商提交，请联系管理员检查签名有效期和服务器时间后重新创建视频";
        List<Instant> expirations = new ArrayList<>();
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Invalid request");
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode item : content) {
                    if ("image_url".equals(item.path("type").asText())) {
                        expirations.add(referenceExpiry(item.path("image_url").path("url").asText()));
                    }
                }
            }
            if (root.has("input_reference")) {
                expirations.add(referenceExpiry(root.path("input_reference").path("url").asText()));
            }
        } catch (JsonProcessingException | RuntimeException e) {
            throw new SubmissionRequestException("VIDEO_REFERENCE_URL_INVALID", "无法确认参考素材链接的有效期。" + guidance);
        }
        Instant requiredUntil = Instant.now().plusSeconds(Math.max(referenceUrlSafetySeconds, providerTimeoutSeconds + 25));
        if (expirations.stream().anyMatch(expiry -> !expiry.isAfter(requiredUntil))) {
            throw new SubmissionRequestException("VIDEO_REFERENCE_URL_EXPIRED", "参考素材链接已过期或即将过期，无法安全提交。" + guidance);
        }
    }

    private Instant referenceExpiry(String url) {
        // Our object-storage implementation signs SigV4 URLs. Parsing requires no DNS/network access.
        String query = URI.create(url).getRawQuery();
        if (query == null) throw new IllegalArgumentException("Missing signature");
        Map<String, String> parameters = new HashMap<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (parameters.putIfAbsent(name, value) != null) throw new IllegalArgumentException("Duplicate signature parameter");
        }
        long seconds = Long.parseLong(parameters.get("X-Amz-Expires"));
        if (seconds < 1 || seconds > 604800) throw new IllegalArgumentException("Invalid signature lifetime");
        return Instant.from(SIGNING_DATE.parse(parameters.get("X-Amz-Date"))).plusSeconds(seconds);
    }

    public static final class SubmissionRequestException extends NonRetryableTaskException {
        public SubmissionRequestException(String code, String message) { super(code, message, null); }
    }

    @Transactional
    public void saveSubmission(Long workflowId, Task task, VideoProviderGateway.SubmitResult result) {
        if (!tasks.ownsExecution(task)) return;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId())
                || Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(workflow.getStatus())) return;
        VideoProviderJob job = new VideoProviderJob();
        job.setTenantId(workflow.getTenantId());
        job.setWorkflowId(workflowId);
        job.setProvider(result.provider());
        job.setModel(workflow.getModel());
        job.setProviderJobId(result.providerJobId());
        job.setStatus(result.status());
        providerJobs.saveAndFlush(job);
        workflow.setProviderJobId(result.providerJobId());
        workflow.setProviderStatus(result.status());
        if ("FAILED".equals(result.status()) || "CANCELLED".equals(result.status())) {
            markFailed(workflow, "VIDEO_PROVIDER_GENERATION_FAILED", "供应商已确认视频生成失败");
        } else {
            workflow.setStatus("GENERATING");
            advanceStage(workflow, "POLL");
            workflow.setProgress(18);
            enqueuePoll(workflow, 0);
        }
    }

    @Transactional
    public boolean beginPoll(Long workflowId, Task task) {
        if (!tasks.ownsExecution(task)) return false;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId()) || workflow.getProviderJobId() == null
                || Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(workflow.getStatus())) return false;
        if (expired(workflow) || workflow.getPollRound() >= maxPolls) {
            markFailed(workflow, "VIDEO_TIMEOUT", "视频供应商在规定时间内未完成任务");
            return false;
        }
        workflow.setStage("POLL");
        workflow.setProgress(Math.min(78, 18 + workflow.getPollRound() * 4));
        return true;
    }

    @Transactional
    public void savePoll(Long workflowId, Task task, VideoProviderGateway.PollResult result) {
        if (!tasks.ownsExecution(task)) return;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId())
                || Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(workflow.getStatus())) return;
        if (expired(workflow)) {
            markFailed(workflow, "VIDEO_TIMEOUT", "视频供应商在规定时间内未完成任务");
            return;
        }
        workflow.setProviderStatus(result.status());
        workflow.setPollRound(workflow.getPollRound() + 1);
        VideoProviderJob job = providerJobs.findByWorkflowIdAndProviderJobId(workflowId, workflow.getProviderJobId()).orElseThrow();
        job.setStatus(result.status());
        job.setPollRound(workflow.getPollRound());
        job.setLastErrorMessage(result.error());
        if ("SUCCEEDED".equals(result.status())) {
            if (result.resultUrl() == null) throw new IllegalStateException("供应商已完成但未返回视频 URL");
            job.setResultUrl(result.resultUrl());
            workflow.setProviderResultUrl(result.resultUrl());
            workflow.setStatus("IMPORTING");
            advanceStage(workflow, "IMPORT");
            workflow.setProgress(82);
            Task importTask = tasks.submit(VideoImportHandler.TYPE, "MEDIA_CPU", Map.of("workflowId", workflowId),
                    taskKey(workflow, "import"), workflow.getCreatedBy());
            workflow.setTaskId(importTask.getId());
        } else if ("FAILED".equals(result.status()) || "CANCELLED".equals(result.status())) {
            markFailed(workflow, "VIDEO_PROVIDER_GENERATION_FAILED", result.error() == null ? "供应商生成失败" : result.error());
        } else if (workflow.getPollRound() >= maxPolls || expired(workflow)) {
            markFailed(workflow, "VIDEO_TIMEOUT", "视频供应商在规定时间内未完成任务");
        } else {
            workflow.setStatus("GENERATING");
            enqueuePoll(workflow, nextPollDelay(workflow.getPollRound()));
        }
    }

    @Transactional
    public boolean beginImport(Long workflowId, Task task) {
        if (!tasks.ownsExecution(task)) return false;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId())
                || published(workflow) || Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(workflow.getStatus())) return false;
        if (expired(workflow)) {
            markFailed(workflow, "VIDEO_TIMEOUT", "视频生成超过最大处理时长");
            return false;
        }
        workflow.setStage("IMPORT");
        workflow.setProgress(86);
        return true;
    }

    /** Commit an independently named candidate before any streaming PUT can leave an orphan. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> reserveImportKey(Long workflowId, Task task) {
        if (!tasks.ownsExecution(task)) return Optional.empty();
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId()) || !"IMPORTING".equals(workflow.getStatus()) || published(workflow)) {
            return Optional.empty();
        }
        String key = newImportKey(workflow);
        var keys = new ArrayList<>(workflow.getPendingCleanupKeys());
        keys.add(key);
        workflow.setPendingCleanupKeys(keys);
        return Optional.of(key);
    }

    @Transactional
    public void cleanupImportObject(Long workflowId, String key) {
        discardStaleImport(lock(workflowId), key);
    }

    @Transactional
    public void saveImportedVideo(Long workflowId, Task task, String storageKey, long sizeBytes, String contentType) {
        if (!tasks.ownsExecution(task)) {
            discardStaleImport(lock(workflowId), storageKey);
            return;
        }
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId())
                || published(workflow) || Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(workflow.getStatus())) {
            discardStaleImport(workflow, storageKey);
            return;
        }
        Asset asset = new Asset();
        asset.setTenantId(workflow.getTenantId());
        asset.setCreatedBy(workflow.getCreatedBy());
        asset.setName("AI 视频-" + workflow.getId() + ".mp4");
        asset.setType("VIDEO");
        // PENDING is reserved for abandoned direct uploads and has a separate scheduled cleanup.
        asset.setStatus("PROCESSING");
        asset.setSource("GENERATED");
        asset.setStorageKey(storageKey);
        asset.setSizeBytes(sizeBytes);
        asset.setMimeType(contentType == null || contentType.isBlank() ? "video/mp4" : contentType);
        assets.saveAndFlush(asset);
        workflow.setOutputAssetId(asset.getId());
        workflow.setOutputStorageKey(storageKey);
        var keys = new ArrayList<>(workflow.getPendingCleanupKeys());
        keys.remove(storageKey);
        workflow.setPendingCleanupKeys(keys);
        workflow.setStatus("QA");
        advanceStage(workflow, "QA");
        workflow.setProgress(94);
        Task qa = tasks.submit(VideoQaHandler.TYPE, "MEDIA_CPU", Map.of("workflowId", workflowId),
                taskKey(workflow, "qa"), workflow.getCreatedBy());
        workflow.setTaskId(qa.getId());
    }

    @Transactional
    public void completeQa(Long workflowId, Task task) {
        if (!tasks.ownsExecution(task)) return;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId())
                || published(workflow) || Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(workflow.getStatus())) return;
        if (workflow.getOutputAssetId() == null || workflow.getOutputStorageKey() == null) {
            markFailed(workflow, "VIDEO_ASSET_MISSING", "视频对象未能保存");
            return;
        }
        Asset output = assets.findById(workflow.getOutputAssetId()).orElse(null);
        if (output == null) {
            markFailed(workflow, "VIDEO_ASSET_MISSING", "视频素材记录不存在");
            return;
        }
        if (storage.stat(workflow.getOutputStorageKey()).isEmpty()) {
            markFailed(workflow, "VIDEO_ASSET_MISSING", "视频对象不存在");
            return;
        }
        try {
            var metadata = videoProbe.probe(workflow.getOutputStorageKey(), "video/mp4");
            var warnings = assessOutput(workflow, metadata.width(), metadata.height(), metadata.durationMs());
            if (!warnings.isEmpty()) {
                // A valid paid output is retained. The same assessment is exposed in every successful view.
                log.warn("视频成片参数与请求需核对，保留成片: workflow={} warnings={}", workflowId, warnings);
            }
            output.setWidth(metadata.width());
            output.setHeight(metadata.height());
            output.setDurationMs(metadata.durationMs());
            output.setMimeType(metadata.mimeType());
            output.setSizeBytes(storage.stat(workflow.getOutputStorageKey()).orElseThrow().sizeBytes());
            output.setStatus("READY");
            assets.saveAndFlush(output);
        } catch (IllegalStateException e) {
            assets.findById(workflow.getOutputAssetId()).filter(asset -> !"READY".equals(asset.getStatus())).ifPresent(asset -> {
                asset.setStatus("INVALID");
                assets.saveAndFlush(asset);
            });
            markFailed(workflow, "VIDEO_ASSET_INVALID", "生成的视频文件无法通过媒体校验");
            return;
        }
        workflow.setStatus("SUCCEEDED");
        workflow.setOutputPublishedAt(Instant.now());
        advanceStage(workflow, "DONE");
        workflow.setProgress(100);
        workflow.setErrorCode(null);
        workflow.setErrorMessage(null);
        releasePermitIfTerminal(workflow);
    }

    /** Compare ffprobe metadata, never provider-reported dimensions. No automatic paid resubmission. */
    private List<VideoDtos.QaWarning> assessOutput(VideoWorkflow workflow, Integer width, Integer height, Integer durationMs) {
        if (width == null || height == null || durationMs == null || width < 1 || height < 1 || durationMs < 1) {
            return List.of(new VideoDtos.QaWarning("VIDEO_METADATA_UNAVAILABLE", "成片的实际参数记录不完整，请核对成片或联系管理员。"));
        }
        var warnings = new ArrayList<VideoDtos.QaWarning>();
        int requestedShortEdge = switch (workflow.getResolution()) {
            case "480p" -> 480;
            case "720p" -> 720;
            case "1080p" -> 1080;
            case "4K" -> 2160; // UHD; p denotes the short edge for portrait/square output too.
            default -> throw new IllegalStateException("视频请求的分辨率无法核验");
        };
        int pixelTolerance = Math.max(16, (int) Math.ceil(requestedShortEdge * 0.02));
        if (Math.abs(Math.min(width, height) - requestedShortEdge) > pixelTolerance) {
            warnings.add(new VideoDtos.QaWarning("VIDEO_RESOLUTION_MISMATCH", "分辨率与请求不一致：请求 "
                    + workflow.getResolution() + "（短边 " + requestedShortEdge + " px），实际 " + width + "×" + height + " px。"));
        }
        if (!"auto".equals(workflow.getRatio())) {
            String[] parts = workflow.getRatio().split(":");
            int horizontal = Integer.parseInt(parts[0]);
            int vertical = Integer.parseInt(parts[1]);
            boolean orientationMismatch = horizontal > vertical && width <= height || horizontal < vertical && width >= height;
            if (orientationMismatch) {
                warnings.add(new VideoDtos.QaWarning("VIDEO_ORIENTATION_MISMATCH", "画面方向与请求不一致：请求 "
                        + workflow.getRatio() + "，实际 " + width + "×" + height + " px。"));
            } else {
                long actual = (long) width * vertical;
                long expected = (long) height * horizontal;
                if (Math.abs(actual - expected) > expected * 0.02) {
                    warnings.add(new VideoDtos.QaWarning("VIDEO_RATIO_MISMATCH", "画面比例与请求不一致：请求 "
                            + workflow.getRatio() + "，实际 " + width + "×" + height + " px。"));
                }
            }
        }
        long requestedMs = workflow.getDurationSeconds() * 1000L;
        long durationTolerance = Math.max(300L, requestedMs * 2 / 100);
        if (Math.abs(durationMs.longValue() - requestedMs) > durationTolerance) {
            warnings.add(new VideoDtos.QaWarning("VIDEO_DURATION_MISMATCH", "时长与请求不一致：请求 "
                    + workflow.getDurationSeconds() + " 秒，实际 " + String.format(Locale.ROOT, "%.3f", durationMs / 1000D) + " 秒。"));
        }
        return List.copyOf(warnings);
    }

    @Transactional
    public void markFailed(Long workflowId, String code, String message) {
        VideoWorkflow workflow = lock(workflowId);
        markFailed(workflow, code, message);
    }

    @Transactional
    public void markFailed(Long workflowId, Task task, String code, String message) {
        // An old HTTP/download may fail after a client has already resumed or canceled the workflow.
        if (!tasks.ownsExecution(task)) return;
        VideoWorkflow workflow = lock(workflowId);
        if (Objects.equals(workflow.getTaskId(), task.getId())) markFailed(workflow, code, message);
    }

    private void markFailed(VideoWorkflow workflow, String code, String message) {
        if (published(workflow)) return;
        if (Set.of("FAILED", "CANCELLED").contains(workflow.getStatus())) {
            cleanupUnpublishedOutput(workflow);
            return;
        }
        finishStage(workflow, VideoMetrics.Outcome.FAILED);
        workflow.setStatus("FAILED");
        workflow.setStage("FAILED");
        workflow.setStageStartedAt(Instant.now());
        workflow.setErrorCode(code);
        workflow.setErrorMessage(message);
        cleanupUnpublishedOutput(workflow);
        releasePermitIfTerminal(workflow);
    }

    private void enqueuePoll(VideoWorkflow workflow, int delaySeconds) {
        Task poll = tasks.submit(VideoPollHandler.TYPE, "VIDEO_PROVIDER", Map.of("workflowId", workflow.getId()),
                taskKey(workflow, "poll") + "-" + (workflow.getPollRound() + 1), workflow.getCreatedBy());
        poll.setRunAfter(Instant.now().plusSeconds(delaySeconds));
        workflow.setTaskId(poll.getId());
    }

    private void advanceStage(VideoWorkflow workflow, String nextStage) {
        finishStage(workflow, VideoMetrics.Outcome.SUCCEEDED);
        workflow.setStage(nextStage);
        workflow.setStageStartedAt(Instant.now());
    }

    private void finishStage(VideoWorkflow workflow, VideoMetrics.Outcome outcome) {
        VideoMetrics.Stage stage;
        try { stage = VideoMetrics.Stage.valueOf(workflow.getStage()); }
        catch (IllegalArgumentException e) { return; } // DONE/FAILED/CANCELLED are not timed stages.
        Instant startedAt = workflow.getStageStartedAt();
        if (startedAt == null) startedAt = workflow.getCreatedAt();
        Duration elapsed = Duration.between(startedAt, Instant.now());
        int rounds = workflow.getPollRound();
        Runnable record = () -> metrics.recordStage(stage, elapsed, outcome, rounds);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { record.run(); }
            });
        } else {
            record.run();
        }
    }

    private int nextPollDelay(int round) { return Math.min(60, Math.max(5, pollSeconds) * Math.max(1, round)); }

    private boolean expired(VideoWorkflow workflow) {
        Instant created = workflow.getAttemptStartedAt() == null ? workflow.getCreatedAt() : workflow.getAttemptStartedAt();
        return created == null || created.plusSeconds(Math.max(1, maxDurationSeconds)).isBefore(Instant.now());
    }

    private String taskKey(VideoWorkflow workflow, String stage) {
        return "video-" + stage + "-" + workflow.getId()
                + (workflow.getRetryRound() == 0 ? "" : "-retry-" + workflow.getRetryRound());
    }

    public static String submissionKey(VideoWorkflow workflow) {
        return "video-" + workflow.getId() + "-submit"
                + (workflow.getSubmissionGeneration() == 0 ? "" : "-generation-" + workflow.getSubmissionGeneration());
    }

    private String outputKey(VideoWorkflow workflow) {
        return "t" + workflow.getTenantId() + "/generated-video/" + workflow.getId() + "/"
                + (workflow.getRetryRound() == 0 ? "" : "retry-" + workflow.getRetryRound() + "/") + "output.mp4";
    }

    private String newImportKey(VideoWorkflow workflow) {
        String base = outputKey(workflow);
        return base.substring(0, base.length() - "output.mp4".length()) + "import-" + UUID.randomUUID() + "/output.mp4";
    }

    private boolean published(VideoWorkflow workflow) {
        // Only successful QA sets 100; older late failures could overwrite status but kept progress.
        return workflow.getOutputPublishedAt() != null || "SUCCEEDED".equals(workflow.getStatus()) || workflow.getProgress() == 100;
    }

    private boolean ownedVideoKey(VideoWorkflow workflow, String key) {
        String prefix = "t" + workflow.getTenantId() + "/generated-video/" + workflow.getId() + "/";
        return key != null && key.matches(Pattern.quote(prefix) + "(?:retry-[1-9][0-9]*/)?(?:import-[0-9a-f-]{36}/)?output\\.mp4");
    }

    private boolean deleteUnpublishedKey(VideoWorkflow workflow, String key) {
        if (!ownedVideoKey(workflow, key)) {
            log.warn("拒绝清理不属于视频工作流的对象键: workflow={} key={}", workflow.getId(), key);
            return false;
        }
        if (Objects.equals(key, workflow.getOutputStorageKey()) && published(workflow)) return false;
        try {
            // READY is a conservative fallback for legacy rows whose publication history is incomplete.
            if (assets.findByStorageKey(key).filter(asset -> "READY".equals(asset.getStatus())).isPresent()) return false;
            storage.delete(key);
            return true;
        }
        catch (RuntimeException e) {
            log.warn("未交付视频对象清理失败，保留对象键: workflow={} key={}", workflow.getId(), key, e);
            return false;
        }
    }

    /** Failed/canceled outputs were never delivered. Keep keys when storage is unavailable. */
    private void cleanupUnpublishedOutput(VideoWorkflow workflow) {
        if (published(workflow)) return;
        var keys = new ArrayList<>(workflow.getPendingCleanupKeys());
        if (workflow.getOutputStorageKey() != null && !keys.contains(workflow.getOutputStorageKey())) {
            keys.add(workflow.getOutputStorageKey());
        }
        // A crash can leave an imported object without an Asset row; its deterministic key is known.
        if (!keys.contains(outputKey(workflow))) keys.add(outputKey(workflow));
        if (workflow.getOutputAssetId() != null) {
            assets.findById(workflow.getOutputAssetId()).filter(asset -> !"READY".equals(asset.getStatus()))
                    .ifPresent(asset -> asset.setStatus("INVALID"));
        }
        workflow.setOutputAssetId(null);
        workflow.setOutputStorageKey(null);
        keys.removeIf(key -> deleteUnpublishedKey(workflow, key));
        workflow.setPendingCleanupKeys(keys);
    }

    private void discardStaleImport(VideoWorkflow workflow, String key) {
        // A canceled download can finish after terminal cleanup; never delete a published/current candidate.
        if (Objects.equals(key, workflow.getOutputStorageKey())
                && (published(workflow) || "QA".equals(workflow.getStatus()))) return;
        var keys = new ArrayList<>(workflow.getPendingCleanupKeys());
        if (deleteUnpublishedKey(workflow, key)) keys.remove(key);
        else if (!keys.contains(key)) keys.add(key);
        workflow.setPendingCleanupKeys(keys);
    }

    private void releasePermitIfTerminal(VideoWorkflow workflow) {
        if (!workflow.isVideoConcurrencyPermitHeld()
                || !Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(workflow.getStatus())) return;
        workflow.setVideoConcurrencyPermitHeld(false);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { rateLimiter.releaseVideoGeneration(workflow.getTenantId()); }
            });
        } else {
            rateLimiter.releaseVideoGeneration(workflow.getTenantId());
        }
    }

    @Transactional
    public VideoDtos.View cancel(Long id) {
        VideoWorkflow workflow = lock(id);
        if (published(workflow)) return view(workflow);
        if (Set.of("FAILED", "CANCELLED").contains(workflow.getStatus())) {
            cleanupUnpublishedOutput(workflow);
            return view(workflow);
        }
        if (workflow.getTaskId() != null) tasks.cancel(workflow.getTaskId());
        finishStage(workflow, VideoMetrics.Outcome.CANCELED);
        workflow.setStatus("CANCELLED");
        workflow.setStage("CANCELLED");
        workflow.setStageStartedAt(Instant.now());
        workflow.setProgress(Math.min(workflow.getProgress(), 99));
        workflow.setErrorCode(null);
        workflow.setErrorMessage("已取消本次视频生成");
        cleanupUnpublishedOutput(workflow);
        releasePermitIfTerminal(workflow);
        return view(workflow);
    }

    private VideoWorkflow find(Long id) {
        return workflows.findById(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "视频任务不存在"));
    }

    private VideoWorkflow lock(Long id) {
        return workflows.lock(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "视频任务不存在"));
    }

    private VideoDtos.View view(VideoWorkflow workflow) {
        String url = !"SUCCEEDED".equals(workflow.getStatus()) || workflow.getOutputStorageKey() == null
                ? null : storage.presignGet(workflow.getOutputStorageKey(), Duration.ofMinutes(30));
        String status = effectiveStatus(workflow);
        RetryPlan plan = retryPlan(workflow, status);
        VideoDtos.Create request = workflow.getRequest();
        Asset output = workflow.getOutputAssetId() == null ? null : assets.findById(workflow.getOutputAssetId()).orElse(null);
        Integer actualWidth = output == null ? null : output.getWidth();
        Integer actualHeight = output == null ? null : output.getHeight();
        Integer actualDurationMs = output == null ? null : output.getDurationMs();
        // Derive from already persisted Asset metadata: no second source of truth or new migration.
        List<VideoDtos.QaWarning> warnings = "SUCCEEDED".equals(status)
                ? assessOutput(workflow, actualWidth, actualHeight, actualDurationMs) : List.of();
        return new VideoDtos.View(workflow.getId(), workflow.getRequestKey(), request.prompt(), request.referenceImageAssetIds(),
                request.referenceVideoAssetId(), workflow.getModel(), workflow.getRatio(), workflow.getDurationSeconds(),
                workflow.getResolution(), status, workflow.getStage(), workflow.getProgress(),
                workflow.getProviderStatus(), workflow.getErrorMessage(), url, workflow.getOutputAssetId(), workflow.getTaskId(), workflow.getCreatedAt(),
                request.referenceImageAssetIds().stream().map(this::referenceView).toList(),
                request.referenceVideoAssetId() == null ? null : referenceView(request.referenceVideoAssetId()),
                workflow.getErrorCode(), plan.stage() != null, plan.stage(), "SUBMIT".equals(plan.stage()), plan.hint(),
                actualWidth, actualHeight, actualDurationMs, warnings);
    }

    private VideoDtos.Reference referenceView(Long id) {
        return assets.findById(id).map(asset -> new VideoDtos.Reference(asset.getId(), asset.getName(),
                "READY".equals(asset.getStatus()) ? storage.presignGet(asset.getStorageKey(), Duration.ofHours(1)) : null))
                .orElse(new VideoDtos.Reference(id, "参考素材已不可用", null));
    }

    private String hash(VideoDtos.Create request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request))); }
        catch (Exception e) { throw new IllegalStateException("视频请求无法序列化", e); }
    }

    /** Download the provider's temporary URL into our private object store. */
    public ImportedVideo importUrl(VideoWorkflow workflow) {
        return importUrl(workflow, newImportKey(workflow));
    }

    public ImportedVideo importUrl(VideoWorkflow workflow, String key) {
        if (published(workflow) || !ownedVideoKey(workflow, key) || assets.findByStorageKey(key).isPresent()) {
            throw new VideoImportException(VideoImportException.Reason.OUTPUT_PROTECTED, "视频对象不可覆盖");
        }
        try {
            String url = workflow.getProviderResultUrl();
            if (url == null || url.isBlank()) throw new VideoImportException(VideoImportException.Reason.MISSING_URL, "供应商视频 URL 为空");
            URI current = checkedImportUrl(url);
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                HttpGet request = new HttpGet(current);
                request.setConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                        .setConnectTimeout(Timeout.ofSeconds(15))
                        .setResponseTimeout(Timeout.ofMinutes(10))
                        .build());
                DownloadResult result = httpClient.execute(request, response -> downloadResponse(response, key));
                if (result.redirectLocation() != null) {
                    if (redirects == MAX_REDIRECTS) throw new VideoImportException(VideoImportException.Reason.REDIRECT_LIMIT, "供应商视频重定向次数过多");
                    try {
                        current = checkedImportUrl(current.resolve(result.redirectLocation()).toString());
                    } catch (IllegalArgumentException e) {
                        throw new VideoImportException(VideoImportException.Reason.INVALID_URL, e.getMessage(), e);
                    }
                    continue;
                }
                return result.imported();
            }
            throw new VideoImportException(VideoImportException.Reason.REDIRECT_LIMIT, "供应商视频重定向次数过多");
        } catch (Exception e) {
            // The handler retries deletion in a short transaction; the reserved key stays durable on failure.
            try { storage.delete(key); }
            catch (RuntimeException cleanupError) {
                log.warn("视频导入异常后的对象清理失败，保留已登记对象键: workflow={} key={}", workflow.getId(), key, cleanupError);
            }
            if (e instanceof VideoImportException importError) throw importError;
            if (e instanceof BizException) throw (BizException) e;
            throw new VideoImportException(VideoImportException.Reason.TRANSFER_FAILED, "网络或存储传输中断，请稍后重试", e);
        }
    }

    private URI checkedImportUrl(String url) {
        try { return VideoUrlSecurity.checkedHttps(url); }
        catch (IllegalArgumentException e) {
            // DNS failure is not evidence of an unsafe URL; keep the HTTPS/private-network checks intact.
            var reason = e.getCause() instanceof java.net.UnknownHostException
                    ? VideoImportException.Reason.TRANSFER_FAILED : VideoImportException.Reason.INVALID_URL;
            throw new VideoImportException(reason, e.getMessage(), e);
        }
    }

    private DownloadResult downloadResponse(org.apache.hc.core5.http.ClassicHttpResponse response, String key) throws IOException {
        int status = response.getCode();
        if (status >= 300 && status < 400) {
            String location = response.getFirstHeader("Location") == null ? null
                    : response.getFirstHeader("Location").getValue();
            EntityUtils.consumeQuietly(response.getEntity());
            if (location == null || location.isBlank()) throw new VideoImportException(VideoImportException.Reason.REDIRECT_LOCATION_MISSING, "视频下载响应缺少重定向地址");
            return new DownloadResult(location, null);
        }
        if (status < 200 || status >= 300) {
            EntityUtils.consumeQuietly(response.getEntity());
            if (status == 404 || status == 410) {
                throw new VideoImportException(VideoImportException.Reason.RESULT_URL_UNAVAILABLE,
                        "视频下载地址不可用（HTTP " + status + "，可能已失效）。可重试恢复原供应商任务以获取新地址；不要重复提交生成请求。");
            }
            boolean retryable = status == 408 || status == 429 || status >= 500;
            throw new VideoImportException(retryable ? VideoImportException.Reason.HTTP_TRANSIENT : VideoImportException.Reason.HTTP_REJECTED,
                    "视频下载 HTTP " + status);
        }
        HttpEntity entity = response.getEntity();
        if (entity == null) throw new VideoImportException(VideoImportException.Reason.EMPTY_RESPONSE, "视频下载响应为空");
        long declaredLength = entity.getContentLength();
        if (declaredLength > maxProviderBytes) throw new VideoImportException(VideoImportException.Reason.TOO_LARGE, "视频文件超过大小限制");
        String contentType = entity.getContentType();
        if (contentType == null || contentType.isBlank()) contentType = "video/mp4";
        try (InputStream input = entity.getContent()) {
            CountingVideoInputStream tracked = new CountingVideoInputStream(input, maxProviderBytes);
            try { storage.put(key, tracked, declaredLength > 0 ? declaredLength : -1L, contentType); }
            catch (RuntimeException e) {
                // MinIO converts stream exceptions to a cause-less STORAGE_UNAVAILABLE BizException.
                if (tracked.count() > maxProviderBytes) throw new VideoImportException(VideoImportException.Reason.TOO_LARGE, "视频文件超过大小限制", e);
                throw e;
            }
            if (declaredLength > 0 && tracked.count() < declaredLength) throw new IOException("视频下载流提前结束");
            if (tracked.count() <= 0) throw new VideoImportException(VideoImportException.Reason.EMPTY_RESPONSE, "视频下载内容为空");
            if (!isVideoContent(contentType, tracked.prefix())) {
                throw new VideoImportException(VideoImportException.Reason.INVALID_CONTENT, "供应商返回的内容不是有效视频");
            }
            return new DownloadResult(null, new ImportedVideo(key, tracked.count(), contentType));
        }
    }

    private boolean isVideoContent(String contentType, byte[] prefix) {
        String type = contentType.toLowerCase(java.util.Locale.ROOT);
        if (type.startsWith("video/")) return true;
        if (!"application/octet-stream".equals(type)) return false;
        for (int i = 0; i + 4 <= prefix.length && i < 32; i++) {
            if (prefix[i] == 'f' && prefix[i + 1] == 't' && prefix[i + 2] == 'y' && prefix[i + 3] == 'p') return true;
        }
        return prefix.length >= 4 && (prefix[0] == 0x1A && (prefix[1] & 0xff) == 0x45
                && (prefix[2] & 0xff) == 0xDF && (prefix[3] & 0xff) == 0xA3);
    }

    private record DownloadResult(String redirectLocation, ImportedVideo imported) {}

    private static final class CountingVideoInputStream extends FilterInputStream {
        private final long limit;
        private final java.io.ByteArrayOutputStream prefix = new java.io.ByteArrayOutputStream(64);
        private long count;

        private CountingVideoInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) add(value);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                if (prefix.size() < 64) prefix.write(bytes, offset, Math.min(read, 64 - prefix.size()));
                count += read;
                if (count > limit) throw new VideoImportException(VideoImportException.Reason.TOO_LARGE, "视频文件超过大小限制");
            }
            return read;
        }

        private void add(int value) throws IOException {
            count++;
            if (prefix.size() < 64) prefix.write(value);
            if (count > limit) throw new VideoImportException(VideoImportException.Reason.TOO_LARGE, "视频文件超过大小限制");
        }

        private long count() { return count; }
        private byte[] prefix() { return prefix.toByteArray(); }
    }

    public record ImportedVideo(String storageKey, long sizeBytes, String contentType) {}
}
