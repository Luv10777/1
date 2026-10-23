package com.wuyao.growth.video.plan;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VideoPlanRepository extends JpaRepository<VideoPlan, Long> {
    @Query("select p.taskId from VideoPlan p where p.billingReservationId in :reservations and p.status<>'SUCCEEDED' and p.taskId is not null")
    List<Long> billingRecoveryTaskIds(@Param("reservations") List<String> reservations);
    Optional<VideoPlan> findByRequestKey(String requestKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from VideoPlan p where p.id = :id")
    Optional<VideoPlan> lock(@Param("id") Long id);
}
