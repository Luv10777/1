package com.wuyao.nexus.service.impl;

import com.wuyao.nexus.dto.WorkRequest;
import com.wuyao.nexus.dto.WorkResponse;
import com.wuyao.nexus.entity.Work;
import com.wuyao.nexus.exception.BusinessException;
import com.wuyao.nexus.repository.WorkRepository;
import com.wuyao.nexus.service.WorkService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class WorkServiceImpl implements WorkService {

    private final WorkRepository workRepository;

    @Override
    public Page<WorkResponse> list(Long tenantId, String reviewStatus, String type, Pageable pageable) {
        Work.ReviewStatus status = reviewStatus != null ? Work.ReviewStatus.valueOf(reviewStatus) : null;
        Work.WorkType workType = type != null ? Work.WorkType.valueOf(type) : null;
        return workRepository.findByFilters(tenantId, status, workType, pageable)
                .map(this::toResponse);
    }

    @Override
    public WorkResponse get(Long id, Long tenantId) {
        Work work = workRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("作品不存在"));
        return toResponse(work);
    }

    @Override
    @Transactional
    public WorkResponse create(Long tenantId, Long userId, WorkRequest request) {
        Work work = new Work();
        work.setTenantId(tenantId);
        work.setMerchantId(request.getMerchantId());
        work.setStoreId(request.getStoreId());
        work.setCode("W" + System.currentTimeMillis());
        work.setTitle(request.getTitle());
        work.setType(Work.WorkType.valueOf(request.getType()));
        work.setCoverUrl(request.getCoverUrl());
        work.setPreviewUrl(request.getPreviewUrl());
        work.setContentUrl(request.getContentUrl());
        work.setContentText(request.getContentText());
        work.setWorkflowId(request.getWorkflowId());
        work.setWorkflowVersion(request.getWorkflowVersion());
        work.setModelAlias(request.getModelAlias());
        work.setPromptVersion(request.getPromptVersion());
        work.setGenerationParams(request.getGenerationParams());
        work.setQaResult(request.getQaResult());
        work.setCreatedBy(userId);
        work.setReviewStatus(Work.ReviewStatus.DRAFT);

        work = workRepository.save(work);
        return toResponse(work);
    }

    @Override
    @Transactional
    public WorkResponse update(Long id, Long tenantId, WorkRequest request) {
        Work work = workRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("作品不存在"));

        work.setTitle(request.getTitle());
        work.setCoverUrl(request.getCoverUrl());
        work.setPreviewUrl(request.getPreviewUrl());
        work.setContentUrl(request.getContentUrl());
        work.setContentText(request.getContentText());
        work.setVersion(work.getVersion() + 1);

        work = workRepository.save(work);
        return toResponse(work);
    }

    @Override
    @Transactional
    public void delete(Long id, Long tenantId) {
        Work work = workRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("作品不存在"));

        work.setDeletedAt(LocalDateTime.now());
        workRepository.save(work);
    }

    @Override
    @Transactional
    public void submitReview(Long id, Long tenantId, Long userId) {
        Work work = workRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("作品不存在"));

        if (work.getReviewStatus() != Work.ReviewStatus.DRAFT) {
            throw new BusinessException("作品状态不允许提交审核");
        }

        work.setReviewStatus(Work.ReviewStatus.PENDING);
        workRepository.save(work);
    }

    @Override
    @Transactional
    public void approve(Long id, Long tenantId, Long userId, String notes) {
        Work work = workRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("作品不存在"));

        if (work.getReviewStatus() != Work.ReviewStatus.PENDING) {
            throw new BusinessException("作品状态不允许审核");
        }

        work.setReviewStatus(Work.ReviewStatus.APPROVED);
        work.setReviewNotes(notes);
        work.setReviewedBy(userId);
        work.setReviewedAt(LocalDateTime.now());
        workRepository.save(work);
    }

    @Override
    @Transactional
    public void reject(Long id, Long tenantId, Long userId, String notes) {
        Work work = workRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("作品不存在"));

        if (work.getReviewStatus() != Work.ReviewStatus.PENDING) {
            throw new BusinessException("作品状态不允许审核");
        }

        work.setReviewStatus(Work.ReviewStatus.REJECTED);
        work.setReviewNotes(notes);
        work.setReviewedBy(userId);
        work.setReviewedAt(LocalDateTime.now());
        workRepository.save(work);
    }

    @Override
    @Transactional
    public void publish(Long id, Long tenantId, String[] platforms) {
        Work work = workRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("作品不存在"));

        if (work.getReviewStatus() != Work.ReviewStatus.APPROVED) {
            throw new BusinessException("作品未审核通过，无法发布");
        }

        work.setReviewStatus(Work.ReviewStatus.PUBLISHED);
        work.setPublishedAt(LocalDateTime.now());
        // TODO: 实际发布到各平台的逻辑
        workRepository.save(work);
    }

    private WorkResponse toResponse(Work work) {
        WorkResponse response = new WorkResponse();
        BeanUtils.copyProperties(work, response);
        response.setType(work.getType().name());
        response.setReviewStatus(work.getReviewStatus().name());
        return response;
    }
}
