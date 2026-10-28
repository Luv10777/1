package com.wuyao.growth.copywriting;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CopyJournalRepository extends JpaRepository<CopyJournalEntry, Long> {
    Optional<CopyJournalEntry> findByBatchIdAndVersionIndex(Long batchId, int versionIndex);
    List<CopyJournalEntry> findByBatchId(Long batchId);
    Page<CopyJournalEntry> findByStoreIdOrderByIdDesc(Long storeId, Pageable pageable);
    @Query("select distinct e.batchId from CopyJournalEntry e where e.batchId in :batchIds")
    List<Long> finalizedBatchIds(@Param("batchIds") Collection<Long> batchIds);
}
