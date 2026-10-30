package com.wuyao.growth.referral;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** referral_rates 是平台级配置，不分商户。 */
public interface ReferralRateRepository extends JpaRepository<ReferralRate, Long> {
    Optional<ReferralRate> findTopByOrderByIdDesc();

    List<ReferralRate> findTop10ByOrderByIdDesc();
}
