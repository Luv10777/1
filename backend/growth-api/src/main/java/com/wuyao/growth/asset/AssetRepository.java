package com.wuyao.growth.asset;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

/**
 * 注意这里没有一个方法带 tenantId 参数。
 * 租户过滤由数据库的行级安全策略完成——查不到别家的数据，不是因为代码记得过滤，
 * 而是因为数据库根本不返回。这是 common/tenant 那套的意义所在。
 */
public interface AssetRepository extends JpaRepository<Asset, Long> {

    Page<Asset> findAllByOrderByIdDesc(Pageable pageable);

    Optional<Asset> findByStorageKey(String storageKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Asset a where a.id = :id")
    Optional<Asset> findForUpdate(@Param("id") Long id);
}
