package com.wuyao.growth.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {
    List<Store> findAllByStatusOrderByIdAsc(String status);
    Optional<Store> findByIdAndStatus(Long id, String status);
    long countByBrandIdAndStatus(Long brandId, String status);

    @Query("select s.brandId, count(s) from Store s where s.status = 'ACTIVE' and s.brandId is not null group by s.brandId")
    List<Object[]> countActiveByBrand();

    @Modifying(flushAutomatically = true)
    @Query("update Store s set s.brandId = :brandId where s.brandId is null and s.status = 'ACTIVE'")
    int assignBrandWhereMissing(@Param("brandId") Long brandId);
}
