package com.wuyao.growth.copywriting;

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
import com.wuyao.growth.creative.image.ImageCreationService;
import com.wuyao.growth.iam.service.AccountService;
import com.wuyao.growth.product.ProductDtos;
import com.wuyao.growth.product.ProductService;
import com.wuyao.growth.store.StoreAccessService;
import com.wuyao.growth.store.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 文案创作：每次写 3 条，预占 1 烟火，成功扣除，失败退回；改写不另收费，每批最多 5 次；定稿收进门店志。 */
@Service
@RequiredArgsConstructor
public class CopyService {
    static final int REWRITE_LIMIT = 5;
    static final int MIN_VERSIONS = 2;
    /** 一次最多附的图片数，和文本模型适配器的上限一致。 */
    static final int MAX_IMAGES = com.wuyao.growth.common.gateway.ChatCompletionTextAdapter.MAX_IMAGES;
    private final CopyBatchRepository batches;
    private final CopyJournalRepository journal;
    private final StoreAccessService storeAccess;
    private final StoreService stores;
    private final ProductService products;
    private final AssetService assets;
    private final ImageCreationService images;
    private final AccountService accounts;
    private final TaskService tasks;
    private final TaskRepository taskRepository;
    private final AiGateway gateway;
    private final BillingService billing;

    public CopyDtos.Config config() {
        var styles = CopyPrompt.STYLES.entrySet().stream().map(e -> new CopyDtos.Style(e.getKey(), e.getValue())).toList();
        return new CopyDtos.Config(gateway.configured(ModelAlias.TEXT_WRITER), BillingService.COPY_CREDITS,
                MAX_IMAGES, 3, REWRITE_LIMIT, styles);
    }

    @Transactional
    public CopyDtos.View create(CopyDtos.Create request, Long userId) {
        billing.lockForGeneration();
        if (!gateway.configured(ModelAlias.TEXT_WRITER)) throw BizException.of(ErrorCode.COPY_NOT_CONFIGURED, "文案模型尚未配置");
        storeAccess.requireAccess(request.storeId(), userId);
        if (!CopyPrompt.STYLES.containsKey(request.style())) throw BizException.of(ErrorCode.BAD_REQUEST, "不支持的风格");
        String note = trim(request.note());
        var imageIds = List.copyOf(request.imageAssetIds());
        var workIds = List.copyOf(request.workItemIds());
        var productIds = List.copyOf(request.productIds());
        var existing = batches.findByRequestKey(request.requestKey());
        if (existing.isPresent()) {
            var prior = existing.get();
            if (!prior.getStoreId().equals(request.storeId()) || !prior.getPlatform().equals(request.platform())
                    || !prior.getStyle().equals(request.style()) || !prior.getStoreLevel().equals(request.storeLevel())
                    || prior.isVoiceOn() != request.voiceOn() || !prior.getImageAssetIds().equals(imageIds)
                    || !prior.getWorkItemIds().equals(workIds)
                    || !prior.getProducts().stream().map(CopyDtos.ProductRef::id).toList().equals(productIds)
                    || !Objects.equals(prior.getNote(), note) || !Objects.equals(prior.getPreviousBatchId(), request.previousBatchId())) {
                throw BizException.of(ErrorCode.CONFLICT, "请求编号已用于不同的文案，请重新提交");
            }
            return view(prior);
        }
        if (imageIds.isEmpty() && workIds.isEmpty() && productIds.isEmpty() && note == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请上传图片、选一张作品或写一句想说的话");
        }
        if (imageIds.size() + workIds.size() > MAX_IMAGES) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "图片最多 " + MAX_IMAGES + " 张");
        }
        if (imageIds.stream().distinct().count() != imageIds.size() || workIds.stream().distinct().count() != workIds.size()
                || productIds.stream().distinct().count() != productIds.size()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "素材重复");
        }
        billing.requireMembership("COPY");
        imageIds.forEach(assets::imageReference);
        workIds.forEach(id -> images.workImage(id).orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST, "作品不存在或还没完成")));
        var chosen = productIds.stream().map(id -> products.get(id, userId)).toList();
        if (chosen.stream().anyMatch(p -> !p.storeId().equals(request.storeId()))) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "只能选当前门店的商品");
        }
        if (request.previousBatchId() != null) {
            var previous = batches.findById(request.previousBatchId())
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "上一批文案不存在"));
            if (!"SUCCEEDED".equals(previous.getStatus()) || !previous.getStoreId().equals(request.storeId())) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "上一批文案还没有完成");
            }
        }
        var batch = new CopyBatch();
        batch.setTenantId(TenantContext.require());
        batch.setStoreId(request.storeId());
        batch.setCreatedBy(userId);
        batch.setRequestKey(request.requestKey());
        batch.setPlatform(request.platform());
        batch.setStyle(request.style());
        batch.setStoreLevel(request.storeLevel());
        batch.setVoiceOn(request.voiceOn());
        batch.setImageAssetIds(imageIds);
        batch.setWorkItemIds(workIds);
        batch.setProducts(chosen.stream().map(p -> new CopyDtos.ProductRef(p.id(), p.name())).toList());
        batch.setProductFacts(chosen.isEmpty() ? null : chosen.stream().map(CopyService::productFacts).collect(Collectors.joining("\n")));
        batch.setNote(note);
        batch.setPreviousBatchId(request.previousBatchId());
        batch.setBillingReservationId(billing.reserve("copy-generate:" + request.requestKey(), "COPY", BillingService.COPY_CREDITS,
                (request.previousBatchId() == null ? "文案创作" : "文案创作换一批") + " · 预占 " + BillingService.COPY_CREDITS + " 烟火"));
        batches.saveAndFlush(batch);
        Task task = tasks.submit(CopyGenerateHandler.TYPE, "DEFAULT", Map.of("batchId", batch.getId()),
                "copy-generate-" + batch.getId(), userId);
        batch.setTaskId(task.getId());
        return view(batch);
    }

    @Transactional(readOnly = true)
    public CopyDtos.View get(Long id, Long userId) {
        var batch = find(id);
        storeAccess.requireAccess(batch.getStoreId(), userId);
        return view(batch);
    }

    /** 创作记录：这家店的每次生成，新的在前，包含生成中和失败的。 */
    @Transactional(readOnly = true)
    public PageResult<CopyDtos.History> history(Long storeId, String platform, int page, int size, Long userId) {
        storeAccess.requireAccess(storeId, userId);
        if (platform != null && !platform.isBlank() && Arrays.stream(CopyPlatform.values()).noneMatch(p -> p.name().equals(platform))) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "不支持的平台");
        }
        var pageable = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        var result = platform == null || platform.isBlank() ? batches.findByStoreIdOrderByIdDesc(storeId, pageable)
                : batches.findByStoreIdAndPlatformOrderByIdDesc(storeId, platform, pageable);
        var ids = result.getContent().stream().map(CopyBatch::getId).toList();
        var finalized = ids.isEmpty() ? Set.<Long>of() : new HashSet<>(journal.finalizedBatchIds(ids));
        var names = result.getContent().stream().anyMatch(b -> b.getCreatedBy() != null && !b.getCreatedBy().equals(userId))
                ? accounts.members().stream().collect(Collectors.toMap(a -> a.id(), a -> a.name() == null ? "" : a.name(), (a, b) -> a))
                : Map.<Long, String>of();
        return PageResult.of(result, result.getContent().stream().map(b -> new CopyDtos.History(
                b.getId(), b.getPlatform(), b.getStyle(), b.getStatus(), firstLine(b), thumbnail(b),
                !b.getImageAssetIds().isEmpty() || !b.getWorkItemIds().isEmpty(),
                b.getCreatedBy() == null || b.getCreatedBy().equals(userId) ? null : names.get(b.getCreatedBy()),
                finalized.contains(b.getId()), b.getErrorMessage(), b.getCreatedAt())).toList());
    }

    @Transactional
    public CopyDtos.View rewrite(Long id, int index, CopyDtos.Rewrite request, Long userId) {
        if (!gateway.configured(ModelAlias.TEXT_WRITER)) throw BizException.of(ErrorCode.COPY_NOT_CONFIGURED, "文案模型尚未配置");
        var batch = batches.lock(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "文案不存在"));
        storeAccess.requireAccess(batch.getStoreId(), userId);
        if (!"SUCCEEDED".equals(batch.getStatus()) || batch.getVersions() == null) throw BizException.of(ErrorCode.BAD_REQUEST, "这批文案还没写好");
        if (index < 0 || index >= batch.getVersions().size()) throw BizException.of(ErrorCode.NOT_FOUND, "这条文案不存在");
        if (batch.getVersions().stream().anyMatch(v -> v.get("rewriting") != null)) {
            throw BizException.of(ErrorCode.CONFLICT, "上一次改写还没完成，请稍等");
        }
        if (batch.getRewriteCount() >= REWRITE_LIMIT) throw BizException.of(ErrorCode.BAD_REQUEST, "这批文案已改写 " + REWRITE_LIMIT + " 次，可以换一批");
        billing.requireMembership("COPY");
        var versions = copy(batch.getVersions());
        var version = versions.get(index);
        version.put("rewriting", request.action());
        version.remove("rewriteError");
        batch.setVersions(versions);
        batch.setRewriteCount(batch.getRewriteCount() + 1);
        int revision = ((Number) version.getOrDefault("revision", 0)).intValue();
        tasks.submit(CopyRewriteHandler.TYPE, "DEFAULT", Map.of("batchId", id, "index", index, "revision", revision,
                "action", request.action()), "copy-rewrite-" + id + "-" + batch.getRewriteCount(), userId);
        return view(batch);
    }

    /** 定稿：把这一条按发出去的样子收进门店志。同一条重复定稿返回原记录。 */
    @Transactional
    public CopyDtos.JournalEntry finalizeVersion(CopyDtos.Finalize request, Long userId) {
        var batch = find(request.batchId());
        storeAccess.requireAccess(batch.getStoreId(), userId);
        var existing = journal.findByBatchIdAndVersionIndex(batch.getId(), request.versionIndex());
        if (existing.isPresent()) return entry(existing.get());
        if (!"SUCCEEDED".equals(batch.getStatus()) || batch.getVersions() == null || request.versionIndex() >= batch.getVersions().size()) {
            throw BizException.of(ErrorCode.NOT_FOUND, "这条文案不存在");
        }
        var version = batch.getVersions().get(request.versionIndex());
        if (version.get("rewriting") != null) throw BizException.of(ErrorCode.CONFLICT, "这条还在改写，等改完再定稿");
        var values = stores.profile(batch.getStoreId()).map(CopyContexts::storeValues).orElse(Map.of());
        var entry = new CopyJournalEntry();
        entry.setTenantId(batch.getTenantId());
        entry.setStoreId(batch.getStoreId());
        entry.setBatchId(batch.getId());
        entry.setVersionIndex(request.versionIndex());
        entry.setPlatform(batch.getPlatform());
        String title = fill((String) version.getOrDefault("title", ""), values);
        entry.setTitle(title.isBlank() ? null : title);
        entry.setBody(fill((String) version.get("body"), values));
        @SuppressWarnings("unchecked") List<String> tags = (List<String>) version.getOrDefault("tags", List.of());
        entry.setTags(new ArrayList<>(tags));
        entry.setFinalizedBy(userId);
        return entry(journal.saveAndFlush(entry));
    }

    @Transactional(readOnly = true)
    public PageResult<CopyDtos.JournalEntry> journal(Long storeId, int page, int size, Long userId) {
        storeAccess.requireAccess(storeId, userId);
        var result = journal.findByStoreIdOrderByIdDesc(storeId, PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size))));
        return PageResult.of(result, result.getContent().stream().map(CopyService::entry).toList());
    }

    // ---- 生成任务 ----

    @Transactional(readOnly = true)
    public CopyBatch processing(Long id) { return find(id); }

    @Transactional(readOnly = true)
    public List<String> previousAngles(Long previousBatchId) {
        if (previousBatchId == null) return List.of();
        return batches.findById(previousBatchId).map(CopyBatch::getVersions).orElse(List.of()).stream()
                .map(v -> String.valueOf(v.getOrDefault("angle", ""))).filter(a -> !a.isBlank()).toList();
    }

    @Transactional(readOnly = true)
    public List<Long> billingRecoveryTaskIds(List<String> reservations) {
        return reservations.isEmpty() ? List.of() : batches.billingRecoveryTaskIds(reservations);
    }

    @Transactional
    public boolean begin(Long id, Task task) {
        var batch = owned(id, task);
        if (batch == null) return false;
        batch.setStatus("GENERATING");
        batch.setErrorMessage(null);
        return true;
    }

    @Transactional
    public boolean completed(Long id, Task task, List<Map<String, Object>> versions) {
        var batch = owned(id, task);
        if (batch == null) return false;
        if (batch.getBillingReservationId() != null) billing.capture(batch.getBillingReservationId(), BillingService.COPY_CREDITS,
                "copy-generate:" + id + ":capture", "文案创作 · 已写 " + versions.size() + " 条");
        batch.setVersions(versions);
        batch.setStatus("SUCCEEDED");
        return true;
    }

    @Transactional
    public void failed(Long id, Task task, String message) {
        var batch = owned(id, task);
        if (batch != null) markFailed(batch, message);
    }

    @Transactional
    public void taskFailed(TaskTerminalFailure event) {
        if (!(event.payload().get("batchId") instanceof Number id)) return;
        billing.lockForGeneration();
        var batch = batches.lock(id.longValue()).orElse(null);
        if (batch == null || batch.terminal() || !Objects.equals(batch.getTaskId(), event.taskId())) return;
        markFailed(batch, "文案没有写完，预占烟火已退回，请重试");
    }

    // ---- 改写任务 ----

    /** 改写结果只写回仍在等这次改写的那一条；之后又被改过的，丢弃这次结果。 */
    @Transactional
    public boolean rewritten(Long id, int index, int revision, Map<String, Object> result) {
        var batch = batches.lock(id).orElse(null);
        var version = pending(batch, index, revision);
        if (version == null) return false;
        var versions = copy(batch.getVersions());
        var updated = versions.get(index);
        for (String key : List.of("title", "body", "tags", "aiScore", "aiReason")) updated.put(key, result.get(key));
        updated.put("revision", revision + 1);
        updated.remove("rewriting");
        batch.setVersions(versions);
        return true;
    }

    @Transactional
    public void rewriteFailed(Long id, int index, int revision, String message) {
        var batch = batches.lock(id).orElse(null);
        if (pending(batch, index, revision) == null) return;
        var versions = copy(batch.getVersions());
        versions.get(index).remove("rewriting");
        versions.get(index).put("rewriteError", message);
        batch.setVersions(versions);
        batch.setRewriteCount(Math.max(0, batch.getRewriteCount() - 1));
    }

    @Transactional
    public void rewriteTaskFailed(TaskTerminalFailure event) {
        if (event.payload().get("batchId") instanceof Number id && event.payload().get("index") instanceof Number index
                && event.payload().get("revision") instanceof Number revision) {
            rewriteFailed(id.longValue(), index.intValue(), revision.intValue(), "改写没有完成，请重试");
        }
    }

    private static Map<String, Object> pending(CopyBatch batch, int index, int revision) {
        if (batch == null || batch.getVersions() == null || index < 0 || index >= batch.getVersions().size()) return null;
        var version = batch.getVersions().get(index);
        return version.get("rewriting") != null && ((Number) version.getOrDefault("revision", 0)).intValue() == revision ? version : null;
    }

    private void markFailed(CopyBatch batch, String message) {
        if (batch.getBillingReservationId() != null) billing.release(batch.getBillingReservationId(),
                "copy-generate:" + batch.getId() + ":release", "文案创作 · 未完成，退回烟火");
        batch.setStatus("FAILED");
        batch.setErrorMessage(message);
    }

    private CopyBatch owned(Long id, Task task) {
        // 状态变化和结算与其他生成功能一样先锁账户，再锁记录。
        billing.lockForGeneration();
        if (taskRepository.findOwnedRunning(task.getId(), task.getAttempts()).isEmpty()) return null;
        var batch = batches.lock(id).orElseThrow();
        return batch.terminal() || !Objects.equals(batch.getTaskId(), task.getId()) ? null : batch;
    }

    private CopyBatch find(Long id) {
        return batches.findById(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "文案不存在"));
    }

    private CopyDtos.View view(CopyBatch b) {
        var pictures = new ArrayList<CopyDtos.Image>();
        b.getImageAssetIds().forEach(id -> pictures.add(assets.imagePreview(id)
                .map(image -> new CopyDtos.Image("ASSET", id, image.name(), image.url()))
                .orElse(new CopyDtos.Image("ASSET", id, "图片已删除", null))));
        b.getWorkItemIds().forEach(id -> pictures.add(images.workImage(id)
                .map(work -> new CopyDtos.Image("WORK", id, work.title(), work.url()))
                .orElse(new CopyDtos.Image("WORK", id, "作品已删除", null))));
        var finalized = b.getId() == null ? List.<Integer>of()
                : journal.findByBatchId(b.getId()).stream().map(CopyJournalEntry::getVersionIndex).sorted().toList();
        var store = stores.profile(b.getStoreId()).map(CopyContexts::storeValues).orElse(Map.of());
        return new CopyDtos.View(b.getId(), b.getStoreId(), b.getStatus(), b.getPlatform(), b.getStyle(), b.getStoreLevel(),
                b.isVoiceOn(), b.getNote(), pictures, b.getProducts(), b.getPreviousBatchId(), b.getVersions(), finalized,
                b.getRewriteCount(), REWRITE_LIMIT, store, b.getErrorMessage(), b.getCreatedAt());
    }

    private String thumbnail(CopyBatch b) {
        if (!b.getImageAssetIds().isEmpty()) return assets.imagePreview(b.getImageAssetIds().getFirst()).map(AssetService.ImagePreview::url).orElse(null);
        if (!b.getWorkItemIds().isEmpty()) return images.workImage(b.getWorkItemIds().getFirst()).map(ImageCreationService.WorkImage::url).orElse(null);
        return null;
    }

    private static String firstLine(CopyBatch b) {
        if (b.getVersions() == null || b.getVersions().isEmpty()) return b.getNote() == null ? null : CopyPrompt.text(b.getNote(), 40);
        var first = b.getVersions().getFirst();
        String title = String.valueOf(first.getOrDefault("title", ""));
        String text = title.isBlank() ? String.valueOf(first.getOrDefault("body", "")) : title;
        return CopyPrompt.text(text.lines().filter(line -> !line.isBlank()).findFirst().orElse(""), 40);
    }

    private static CopyDtos.JournalEntry entry(CopyJournalEntry e) {
        return new CopyDtos.JournalEntry(e.getId(), e.getBatchId(), e.getVersionIndex(), e.getPlatform(), e.getTitle(),
                e.getBody(), e.getTags(), e.getFinalizedAt());
    }

    /** 把〔店名〕等占位符换成门店资料里的值；门店后来清空了的字段保留占位符原样。 */
    static String fill(String text, Map<String, String> values) {
        if (text == null) return "";
        var matcher = CopyGuard.STORE_TOKEN.matcher(text);
        var out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String productFacts(ProductDtos.View p) {
        var parts = Stream.of(
                p.price() == null ? null : "价格 " + p.price().stripTrailingZeros().toPlainString() + " 元" + (CopyPrompt.blank(p.saleUnit()) ? "" : "/" + p.saleUnit()),
                CopyPrompt.blank(p.specification()) ? null : "规格 " + p.specification().strip(),
                CopyPrompt.blank(p.promotionRule()) ? null : "优惠 " + p.promotionRule().strip(),
                CopyPrompt.blank(p.coreSellingPoints()) ? null : "卖点 " + p.coreSellingPoints().strip()).filter(Objects::nonNull).toList();
        return "商品「" + p.name() + "」" + (parts.isEmpty() ? "" : "：" + String.join("；", parts));
    }

    private static List<Map<String, Object>> copy(List<Map<String, Object>> versions) {
        return versions.stream().map(v -> (Map<String, Object>) new LinkedHashMap<>(v)).collect(Collectors.toCollection(ArrayList::new));
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
