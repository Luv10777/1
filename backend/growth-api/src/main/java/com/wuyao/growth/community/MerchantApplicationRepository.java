package com.wuyao.growth.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MerchantApplicationRepository extends JpaRepository<MerchantApplication, Long> {
    List<MerchantApplication> findAllByOrderByCreatedAtDescIdDesc();
}
