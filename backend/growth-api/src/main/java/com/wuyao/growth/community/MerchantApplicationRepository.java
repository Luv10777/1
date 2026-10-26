package com.wuyao.growth.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MerchantApplicationRepository extends JpaRepository<MerchantApplication, Long> {
    List<MerchantApplication> findAllByOrderByCreatedAtDescIdDesc();

    List<MerchantApplication> findAllByTenantIdOrderByCreatedAtDescIdDesc(Long tenantId);

    /** 入驻审核通过的商户：application_id, tenant_id, merchant_name, total。见 V67。 */
    @org.springframework.data.jpa.repository.Query(value = "select application_id, tenant_id, merchant_name, total from public_alliance_merchants(:limit, :offset)", nativeQuery = true)
    List<Object[]> findPublicAllianceMerchants(@org.springframework.data.repository.query.Param("limit") int limit,
                                               @org.springframework.data.repository.query.Param("offset") long offset);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<MerchantApplication> findByIdAndTenantId(Long id, Long tenantId);
}
