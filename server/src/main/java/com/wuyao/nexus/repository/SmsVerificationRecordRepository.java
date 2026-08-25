package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.SmsVerificationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SmsVerificationRecordRepository extends JpaRepository<SmsVerificationRecord, Long> {
    Optional<SmsVerificationRecord> findTopByPhoneAndStatusOrderByCreatedAtDesc(
            String phone, SmsVerificationRecord.Status status);

    long countByPhoneAndCreatedAtAfter(String phone, LocalDateTime after);

    long countByIpAddressAndCreatedAtAfter(String ipAddress, LocalDateTime after);
}
