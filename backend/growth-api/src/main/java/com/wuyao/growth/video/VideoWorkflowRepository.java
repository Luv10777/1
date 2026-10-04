package com.wuyao.growth.video;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface VideoWorkflowRepository extends JpaRepository<VideoWorkflow, Long> {
    Page<VideoWorkflow> findAllByOrderByIdDesc(Pageable pageable);

    Optional<VideoWorkflow> findByRequestKey(String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from VideoWorkflow w where w.id = :id")
    Optional<VideoWorkflow> lock(Long id);

    @Query(value = "select true from pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    boolean lockRequest(String key);
}
