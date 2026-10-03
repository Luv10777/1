package com.wuyao.growth.iam.repository;

import com.wuyao.growth.iam.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    @Query("select t.id from Tenant t where t.id > :after order by t.id")
    List<Long> idsAfter(@Param("after") Long after, Pageable pageable);
}
