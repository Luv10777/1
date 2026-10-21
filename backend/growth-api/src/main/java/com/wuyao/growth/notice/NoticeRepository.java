package com.wuyao.growth.notice;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

interface NoticeRepository extends JpaRepository<Notice, Long> {

    /** 已经有同一事件的通知就跳过，不抛错，免得把发通知的业务事务弄成只能回滚。 */
    @Modifying
    @Query(value = """
            INSERT INTO notices(tenant_id, user_id, category, title, body, link, dedupe_key)
            VALUES (:tenantId, :userId, :category, :title, :body, :link, :dedupeKey)
            ON CONFLICT (tenant_id, user_id, dedupe_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") Long tenantId, @Param("userId") Long userId, @Param("category") String category,
                       @Param("title") String title, @Param("body") String body, @Param("link") String link,
                       @Param("dedupeKey") String dedupeKey);

    Page<Notice> findAllByTenantIdAndUserIdOrderByCreatedAtDescIdDesc(Long tenantId, Long userId, Pageable pageable);

    long countByTenantIdAndUserIdAndReadAtIsNull(Long tenantId, Long userId);

    @Modifying
    @Query("update Notice n set n.readAt = :now where n.tenantId = :tenantId and n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("tenantId") Long tenantId, @Param("userId") Long userId, @Param("now") Instant now);
}
