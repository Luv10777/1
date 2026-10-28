package com.wuyao.growth.copywriting;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CopyBatchRepository extends JpaRepository<CopyBatch, Long> {
    @Query("select b.taskId from CopyBatch b where b.billingReservationId in :reservations and b.status<>'SUCCEEDED' and b.taskId is not null")
    List<Long> billingRecoveryTaskIds(@Param("reservations") List<String> reservations);
    Optional<CopyBatch> findByRequestKey(String requestKey);
    Page<CopyBatch> findByStoreIdOrderByIdDesc(Long storeId, Pageable pageable);
    Page<CopyBatch> findByStoreIdAndPlatformOrderByIdDesc(Long storeId, String platform, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from CopyBatch b where b.id = :id")
    Optional<CopyBatch> lock(@Param("id") Long id);
}
