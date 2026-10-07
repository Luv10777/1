package com.wuyao.growth.iam.repository;

import com.wuyao.growth.iam.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findByPhone(String phone);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findForUpdate(@Param("id") Long id);

    // users 没有行级安全，按商户取数必须显式带 tenantId。
    List<User> findAllByTenantIdAndStatusNotOrderByIdAsc(Long tenantId, String status);
}
