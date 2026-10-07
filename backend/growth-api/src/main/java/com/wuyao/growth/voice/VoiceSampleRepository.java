package com.wuyao.growth.voice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface VoiceSampleRepository extends JpaRepository<VoiceSample, Long> {
    /** 开放给这家门店、尚未删除的样本。 */
    @Query("""
            select v from VoiceSample v
             where v.status <> 'DELETED'
               and v.id in (select g.sampleId from VoiceSampleStore g where g.storeId = :storeId)
             order by v.id desc
            """)
    List<VoiceSample> findOpenTo(Long storeId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VoiceSample v where v.id = :id")
    Optional<VoiceSample> lockById(Long id);
}
