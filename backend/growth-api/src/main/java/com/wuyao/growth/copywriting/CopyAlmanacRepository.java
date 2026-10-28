package com.wuyao.growth.copywriting;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface CopyAlmanacRepository extends JpaRepository<CopyAlmanac, Long> {
    Optional<CopyAlmanac> findByStoreIdAndDayAndIndustry(Long storeId, LocalDate day, String industry);

    /** 同一家店同一天同时打开多次时只登记一份；返回 1 表示这次登记成功，由这次去提交生成任务。 */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO copy_almanacs (tenant_id, store_id, day, industry, status, facts)
            VALUES (:tenant, :store, :day, :industry, 'GENERATING', CAST(:facts AS jsonb))
            ON CONFLICT (tenant_id, store_id, day, industry) DO NOTHING""")
    int insertIfAbsent(@Param("tenant") Long tenantId, @Param("store") Long storeId, @Param("day") LocalDate day,
                       @Param("industry") String industry, @Param("facts") String facts);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CopyAlmanac a where a.id = :id")
    Optional<CopyAlmanac> lock(@Param("id") Long id);
}
