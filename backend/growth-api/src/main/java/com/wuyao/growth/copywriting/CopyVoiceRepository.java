package com.wuyao.growth.copywriting;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CopyVoiceRepository extends JpaRepository<CopyVoiceProfile, Long> {
    Optional<CopyVoiceProfile> findByStoreId(Long storeId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from CopyVoiceProfile v where v.storeId = :storeId")
    Optional<CopyVoiceProfile> lockByStoreId(@Param("storeId") Long storeId);
}
