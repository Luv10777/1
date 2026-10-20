package com.wuyao.growth.alliance;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 供券商户的收款账户，一个商户一个。银行账号只存密文和后 4 位。 */
@Entity
@Table(name = "alliance_settlement_accounts")
@Getter
@Setter
public class AllianceSettlementAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tenantId;
    private String accountType;
    private String accountName;
    private String bankName;
    private String branchName;
    private byte[] accountNumberCiphertext;
    private String accountNumberLast4;
    private String financeContactName;
    private String financeContactPhone;
    private String status = "PENDING_VERIFY";
    private String reviewNote;
    private Long reviewedBy;
    private Instant reviewedAt;
    private Long updatedBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    @Version
    private Long version;
}
