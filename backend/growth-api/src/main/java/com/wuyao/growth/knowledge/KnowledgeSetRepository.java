package com.wuyao.growth.knowledge;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeSetRepository extends JpaRepository<KnowledgeSet, Long> {

    Page<KnowledgeSet> findByStoreIdOrderByUpdatedAtDesc(Long storeId, Pageable pageable);

    Page<KnowledgeSet> findByStoreIdAndKindOrderByUpdatedAtDesc(Long storeId, String kind, Pageable pageable);

    List<KnowledgeSet> findByStoreIdAndKindAndStatusOrderByIdAsc(Long storeId, String kind, String status);
}
