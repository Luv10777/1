package com.wuyao.growth.video.analysis;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface VideoAnalysisRepository extends JpaRepository<VideoAnalysis, Long> {
    Optional<VideoAnalysis> findByRequestKey(String requestKey);
    Page<VideoAnalysis> findAllByOrderByIdDesc(Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from VideoAnalysis a where a.id = :id")
    Optional<VideoAnalysis> lock(@Param("id") Long id);
}
