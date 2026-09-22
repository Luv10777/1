package com.wuyao.growth.iam.repository;

import com.wuyao.growth.iam.entity.SmsCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface SmsCodeRepository extends JpaRepository<SmsCode, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SmsCode> findFirstByPhoneAndPurposeOrderByIdDesc(String phone, String purpose);

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:phone, 0))", nativeQuery = true)
    int lockPhone(@Param("phone") String phone);

    long countByPhoneAndCreatedAtAfter(String phone, Instant after);
}
