package com.wuyao.growth.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {
    List<Store> findAllByTenantIdAndStatusOrderByIdAsc(Long tenantId, String status);
    Optional<Store> findByIdAndTenantIdAndStatus(Long id, Long tenantId, String status);
    Optional<Store> findByIdAndStatus(Long id, String status);

    java.util.List<Store> findAllByIdInAndStatus(java.util.Collection<Long> ids, String status);
    long countByBrandIdAndStatus(Long brandId, String status);

    /** 已入驻商户名下营业中的门店：store_id, store_name, total。见 V66。 */
    @Query(value = "select store_id, store_name, total from public_alliance_stores(:limit, :offset)", nativeQuery = true)
    List<Object[]> findPublicAllianceStores(@Param("limit") int limit, @Param("offset") long offset);

    @Query("select s.brandId, count(s) from Store s where s.status = 'ACTIVE' and s.brandId is not null group by s.brandId")
    List<Object[]> countActiveByBrand();

    @Modifying(flushAutomatically = true)
    @Query("update Store s set s.brandId = :brandId where s.brandId is null and s.status = 'ACTIVE'")
    int assignBrandWhereMissing(@Param("brandId") Long brandId);
}
