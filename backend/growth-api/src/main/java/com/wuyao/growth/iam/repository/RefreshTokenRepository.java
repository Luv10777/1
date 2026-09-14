package com.wuyao.growth.iam.repository;

import com.wuyao.growth.iam.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHashAndStatus(String tokenHash, String status);

    @Modifying
    @Query("update RefreshToken t set t.status = 'REVOKED' where t.userId = :userId and t.status = 'ACTIVE'")
    int revokeAllForUser(Long userId);
}
