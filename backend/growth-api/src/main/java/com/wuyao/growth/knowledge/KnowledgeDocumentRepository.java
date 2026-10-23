package com.wuyao.growth.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findTop50ByStoreIdOrderByCreatedAtDesc(Long storeId);

    List<KnowledgeDocument> findTop50ByBrandIdOrderByCreatedAtDesc(Long brandId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from KnowledgeDocument d where d.id = :id")
    Optional<KnowledgeDocument> findForUpdate(@Param("id") Long id);
}
