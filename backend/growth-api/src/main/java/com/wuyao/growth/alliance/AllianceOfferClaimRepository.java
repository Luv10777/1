package com.wuyao.growth.alliance;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AllianceOfferClaimRepository extends JpaRepository<AllianceOfferClaim, Long> {

    Optional<AllianceOfferClaim> findByVoucherRef(UUID voucherRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AllianceOfferClaim c where c.voucherRef = :ref")
    Optional<AllianceOfferClaim> findForUpdate(@Param("ref") UUID ref);

    Optional<AllianceOfferClaim> findByCodeHash(String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AllianceOfferClaim c where c.codeHash = :hash")
    Optional<AllianceOfferClaim> findByCodeHashForUpdate(@Param("hash") String hash);

    @Query("select c.voucherRef from AllianceOfferClaim c where c.status = 'CLAIMED' and c.expiresAt <= :now order by c.expiresAt")
    List<UUID> dueForExpiry(@Param("now") Instant now, Pageable pageable);

    long countByOfferIdAndStatus(Long offerId, String status);

    @Query("select count(c) from AllianceOfferClaim c where c.status = 'CLAIMED' and c.offerId in (select o.id from AllianceOffer o where o.storeId = :storeId and o.tenantId = c.tenantId)")
    long countOutstandingForStore(@Param("storeId") Long storeId);

    @Query("select count(c) from AllianceOfferClaim c where c.status = 'CLAIMED' and c.offerId in (select o.id from AllianceOffer o where o.productId = :productId and o.tenantId = c.tenantId)")
    long countOutstandingForProduct(@Param("productId") Long productId);

    long countByTenantIdAndStatus(Long tenantId, String status);

    List<AllianceOfferClaim> findAllByRedeemedStoreIdAndStatusOrderByRedeemedAtDesc(Long storeId, String status, Pageable pageable);
}
