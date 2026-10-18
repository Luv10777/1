package com.wuyao.growth.community;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class CommunityService {
    private final MerchantApplicationRepository applications;
    private final InformationPublicationRepository publications;

    @Transactional
    public CommunityDtos.ApplicationView apply(CommunityDtos.ApplicationRequest request, Long userId) {
        var value = new MerchantApplication();
        value.setTenantId(TenantContext.require());
        value.setSubmittedBy(userId);
        value.setMerchantName(request.merchantName().trim());
        value.setCompanyName(request.companyName().trim());
        value.setIndustry(request.industry().trim());
        value.setRegion(request.region().trim());
        value.setCreditCode(request.creditCode().trim().toUpperCase());
        value.setContactName(request.contactName().trim());
        value.setContactPhone(request.contactPhone().trim());
        value.setIntroduction(request.introduction().trim());
        return CommunityDtos.ApplicationView.of(applications.saveAndFlush(value));
    }

    @Transactional(readOnly = true)
    public java.util.List<CommunityDtos.ApplicationView> applications() {
        return applications.findAllByOrderByCreatedAtDescIdDesc().stream().map(CommunityDtos.ApplicationView::of).toList();
    }

    @Transactional
    public CommunityDtos.InformationView publish(CommunityDtos.InformationRequest request, Long userId) {
        var value = new InformationPublication();
        value.setTenantId(TenantContext.require());
        value.setSubmittedBy(userId);
        apply(value, request);
        value.setStatus("PENDING_REVIEW");
        return CommunityDtos.InformationView.of(publications.saveAndFlush(value));
    }

    @Transactional
    public CommunityDtos.InformationView update(Long id, CommunityDtos.InformationRequest request) {
        var value = publications.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "发布内容不存在"));
        if (value.isPlatformBlocked()) throw BizException.of(ErrorCode.FORBIDDEN, "该信息已由平台下线，请联系平台处理");
        apply(value, request);
        value.setStatus("PENDING_REVIEW");
        value.setPublishedAt(null);
        value.setReviewNote(null);
        value.setUpdatedAt(Instant.now());
        return CommunityDtos.InformationView.of(publications.saveAndFlush(value));
    }

    @Transactional
    public void takeOffline(Long id) {
        var value = publications.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "发布内容不存在"));
        value.setStatus("OFFLINE");
        value.setUpdatedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public java.util.List<CommunityDtos.InformationView> publications() {
        return publications.findAllByTenantIdOrderByCreatedAtDescIdDesc(TenantContext.require()).stream()
                .map(CommunityDtos.InformationView::of).toList();
    }

    @Transactional(readOnly = true)
    public PageResult<CommunityDtos.InformationView> publicFeed(int page, int size, String category, String keyword) {
        checkPaging(page, size);
        String normalizedCategory = blankToNull(category);
        String normalizedKeyword = blankToNull(keyword);
        var result = publications.publicFeed(LocalDate.now(), normalizedCategory, normalizedKeyword, PageRequest.of(page, size));
        return PageResult.of(result, result.getContent().stream().map(CommunityDtos.InformationView::publicOf).toList());
    }

    @Transactional(readOnly = true)
    public CommunityDtos.InformationView publicInformation(Long id) {
        return publications.findPublicById(id, LocalDate.now())
                .map(CommunityDtos.InformationView::publicOf)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "信息不存在或已过期"));
    }

    private void apply(InformationPublication value, CommunityDtos.InformationRequest request) {
        if (request.validUntil() != null && request.validUntil().isBefore(LocalDate.now())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "有效期不能早于今天");
        }
        value.setCategory(request.category());
        value.setTitle(request.title().trim());
        value.setMerchantName(request.merchantName().trim());
        value.setRegion(request.region().trim());
        value.setSummary(blankToNull(request.summary()));
        value.setContent(request.content().trim());
        value.setContactName(blankToNull(request.contactName()));
        value.setContactPhone(blankToNull(request.contactPhone()));
        value.setValidUntil(request.validUntil());
    }

    private static void checkPaging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST, "page 必须非负，size 必须在 1 到 100 之间");
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
