package com.wuyao.nexus.service.impl;

import com.wuyao.nexus.dto.KnowledgeRequest;
import com.wuyao.nexus.dto.KnowledgeResponse;
import com.wuyao.nexus.entity.Knowledge;
import com.wuyao.nexus.exception.BusinessException;
import com.wuyao.nexus.repository.KnowledgeRepository;
import com.wuyao.nexus.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {

    private final KnowledgeRepository knowledgeRepository;

    @Override
    public Page<KnowledgeResponse> list(Long tenantId, Pageable pageable) {
        return knowledgeRepository.findByTenantIdAndDeletedAtIsNull(tenantId, pageable)
                .map(this::toResponse);
    }

    @Override
    public KnowledgeResponse get(Long id, Long tenantId) {
        Knowledge knowledge = knowledgeRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("知识不存在"));
        return toResponse(knowledge);
    }

    @Override
    @Transactional
    public KnowledgeResponse create(Long tenantId, Long userId, KnowledgeRequest request) {
        Knowledge knowledge = new Knowledge();
        knowledge.setTenantId(tenantId);
        knowledge.setMerchantId(request.getMerchantId());
        knowledge.setStoreId(request.getStoreId());
        knowledge.setCode("K" + System.currentTimeMillis());
        knowledge.setTitle(request.getTitle());
        knowledge.setType(Knowledge.KnowledgeType.valueOf(request.getType()));
        knowledge.setContent(request.getContent());
        knowledge.setFileUrl(request.getFileUrl());
        knowledge.setSourceUrl(request.getSourceUrl());
        knowledge.setStructuredData(request.getStructuredData());
        knowledge.setMetadata(request.getMetadata());
        knowledge.setCreatedBy(userId);
        knowledge.setStatus(Knowledge.KnowledgeStatus.DRAFT);

        knowledge = knowledgeRepository.save(knowledge);
        return toResponse(knowledge);
    }

    @Override
    @Transactional
    public KnowledgeResponse update(Long id, Long tenantId, KnowledgeRequest request) {
        Knowledge knowledge = knowledgeRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("知识不存在"));

        knowledge.setTitle(request.getTitle());
        knowledge.setContent(request.getContent());
        knowledge.setStructuredData(request.getStructuredData());
        knowledge.setMetadata(request.getMetadata());

        knowledge = knowledgeRepository.save(knowledge);
        return toResponse(knowledge);
    }

    @Override
    @Transactional
    public void delete(Long id, Long tenantId) {
        Knowledge knowledge = knowledgeRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("知识不存在"));

        knowledge.setDeletedAt(LocalDateTime.now());
        knowledge.setStatus(Knowledge.KnowledgeStatus.DELETED);
        knowledgeRepository.save(knowledge);
    }

    @Override
    @Transactional
    public void verify(Long id, Long tenantId, Long userId) {
        Knowledge knowledge = knowledgeRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("知识不存在"));

        knowledge.setVerified(true);
        knowledge.setVerifiedBy(userId);
        knowledge.setVerifiedAt(LocalDateTime.now());
        knowledgeRepository.save(knowledge);
    }

    @Override
    @Transactional
    public void publish(Long id, Long tenantId) {
        Knowledge knowledge = knowledgeRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new BusinessException("知识不存在"));

        if (!knowledge.getVerified()) {
            throw new BusinessException("知识未经验证，无法发布");
        }

        knowledge.setStatus(Knowledge.KnowledgeStatus.PUBLISHED);
        knowledgeRepository.save(knowledge);
    }

    private KnowledgeResponse toResponse(Knowledge knowledge) {
        KnowledgeResponse response = new KnowledgeResponse();
        BeanUtils.copyProperties(knowledge, response);
        response.setType(knowledge.getType().name());
        response.setParseStatus(knowledge.getParseStatus().name());
        response.setOcrStatus(knowledge.getOcrStatus().name());
        response.setVectorStatus(knowledge.getVectorStatus().name());
        response.setStatus(knowledge.getStatus().name());
        return response;
    }
}
