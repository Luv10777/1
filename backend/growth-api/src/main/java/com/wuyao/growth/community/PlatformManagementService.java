package com.wuyao.growth.community;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/** The allowlist is checked before any enumeration or cross-tenant transaction. RLS stays enabled. */
@Service
@RequiredArgsConstructor
public class PlatformManagementService {
    private final PlatformProperties config;
    private final PlatformAccountDirectory directory;
    private final InformationPublicationRepository publications;
    private final MerchantApplicationRepository merchants;
    private final RealNameApplicationRepository identities;
    private final PlatformManagementAuditRepository audits;
    private final RealNameCrypto crypto;
    private final PlatformTransactionManager transactionManager;
    private final org.springframework.context.ApplicationEventPublisher events;

    public boolean isOperator(AuthPrincipal me) { return me != null && config.getOperatorUserIds().contains(me.userId()); }

    public void requireOperator(AuthPrincipal me) {
        if (!isOperator(me)) throw BizException.of(ErrorCode.FORBIDDEN, "没有平台运营权限，请联系平台开通");
    }

    public PageResult<PlatformDtos.InformationView> information(AuthPrincipal me, int page, int size, String status, String category, String keyword) {
        requireOperator(me);
        checkPaging(page, size);
        checkFilter(status, Set.of("PENDING_REVIEW", "PUBLISHED", "REJECTED", "OFFLINE", "EXPIRED"));
        checkFilter(category, Set.of("activity", "cooperation", "supply", "notice"));
        var result = new ArrayList<PlatformDtos.InformationView>();
        for (var scope : directory.scopes()) {
            result.addAll(inScope(scope.id(), true, () -> publications.findAllByTenantIdOrderByPublishedAtDescIdDesc(scope.id()).stream()
                    .filter(v -> matches(status, informationStatus(v)) && matches(category, v.getCategory()))
                    .filter(v -> contains(keyword, v.getTitle(), v.getMerchantName(), v.getRegion()))
                    .map(v -> informationView(v, scope.name())).toList()));
        }
        result.sort(Comparator.comparing(PlatformDtos.InformationView::createdAt).thenComparing(PlatformDtos.InformationView::id).reversed());
        return page(result, page, size);
    }

    public PageResult<PlatformDtos.MerchantView> merchants(AuthPrincipal me, int page, int size, String status, String keyword) {
        requireOperator(me);
        checkPaging(page, size);
        checkFilter(status, Set.of("PENDING_REVIEW", "APPROVED", "REJECTED", "SUSPENDED"));
        var result = new ArrayList<PlatformDtos.MerchantView>();
        for (var scope : directory.scopes()) {
            result.addAll(inScope(scope.id(), true, () -> merchants.findAllByTenantIdOrderByCreatedAtDescIdDesc(scope.id()).stream()
                    .filter(v -> matches(status, v.getStatus()))
                    .filter(v -> contains(keyword, v.getMerchantName(), v.getCompanyName(), v.getRegion()))
                    .map(v -> merchantView(v, scope.name())).toList()));
        }
        result.sort(Comparator.comparing(PlatformDtos.MerchantView::createdAt).thenComparing(PlatformDtos.MerchantView::id).reversed());
        return page(result, page, size);
    }

    public PageResult<RealNameDtos.AdminView> identities(AuthPrincipal me, int page, int size, String status, String keyword) {
        requireOperator(me);
        checkPaging(page, size);
        checkFilter(status, Set.of("PENDING_REVIEW", "VERIFIED", "REJECTED"));
        var result = new ArrayList<RealNameDtos.AdminView>();
        for (var scope : directory.scopes()) {
            result.addAll(inScope(scope.id(), true, () -> identities.findAllByTenantIdOrderBySubmittedAtDescIdDesc(scope.id()).stream()
                    .filter(v -> matches(status, v.getStatus()))
                    .map(v -> identityView(v, scope.name()))
                    .filter(v -> contains(keyword, v.userName(), String.valueOf(v.userId()), v.legalNameMasked(), v.idNumberMasked())).toList()));
        }
        result.sort(Comparator.comparing(RealNameDtos.AdminView::submittedAt).thenComparing(RealNameDtos.AdminView::id).reversed());
        return page(result, page, size);
    }

    public PlatformDtos.Summary summary(AuthPrincipal me) {
        requireOperator(me);
        long[] counts = new long[6];
        for (var scope : directory.scopes()) {
            long[] current = inScope(scope.id(), true, () -> {
                var info = publications.findAllByTenantIdOrderByPublishedAtDescIdDesc(scope.id());
                var applications = merchants.findAllByTenantIdOrderByCreatedAtDescIdDesc(scope.id());
                return new long[]{info.stream().filter(v -> "PUBLISHED".equals(informationStatus(v))).count(),
                        info.stream().filter(v -> "OFFLINE".equals(v.getStatus())).count(),
                        applications.stream().filter(v -> "PENDING_REVIEW".equals(v.getStatus())).count(),
                        applications.stream().filter(v -> "APPROVED".equals(v.getStatus())).count(),
                        identities.countByTenantIdAndStatus(scope.id(), "PENDING_REVIEW"),
                        info.stream().filter(v -> "PENDING_REVIEW".equals(v.getStatus())).count()};
            });
            for (int i = 0; i < counts.length; i++) counts[i] += current[i];
        }
        return new PlatformDtos.Summary(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]);
    }

    public void reviewInformation(AuthPrincipal me, Long tenantId, Long id, PlatformDtos.ReviewRequest request) {
        requireOperator(me);
        requireNote(request.note());
        inScope(tenantId, false, () -> {
            var value = publications.findByIdAndTenantId(id, tenantId).orElseThrow(() -> missing("信息"));
            if (!value.getUpdatedAt().equals(request.updatedAt())) {
                throw BizException.of(ErrorCode.CONFLICT, "信息已修改或处理，请刷新后重新核对内容");
            }
            boolean allowed = switch (value.getStatus()) {
                case "PENDING_REVIEW" -> Set.of("PUBLISHED", "REJECTED").contains(request.status());
                case "PUBLISHED" -> "OFFLINE".equals(request.status());
                case "OFFLINE" -> "PUBLISHED".equals(request.status()) && value.getPublishedAt() != null;
                default -> false;
            };
            if (!allowed) throw BizException.of(ErrorCode.CONFLICT, "信息状态已变化或不支持该操作，请刷新后重试");
            if ("PUBLISHED".equals(request.status()) && value.getValidUntil() != null && value.getValidUntil().isBefore(LocalDate.now())) {
                throw BizException.of(ErrorCode.CONFLICT, "该信息已过期，不能恢复上线；请发布者提交新的有效信息");
            }
            if ("PUBLISHED".equals(request.status())) value.setPublishedAt(Instant.now());
            value.setStatus(request.status());
            value.setPlatformBlocked("OFFLINE".equals(request.status()));
            value.setReviewNote(request.note().trim());
            value.setUpdatedAt(Instant.now());
            audit(me.userId(), "INFORMATION", id, request.status(), request.note());
            return null;
        });
    }

    public void reviewMerchant(AuthPrincipal me, Long tenantId, Long id, PlatformDtos.MerchantReviewRequest request) {
        requireOperator(me);
        requireNote(request.note());
        inScope(tenantId, false, () -> {
            var value = merchants.findByIdAndTenantId(id, tenantId).orElseThrow(() -> missing("商家"));
            boolean allowed = switch (value.getStatus()) {
                case "PENDING_REVIEW" -> Set.of("APPROVED", "REJECTED").contains(request.status());
                case "APPROVED" -> "SUSPENDED".equals(request.status());
                case "SUSPENDED" -> "APPROVED".equals(request.status());
                default -> false;
            };
            if (!allowed) throw BizException.of(ErrorCode.CONFLICT, "商家状态已变化或不支持该操作，请刷新后重试");
            value.setStatus(request.status());
            value.setReviewNote(request.note().trim());
            value.setUpdatedAt(Instant.now());
            merchants.flush();
            boolean approved = merchants.findAllByTenantIdOrderByCreatedAtDescIdDesc(tenantId).stream()
                    .anyMatch(application -> "APPROVED".equals(application.getStatus()));
            if (!approved) takeInformationOffline(tenantId);
            events.publishEvent(new MerchantQualificationChanged(tenantId, approved, request.status(), request.note().trim()));
            audit(me.userId(), "MERCHANT", id, request.status(), request.note());
            return null;
        });
    }

    public PlatformDtos.MerchantDetailView revealMerchant(AuthPrincipal me, Long tenantId, Long id, String purpose) {
        requireOperator(me);
        requireNote(purpose);
        return inScope(tenantId, false, () -> {
            var value = merchants.findByIdAndTenantId(id, tenantId).orElseThrow(() -> missing("商家"));
            audit(me.userId(), "MERCHANT", id, "REVEAL", purpose);
            return new PlatformDtos.MerchantDetailView(id, value.getCreditCode(), value.getContactPhone());
        });
    }

    public RealNameDtos.DetailView revealIdentity(AuthPrincipal me, Long tenantId, Long id, String purpose) {
        requireOperator(me);
        requireNote(purpose);
        return inScope(tenantId, false, () -> {
            var value = identities.findByIdAndTenantId(id, tenantId).orElseThrow(() -> missing("实名申请"));
            var user = directory.account(value.getUserId());
            String name = crypto.decrypt(value.getLegalNameCiphertext(), tenantId, value.getUserId(), "name");
            String number = crypto.decrypt(value.getIdNumberCiphertext(), tenantId, value.getUserId(), "number");
            audit(me.userId(), "IDENTITY", id, "REVEAL", purpose);
            return new RealNameDtos.DetailView(id, tenantId, null, value.getUserId(), user.name(), maskPhone(user.phone()), name, number,
                    value.getLegalNameMasked(), value.getIdNumberMasked(), value.getStatus(), value.getReviewNote(), value.getSubmittedAt(), value.getReviewedAt());
        });
    }

    public void reviewIdentity(AuthPrincipal me, Long tenantId, Long id, RealNameDtos.ReviewRequest request) {
        requireOperator(me);
        requireNote(request.note());
        inScope(tenantId, false, () -> {
            Long userId = identities.userIdForReview(id, tenantId).orElseThrow(() -> missing("实名申请"));
            directory.lockAccount(userId);
            // Re-read after the account lock to serialize against submission and other reviewers.
            var value = identities.findByIdAndTenantId(id, tenantId).orElseThrow(() -> missing("实名申请"));
            if (me.userId().equals(value.getUserId())) throw BizException.of(ErrorCode.FORBIDDEN, "不能审核本人的实名申请");
            if (!"PENDING_REVIEW".equals(value.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "该申请已审核，请刷新列表");
            value.setStatus(request.status());
            value.setReviewNote(request.note().trim());
            value.setReviewedBy(me.userId());
            value.setReviewedAt(Instant.now());
            value.setUpdatedAt(Instant.now());
            audit(me.userId(), "IDENTITY", id, request.status(), request.note());
            return null;
        });
    }

    public List<PlatformManagementAudit> auditTrail(AuthPrincipal me, Long tenantId, String type, Long id) {
        requireOperator(me);
        if (!Set.of("INFORMATION", "MERCHANT", "IDENTITY").contains(type)) throw BizException.of(ErrorCode.BAD_REQUEST, "记录类型无效");
        return inScope(tenantId, true, () -> audits.findTop10ByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDescIdDesc(tenantId, type, id));
    }

    private void audit(Long actor, String type, Long id, String action, String note) {
        var event = new PlatformManagementAudit();
        event.setTenantId(TenantContext.require()); event.setActorUserId(actor); event.setTargetType(type);
        event.setTargetId(id); event.setAction(action); event.setNote(note.trim());
        audits.saveAndFlush(event);
    }

    /** 入驻被暂停：这家商户已公开和待审核的信息全部下线。不标记为平台下线，恢复入驻后商户可以重新提交。 */
    private void takeInformationOffline(Long tenantId) {
        publications.findAllByTenantIdOrderByCreatedAtDescIdDesc(tenantId).stream()
                .filter(v -> Set.of("PUBLISHED", "PENDING_REVIEW").contains(v.getStatus()))
                .forEach(v -> {
                    v.setStatus("OFFLINE");
                    v.setUpdatedAt(Instant.now());
                });
    }

    private <T> T inScope(Long tenantId, boolean readOnly, Supplier<T> work) {
        return TenantContext.runAs(tenantId, () -> {
            var tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setReadOnly(readOnly);
            return tx.execute(status -> work.get());
        });
    }

    private static PlatformDtos.InformationView informationView(InformationPublication v, String tenantName) {
        return new PlatformDtos.InformationView(v.getId(), v.getTenantId(), tenantName, v.getCategory(), v.getTitle(), v.getMerchantName(),
                v.getRegion(), v.getSummary(), v.getContent(), v.getContactName(), maskPhone(v.getContactPhone()), v.getValidUntil(),
                informationStatus(v), v.getPublishedAt(), v.getCreatedAt(), v.getReviewNote(), v.getUpdatedAt());
    }

    private static PlatformDtos.MerchantView merchantView(MerchantApplication v, String tenantName) {
        String code = v.getCreditCode();
        String masked = code == null || code.length() < 8 ? "—" : code.substring(0, 4) + "**********" + code.substring(code.length() - 4);
        return new PlatformDtos.MerchantView(v.getId(), v.getTenantId(), tenantName, v.getMerchantName(), v.getCompanyName(), v.getIndustry(),
                v.getRegion(), masked, v.getContactName(), maskPhone(v.getContactPhone()), v.getIntroduction(), v.getStatus(), v.getReviewNote(), v.getCreatedAt(), v.getUpdatedAt());
    }

    private RealNameDtos.AdminView identityView(RealNameApplication v, String tenantName) {
        var user = directory.account(v.getUserId());
        return new RealNameDtos.AdminView(v.getId(), v.getTenantId(), tenantName, v.getUserId(), user.name(), maskPhone(user.phone()),
                v.getLegalNameMasked(), v.getIdNumberMasked(), v.getStatus(), v.getReviewNote(), v.getSubmittedAt(), v.getReviewedAt());
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "—";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String informationStatus(InformationPublication v) {
        return "PUBLISHED".equals(v.getStatus()) && v.getValidUntil() != null && v.getValidUntil().isBefore(LocalDate.now()) ? "EXPIRED" : v.getStatus();
    }
    private static boolean matches(String filter, String value) { return filter == null || filter.isBlank() || filter.equals(value); }
    private static boolean contains(String keyword, String... values) {
        if (keyword == null || keyword.isBlank()) return true;
        String key = keyword.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values).filter(Objects::nonNull).anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(key));
    }
    private static void checkFilter(String value, Set<String> allowed) {
        if (value != null && !value.isBlank() && !allowed.contains(value)) throw BizException.of(ErrorCode.BAD_REQUEST, "筛选条件无效");
    }
    private static void requireNote(String note) {
        if (note == null || note.trim().length() < 5 || note.length() > 500) throw BizException.of(ErrorCode.BAD_REQUEST, "请填写5至500字的处理依据");
    }
    private static void checkPaging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST, "分页参数无效");
    }
    private static <T> PageResult<T> page(List<T> values, int page, int size) {
        int start = (int) Math.min((long) page * size, values.size());
        int end = Math.min(start + size, values.size());
        return new PageResult<>(values.subList(start, end), page, size, values.size(), (int) Math.ceil((double) values.size() / size));
    }
    private static BizException missing(String type) { return BizException.of(ErrorCode.NOT_FOUND, type + "不存在"); }
}
