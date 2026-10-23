package com.wuyao.growth.knowledge;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KnowledgeSuggestionRepository extends JpaRepository<KnowledgeSuggestion, Long> {

    List<KnowledgeSuggestion> findByDocumentIdOrderByIdAsc(Long documentId);

    @Query("select s.documentId, count(s) from KnowledgeSuggestion s "
            + "where s.documentId in :ids and s.status = 'PENDING' group by s.documentId")
    List<Object[]> countPending(@Param("ids") Collection<Long> documentIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from KnowledgeSuggestion s where s.id = :id")
    Optional<KnowledgeSuggestion> findForUpdate(@Param("id") Long id);

    /** Reading a document again replaces what was not yet handled; accepted and dismissed ones stay. */
    @Modifying
    @Query("delete from KnowledgeSuggestion s where s.documentId = :id and s.status = 'PENDING'")
    void deletePending(@Param("id") Long documentId);
}
