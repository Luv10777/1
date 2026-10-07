package com.wuyao.growth.brand;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    List<Brand> findAllByStatusOrderByDefaultBrandDescIdAsc(String status);

    Optional<Brand> findByIdAndStatus(Long id, String status);

    Optional<Brand> findFirstByDefaultBrandTrue();

    long countByStatus(String status);

    boolean existsByNameAndStatus(String name, String status);

    boolean existsByNameAndStatusAndIdNot(String name, String status, Long id);

    /**
     * 同一商户的品牌写操作、以及门店挂品牌，都先拿这把事务级锁。
     * 这样"还没有默认品牌""名称没被占用""品牌下没有门店"这些先查后写的判断不会被并发请求打破。
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('brands:' || CAST(:tenantId AS text), 0))",
            nativeQuery = true)
    int lockTenant(@Param("tenantId") Long tenantId);
}
