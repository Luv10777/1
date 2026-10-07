package com.wuyao.growth.voice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface VoiceSampleRepository extends JpaRepository<VoiceSample, Long> {
    List<VoiceSample> findByStoreIdAndStatusNotOrderByIdDesc(Long storeId, String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VoiceSample v where v.id = :id")
    Optional<VoiceSample> lockById(Long id);
}
