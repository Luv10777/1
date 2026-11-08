package com.wuyao.growth.creative.inspiration;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ImageInspirationRepository extends JpaRepository<ImageInspiration, Long> {
    @Query("select p.taskId from ImageInspiration p where p.billingReservationId in :reservations and p.status<>'SUCCEEDED' and p.taskId is not null")
    List<Long> billingRecoveryTaskIds(@Param("reservations") List<String> reservations);
    Optional<ImageInspiration> findByRequestKey(String requestKey);
    Page<ImageInspiration> findByStatusOrderByIdDesc(String status, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ImageInspiration p where p.id = :id")
    Optional<ImageInspiration> lock(@Param("id") Long id);
}
