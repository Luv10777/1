package com.wuyao.growth.iam.repository;

import com.wuyao.growth.iam.entity.WechatAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WechatAccountRepository extends JpaRepository<WechatAccount, Long> {
    Optional<WechatAccount> findByAppIdAndOpenId(String appId, String openId);

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:identity, 0))", nativeQuery = true)
    int lockIdentity(@Param("identity") String identity);
}
