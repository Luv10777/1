package com.wuyao.growth.iam.repository;

import com.wuyao.growth.iam.entity.Tenant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    @Query("select t.id from Tenant t where t.id > :after order by t.id")
    List<Long> idsAfter(@Param("after") Long after, Pageable pageable);

    /** 关闭商户和往商户里加人都先锁这一行，免得刚关掉的商户里又冒出一个能登录的店员。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> findForUpdate(@Param("id") Long id);
}
