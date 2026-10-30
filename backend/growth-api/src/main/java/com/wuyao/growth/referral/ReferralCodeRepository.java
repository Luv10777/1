package com.wuyao.growth.referral;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReferralCodeRepository extends JpaRepository<ReferralCode, Long> {
    /** 按邀请码找人要跨商户，走 V71 的 SECURITY DEFINER 函数，只拿编号和账号是否有效。 */
    @Query(value = "SELECT user_id AS userId, tenant_id AS tenantId, active FROM referral_code_owner(:code)", nativeQuery = true)
    Optional<CodeOwner> findOwner(@Param("code") String code);

    interface CodeOwner {
        Long getUserId();
        Long getTenantId();
        Boolean getActive();
    }
}
