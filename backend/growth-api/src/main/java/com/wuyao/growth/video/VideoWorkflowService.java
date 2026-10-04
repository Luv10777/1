package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.Asset;
import com.wuyao.growth.asset.AssetRepository;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoWorkflowService {
    private static final int MAX_PROVIDER_BYTES = 1024 * 1024 * 1024;

    private final VideoWorkflowRepository workflows;
    private final VideoProviderJobRepository providerJobs;
    private final AssetRepository assets;
    private final AssetService assetService;
    private final ObjectStorage storage;
    private final TaskService tasks;
    private final ObjectMapper json;

    @Value("${growth.video.provider.poll-seconds:15}") private int pollSeconds;

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
        workflows.saveAndFlush(workflow);
        Task task = tasks.submit(VideoSubmitHandler.TYPE, "VIDEO_PROVIDER", Map.of("workflowId", workflow.getId()),
                "video-submit-" + workflow.getId(), userId);
        workflow.setTaskId(task.getId());
        return view(workflow);
    }

    @Transactional(readOnly = true)
    public VideoDtos.View get(Long id) { return view(find(id)); }

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
        if (!Objects.equals(workflow.getTaskId(), task.getId()) || Set.of("SUCCEEDED", "FAILED", "CANCELED").contains(workflow.getStatus())) return false;
        workflow.setStatus("SUBMITTING");
        workflow.setStage("SUBMIT");
        workflow.setProgress(8);
        return true;
    }

    @Transactional
    public void saveSubmission(Long workflowId, Task task, VideoProviderGateway.SubmitResult result) {
        if (!tasks.ownsExecution(task)) return;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId())) return;
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
        workflow.setStatus("GENERATING");
        workflow.setStage("POLL");
        workflow.setProgress(18);
        enqueuePoll(workflow, 0);
    }

    @Transactional
    public boolean beginPoll(Long workflowId, Task task) {
        if (!tasks.ownsExecution(task)) return false;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId()) || workflow.getProviderJobId() == null) return false;
        workflow.setStage("POLL");
        workflow.setProgress(Math.min(78, 18 + workflow.getPollRound() * 4));
        return true;
    }

    @Transactional
    public void savePoll(Long workflowId, Task task, VideoProviderGateway.PollResult result) {
        if (!tasks.ownsExecution(task)) return;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId())) return;
        workflow.setProviderStatus(result.status());
        workflow.setPollRound(workflow.getPollRound() + 1);
        VideoProviderJob job = providerJobs.findByWorkflowId(workflowId).orElseThrow();
        job.setStatus(result.status());
        job.setPollRound(workflow.getPollRound());
        job.setLastErrorMessage(result.error());
        if ("SUCCEEDED".equals(result.status())) {
            if (result.resultUrl() == null) throw new IllegalStateException("供应商已完成但未返回视频 URL");
            job.setResultUrl(result.resultUrl());
            workflow.setProviderResultUrl(result.resultUrl());
            workflow.setStatus("IMPORTING");
            workflow.setStage("IMPORT");
            workflow.setProgress(82);
            Task importTask = tasks.submit(VideoImportHandler.TYPE, "MEDIA_CPU", Map.of("workflowId", workflowId),
                    "video-import-" + workflowId, workflow.getCreatedBy());
            workflow.setTaskId(importTask.getId());
        } else if ("FAILED".equals(result.status()) || "CANCELED".equals(result.status())) {
            markFailed(workflow, "VIDEO_PROVIDER_FAILED", result.error() == null ? "供应商生成失败" : result.error());
        } else {
            workflow.setStatus("GENERATING");
            enqueuePoll(workflow, nextPollDelay(workflow.getPollRound()));
        }
    }

    @Transactional
    public boolean beginImport(Long workflowId, Task task) {
        if (!tasks.ownsExecution(task)) return false;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId()) || workflow.getProviderResultUrl() == null) return false;
        workflow.setStage("IMPORT");
        workflow.setProgress(86);
        return true;
    }

    @Transactional
    public void saveImportedVideo(Long workflowId, Task task, String storageKey, long sizeBytes, String contentType) {
        if (!tasks.ownsExecution(task)) return;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId())) return;
        Asset asset = new Asset();
        asset.setTenantId(workflow.getTenantId());
        asset.setCreatedBy(workflow.getCreatedBy());
        asset.setName("AI 视频-" + workflow.getId() + ".mp4");
        asset.setType("VIDEO");
        asset.setStatus("READY");
        asset.setSource("GENERATED");
        asset.setStorageKey(storageKey);
        asset.setSizeBytes(sizeBytes);
        asset.setMimeType(contentType == null || contentType.isBlank() ? "video/mp4" : contentType);
        assets.saveAndFlush(asset);
        workflow.setOutputAssetId(asset.getId());
        workflow.setOutputStorageKey(storageKey);
        workflow.setStatus("QA");
        workflow.setStage("QA");
        workflow.setProgress(94);
        Task qa = tasks.submit(VideoQaHandler.TYPE, "MEDIA_CPU", Map.of("workflowId", workflowId),
                "video-qa-" + workflowId, workflow.getCreatedBy());
        workflow.setTaskId(qa.getId());
    }

    @Transactional
    public void completeQa(Long workflowId, Task task) {
        if (!tasks.ownsExecution(task)) return;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId())) return;
        if (workflow.getOutputAssetId() == null || workflow.getOutputStorageKey() == null) {
            markFailed(workflow, "VIDEO_ASSET_MISSING", "视频对象未能保存");
            return;
        }
        if (storage.stat(workflow.getOutputStorageKey()).isEmpty()) {
            markFailed(workflow, "VIDEO_ASSET_MISSING", "视频对象不存在");
            return;
        }
        workflow.setStatus("SUCCEEDED");
        workflow.setStage("DONE");
        workflow.setProgress(100);
        workflow.setErrorCode(null);
        workflow.setErrorMessage(null);
    }

    @Transactional
    public void markFailed(Long workflowId, String code, String message) {
        VideoWorkflow workflow = lock(workflowId);
        markFailed(workflow, code, message);
    }

    private void markFailed(VideoWorkflow workflow, String code, String message) {
        workflow.setStatus("FAILED");
        workflow.setStage("FAILED");
        workflow.setErrorCode(code);
        workflow.setErrorMessage(message);
    }

    private void enqueuePoll(VideoWorkflow workflow, int delaySeconds) {
        Task poll = tasks.submit(VideoPollHandler.TYPE, "VIDEO_PROVIDER", Map.of("workflowId", workflow.getId()),
                "video-poll-" + workflow.getId() + "-" + (workflow.getPollRound() + 1), workflow.getCreatedBy());
        poll.setRunAfter(Instant.now().plusSeconds(delaySeconds));
        workflow.setTaskId(poll.getId());
    }

    private int nextPollDelay(int round) { return Math.min(60, Math.max(5, pollSeconds) * Math.max(1, round)); }

    private VideoWorkflow find(Long id) {
        return workflows.findById(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "视频任务不存在"));
    }

    private VideoWorkflow lock(Long id) {
        return workflows.lock(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "视频任务不存在"));
    }

    private VideoDtos.View view(VideoWorkflow workflow) {
        String url = workflow.getOutputStorageKey() == null ? null : storage.presignGet(workflow.getOutputStorageKey(), Duration.ofMinutes(30));
        VideoDtos.Create request = workflow.getRequest();
        return new VideoDtos.View(workflow.getId(), workflow.getRequestKey(), request.prompt(), request.referenceImageAssetIds(),
                request.referenceVideoAssetId(), workflow.getModel(), workflow.getRatio(), workflow.getDurationSeconds(),
                workflow.getResolution(), workflow.getStatus(), workflow.getStage(), workflow.getProgress(),
                workflow.getProviderStatus(), workflow.getErrorMessage(), url, workflow.getOutputAssetId(), workflow.getTaskId(), workflow.getCreatedAt(),
                request.referenceImageAssetIds().stream().map(this::referenceView).toList(),
                request.referenceVideoAssetId() == null ? null : referenceView(request.referenceVideoAssetId()));
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
        String url = workflow.getProviderResultUrl();
        if (url == null || !url.startsWith("https://")) throw new IllegalStateException("供应商视频 URL 必须使用 HTTPS");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
            HttpResponse<InputStream> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("视频下载 HTTP " + response.statusCode());
            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (contentLength > MAX_PROVIDER_BYTES) throw new IllegalStateException("视频文件超过 1 GB 限制");
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_PROVIDER_BYTES + 1);
                if (bytes.length > MAX_PROVIDER_BYTES) throw new IllegalStateException("视频文件超过 1 GB 限制");
                String key = "t" + workflow.getTenantId() + "/generated-video/" + workflow.getId() + "/" + UUID.randomUUID() + ".mp4";
                storage.put(key, bytes, "video/mp4");
                return new ImportedVideo(key, bytes.length, "video/mp4");
            }
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("保存供应商视频失败", e); }
    }

    public record ImportedVideo(String storageKey, long sizeBytes, String contentType) {}
}
