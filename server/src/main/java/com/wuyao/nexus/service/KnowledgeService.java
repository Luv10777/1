package com.wuyao.nexus.service;

import com.wuyao.nexus.dto.KnowledgeRequest;
import com.wuyao.nexus.dto.KnowledgeResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface KnowledgeService {
    Page<KnowledgeResponse> list(Long tenantId, Pageable pageable);
    KnowledgeResponse get(Long id, Long tenantId);
    KnowledgeResponse create(Long tenantId, Long userId, KnowledgeRequest request);
    KnowledgeResponse update(Long id, Long tenantId, KnowledgeRequest request);
    void delete(Long id, Long tenantId);
    void verify(Long id, Long tenantId, Long userId);
    void publish(Long id, Long tenantId);
}
