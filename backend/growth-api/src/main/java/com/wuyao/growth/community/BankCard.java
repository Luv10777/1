package com.wuyao.growth.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 用户本人的收款银行卡，四要素核验一致才保存。一人一张，换卡直接覆盖。 */
@Entity
@Table(name = "bank_cards")
@Getter
@Setter
public class BankCard {
    @Id
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "bank_name", nullable = false, length = 40)
    private String bankName;
    @Column(name = "card_number_ciphertext", nullable = false, columnDefinition = "bytea")
    private byte[] cardNumberCiphertext;
    @Column(name = "card_last4", nullable = false, length = 4)
    private String cardLast4;
    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
