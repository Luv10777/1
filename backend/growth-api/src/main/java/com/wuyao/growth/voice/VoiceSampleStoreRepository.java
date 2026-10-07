package com.wuyao.growth.voice;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface VoiceSampleStoreRepository extends JpaRepository<VoiceSampleStore, Long> {
    List<VoiceSampleStore> findBySampleIdOrderByStoreIdAsc(Long sampleId);
    List<VoiceSampleStore> findBySampleIdInOrderByStoreIdAsc(Collection<Long> sampleIds);
    boolean existsBySampleIdAndStoreId(Long sampleId, Long storeId);
    List<VoiceSampleStore> findByStoreId(Long storeId);
}
