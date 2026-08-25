package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHashAndStatus(String tokenHash, RefreshToken.Status status);
    void deleteByUserIdAndDeviceId(Long userId, String deviceId);
}
