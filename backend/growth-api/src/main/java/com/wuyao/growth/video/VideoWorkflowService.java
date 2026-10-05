package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.Asset;
import com.wuyao.growth.asset.AssetRepository;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.asset.VideoMediaProbe;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class VideoWorkflowService {
    private static final int MAX_REDIRECTS = 5;

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

    @Value("${growth.video.provider.poll-seconds:15}") private int pollSeconds;
    @Value("${growth.video.max-polls:360}") private int maxPolls;
    @Value("${growth.video.max-duration-seconds:7200}") private long maxDurationSeconds;
    @Value("${growth.video.max-provider-bytes:1073741824}") private long maxProviderBytes;
    @Value("${growth.video.tenant-max-concurrent:2}") private int tenantMaxConcurrent;
    @Value("${growth.video.global-max-concurrent:20}") private int globalMaxConcurrent;
    @Value("${growth.video.concurrency-permit-ttl-seconds:21600}") private long concurrencyPermitTtlSeconds;

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

        if (!rateLimiter.tryAcquireVideoGeneration(tenantId, tenantMaxConcurrent, globalMaxConcurrent,
                concurrencyPermitTtlSeconds)) {
            throw BizException.of(ErrorCode.RATE_LIMITED, "当前有较多视频任务正在生成，请稍后重试");
        }
        try {
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
            workflows.saveAndFlush(workflow);
            Task task = tasks.submit(VideoSubmitHandler.TYPE, "VIDEO_PROVIDER", Map.of("workflowId", workflow.getId()),
                    "video-submit-" + workflow.getId(), userId);
            workflow.setTaskId(task.getId());
            return view(workflow);
        } catch (RuntimeException e) {
            rateLimiter.releaseVideoGeneration(tenantId);
            throw e;
        }
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
        if (expired(workflow)) {
            markFailed(workflow, "VIDEO_TIMEOUT", "视频生成超过最大处理时长");
            return false;
        }
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
        if ("FAILED".equals(result.status()) || "CANCELED".equals(result.status())) {
            markFailed(workflow, "VIDEO_PROVIDER_FAILED", "供应商提交失败");
        } else {
            workflow.setStatus("GENERATING");
            workflow.setStage("POLL");
            workflow.setProgress(18);
            enqueuePoll(workflow, 0);
        }
    }

    @Transactional
    public boolean beginPoll(Long workflowId, Task task) {
        if (!tasks.ownsExecution(task)) return false;
        VideoWorkflow workflow = lock(workflowId);
        if (!Objects.equals(workflow.getTaskId(), task.getId()) || workflow.getProviderJobId() == null) return false;
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
        if (!Objects.equals(workflow.getTaskId(), task.getId())) return;
        if (expired(workflow)) {
            markFailed(workflow, "VIDEO_TIMEOUT", "视频供应商在规定时间内未完成任务");
            return;
        }
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
        if (!Objects.equals(workflow.getTaskId(), task.getId()) || workflow.getProviderResultUrl() == null) return false;
        if (expired(workflow)) {
            markFailed(workflow, "VIDEO_TIMEOUT", "视频生成超过最大处理时长");
            return false;
        }
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
        try {
            var metadata = videoProbe.probe(workflow.getOutputStorageKey(), "video/mp4");
            Asset output = assets.findById(workflow.getOutputAssetId()).orElseThrow();
            output.setWidth(metadata.width());
            output.setHeight(metadata.height());
            output.setDurationMs(metadata.durationMs());
            output.setMimeType(metadata.mimeType());
            output.setSizeBytes(storage.stat(workflow.getOutputStorageKey()).orElseThrow().sizeBytes());
            output.setStatus("READY");
            assets.saveAndFlush(output);
        } catch (IllegalStateException e) {
            assets.findById(workflow.getOutputAssetId()).ifPresent(asset -> {
                asset.setStatus("INVALID");
                assets.saveAndFlush(asset);
            });
            markFailed(workflow, "VIDEO_ASSET_INVALID", "生成的视频文件无法通过媒体校验");
            return;
        }
        workflow.setStatus("SUCCEEDED");
        workflow.setStage("DONE");
        workflow.setProgress(100);
        workflow.setErrorCode(null);
        workflow.setErrorMessage(null);
        releasePermitIfTerminal(workflow);
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
        releasePermitIfTerminal(workflow);
    }

    private void enqueuePoll(VideoWorkflow workflow, int delaySeconds) {
        Task poll = tasks.submit(VideoPollHandler.TYPE, "VIDEO_PROVIDER", Map.of("workflowId", workflow.getId()),
                "video-poll-" + workflow.getId() + "-" + (workflow.getPollRound() + 1), workflow.getCreatedBy());
        poll.setRunAfter(Instant.now().plusSeconds(delaySeconds));
        workflow.setTaskId(poll.getId());
    }

    private int nextPollDelay(int round) { return Math.min(60, Math.max(5, pollSeconds) * Math.max(1, round)); }

    private boolean expired(VideoWorkflow workflow) {
        Instant created = workflow.getCreatedAt();
        return created == null || created.plusSeconds(Math.max(1, maxDurationSeconds)).isBefore(Instant.now());
    }

    private void releasePermitIfTerminal(VideoWorkflow workflow) {
        if (!workflow.isVideoConcurrencyPermitHeld()
                || !Set.of("SUCCEEDED", "FAILED", "CANCELED").contains(workflow.getStatus())) return;
        workflow.setVideoConcurrencyPermitHeld(false);
        rateLimiter.releaseVideoGeneration(workflow.getTenantId());
    }

    @Transactional
    public VideoDtos.View cancel(Long id) {
        VideoWorkflow workflow = lock(id);
        if (Set.of("SUCCEEDED", "FAILED", "CANCELED").contains(workflow.getStatus())) return view(workflow);
        if (workflow.getTaskId() != null) tasks.cancel(workflow.getTaskId());
        workflow.setStatus("CANCELED");
        workflow.setStage("CANCELED");
        workflow.setProgress(Math.min(workflow.getProgress(), 99));
        workflow.setErrorCode(null);
        workflow.setErrorMessage("已取消本次视频生成");
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
        if (url == null) throw new IllegalStateException("供应商视频 URL 为空");
        URI current;
        try {
            current = VideoUrlSecurity.checkedHttps(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        String key = "t" + workflow.getTenantId() + "/generated-video/" + workflow.getId() + "/output.mp4";
        try {
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                HttpGet request = new HttpGet(current);
                request.setConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                        .setConnectTimeout(Timeout.ofSeconds(15))
                        .setResponseTimeout(Timeout.ofMinutes(10))
                        .build());
                DownloadResult result = httpClient.execute(request, response -> downloadResponse(response, key));
                if (result.redirectLocation() != null) {
                    if (redirects == MAX_REDIRECTS) throw new IllegalStateException("供应商视频重定向次数过多");
                    try {
                        current = VideoUrlSecurity.checkedHttps(current.resolve(result.redirectLocation()).toString());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                    continue;
                }
                return result.imported();
            }
            throw new IllegalStateException("供应商视频重定向次数过多");
        } catch (Exception e) {
            try { storage.delete(key); } catch (RuntimeException ignored) { }
            if (e instanceof BizException) throw (BizException) e;
            throw new IllegalStateException("保存供应商视频失败", e);
        }
    }

    private DownloadResult downloadResponse(org.apache.hc.core5.http.ClassicHttpResponse response, String key) throws IOException {
        int status = response.getCode();
        if (status >= 300 && status < 400) {
            String location = response.getFirstHeader("Location") == null ? null
                    : response.getFirstHeader("Location").getValue();
            EntityUtils.consumeQuietly(response.getEntity());
            if (location == null || location.isBlank()) throw new IllegalStateException("视频下载响应缺少重定向地址");
            return new DownloadResult(location, null);
        }
        if (status < 200 || status >= 300) {
            EntityUtils.consumeQuietly(response.getEntity());
            throw new IllegalStateException("视频下载 HTTP " + status);
        }
        HttpEntity entity = response.getEntity();
        if (entity == null) throw new IllegalStateException("视频下载响应为空");
        long declaredLength = entity.getContentLength();
        if (declaredLength > maxProviderBytes) throw new IllegalStateException("视频文件超过大小限制");
        String contentType = entity.getContentType();
        if (contentType == null || contentType.isBlank()) contentType = "video/mp4";
        try (InputStream input = entity.getContent()) {
            CountingVideoInputStream tracked = new CountingVideoInputStream(input, maxProviderBytes);
            storage.put(key, tracked, declaredLength > 0 ? declaredLength : -1L, contentType);
            if (tracked.count() <= 0) throw new IllegalStateException("视频下载内容为空");
            if (!isVideoContent(contentType, tracked.prefix())) {
                try { storage.delete(key); } catch (RuntimeException ignored) { }
                throw new IllegalStateException("供应商返回的内容不是有效视频");
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
                if (count > limit) throw new IOException("视频文件超过大小限制");
            }
            return read;
        }

        private void add(int value) throws IOException {
            count++;
            if (prefix.size() < 64) prefix.write(value);
            if (count > limit) throw new IOException("视频文件超过大小限制");
        }

        private long count() { return count; }
        private byte[] prefix() { return prefix.toByteArray(); }
    }

    public record ImportedVideo(String storageKey, long sizeBytes, String contentType) {}
}
