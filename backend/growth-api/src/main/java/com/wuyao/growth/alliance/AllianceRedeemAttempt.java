package com.wuyao.growth.alliance;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 店员每一次核销尝试，用于连续输错后暂停核销和事后追查。 */
@Entity
@Table(name = "alliance_redeem_attempts")
@Getter
@Setter
public class AllianceRedeemAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long userId;
    private boolean succeeded;
    private Instant createdAt = Instant.now();
}
