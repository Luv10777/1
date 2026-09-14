package com.wuyao.growth.iam.repository;

import com.wuyao.growth.iam.entity.SmsCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface SmsCodeRepository extends JpaRepository<SmsCode, Long> {

    @Query("""
           select c from SmsCode c
            where c.phone = :phone and c.purpose = :purpose and c.consumedAt is null
            order by c.id desc limit 1
           """)
    Optional<SmsCode> findLatestUnconsumed(@Param("phone") String phone, @Param("purpose") String purpose);

    long countByPhoneAndCreatedAtAfter(String phone, Instant after);
}
