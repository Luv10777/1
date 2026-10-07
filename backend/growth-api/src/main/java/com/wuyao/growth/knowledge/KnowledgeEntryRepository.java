package com.wuyao.growth.knowledge;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, Long> {

    boolean existsByKnowledgeSetId(Long knowledgeSetId);

    Page<KnowledgeEntry> findByKnowledgeSetIdOrderByUpdatedAtDesc(Long knowledgeSetId, Pageable pageable);

    List<KnowledgeEntry> findByKnowledgeSetIdAndStatusOrderByIdAsc(Long knowledgeSetId, String status);

    List<KnowledgeEntry> findByStoreIdAndKnowledgeSetIdInAndScopeAndStatusOrderByIdAsc(
            Long storeId, List<Long> knowledgeSetIds, String scope, String status);

    List<KnowledgeEntry> findByStoreIdAndKnowledgeSetIdInAndScopeAndProductIdInAndStatusOrderByIdAsc(
            Long storeId, List<Long> knowledgeSetIds, String scope, List<Long> productIds, String status);
}
