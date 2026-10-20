package com.wuyao.growth.alliance;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface AllianceOfferRepository extends JpaRepository<AllianceOffer, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from AllianceOffer o where o.id = :id")
    Optional<AllianceOffer> findForUpdate(@Param("id") Long id);

    List<AllianceOffer> findAllByTenantIdOrderByCreatedAtDescIdDesc(Long tenantId);

    List<AllianceOffer> findAllByTenantIdAndCreatedAtGreaterThanEqual(Long tenantId, Instant since);

    List<AllianceOffer> findAllByTenantIdAndStatusIn(Long tenantId, Collection<String> statuses);

    boolean existsByStoreIdAndStatusIn(Long storeId, Collection<String> statuses);

    boolean existsByProductIdAndStatusIn(Long productId, Collection<String> statuses);

    /**
     * 券库：别家商户已上架且有剩余的供券单。跨商户可见由 alliance_offer_pool 策略放行。
     * excludedCodes 不能为空集合，没有需要排除的主体时传一个不会命中的占位值。
     */
    // Optional filters need an explicit string type even when null; see ProductRepository.search.
    @Query("""
            select o from AllianceOffer o
            where o.status = 'ACTIVE' and o.claimedQty < o.totalQty and o.tenantId <> :tenantId
              and o.supplierCreditCode not in :excludedCodes
              and (cast(:keyword as string) is null
                   or lower(o.productName) like lower(concat('%', cast(:keyword as string), '%'))
                   or lower(o.storeName) like lower(concat('%', cast(:keyword as string), '%')))
              and (cast(:city as string) is null
                   or lower(o.storeAddress) like lower(concat('%', cast(:city as string), '%')))
            order by o.updatedAt desc, o.id desc
            """)
    Page<AllianceOffer> pool(@Param("tenantId") Long tenantId, @Param("excludedCodes") Collection<String> excludedCodes,
                             @Param("keyword") String keyword, @Param("city") String city, Pageable pageable);

    @Query("select o from AllianceOffer o where o.status = 'PENDING_REVIEW' order by o.createdAt, o.id")
    List<AllianceOffer> pendingReview();

    @Modifying(flushAutomatically = true)
    @Query("update AllianceOffer o set o.status = 'WITHDRAWN', o.updatedAt = :now where o.tenantId = :tenantId and o.status in ('PENDING_REVIEW', 'ACTIVE')")
    int withdrawAll(@Param("tenantId") Long tenantId, @Param("now") Instant now);
}
