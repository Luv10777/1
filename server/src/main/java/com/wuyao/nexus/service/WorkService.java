package com.wuyao.nexus.service;

import com.wuyao.nexus.dto.WorkRequest;
import com.wuyao.nexus.dto.WorkResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WorkService {
    Page<WorkResponse> list(Long tenantId, String reviewStatus, String type, Pageable pageable);
    WorkResponse get(Long id, Long tenantId);
    WorkResponse create(Long tenantId, Long userId, WorkRequest request);
    WorkResponse update(Long id, Long tenantId, WorkRequest request);
    void delete(Long id, Long tenantId);
    void submitReview(Long id, Long tenantId, Long userId);
    void approve(Long id, Long tenantId, Long userId, String notes);
    void reject(Long id, Long tenantId, Long userId, String notes);
    void publish(Long id, Long tenantId, String[] platforms);
}
