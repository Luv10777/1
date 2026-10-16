package com.wuyao.growth.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.time.Instant;
import java.util.Optional;

public interface InformationPublicationRepository extends JpaRepository<InformationPublication, Long> {
    List<InformationPublication> findAllByTenantIdOrderByCreatedAtDescIdDesc(Long tenantId);

    List<InformationPublication> findAllByTenantIdOrderByPublishedAtDescIdDesc(Long tenantId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<InformationPublication> findByIdAndTenantId(Long id, Long tenantId);

    @Query("select i from InformationPublication i where i.status = 'PUBLISHED' " +
            "and (i.validUntil is null or i.validUntil >= :today) " +
            "and (cast(:category as string) is null or i.category = cast(:category as string)) " +
            "and (cast(:keyword as string) is null or lower(i.title) like lower(concat('%', cast(:keyword as string), '%')) " +
            "or lower(i.summary) like lower(concat('%', cast(:keyword as string), '%')) " +
            "or lower(i.merchantName) like lower(concat('%', cast(:keyword as string), '%'))) " +
            "order by i.publishedAt desc, i.id desc")
    Page<InformationPublication> publicFeed(@Param("today") LocalDate today,
                                             @Param("category") String category,
                                             @Param("keyword") String keyword,
                                             Pageable pageable);

    @Query("select i from InformationPublication i where i.id = :id and i.status = 'PUBLISHED' " +
            "and (i.validUntil is null or i.validUntil >= :today)")
    Optional<InformationPublication> findPublicById(@Param("id") Long id, @Param("today") LocalDate today);

    /** 商户关闭时下线它发布的全部信息。 */
    @Modifying(flushAutomatically = true)
    @Query("update InformationPublication i set i.status = 'OFFLINE', i.updatedAt = :now where i.tenantId = :tenantId and i.status = 'PUBLISHED'")
    int takeDownAll(@Param("tenantId") Long tenantId, @Param("now") Instant now);
}
