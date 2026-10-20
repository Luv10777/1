package com.wuyao.growth.alliance;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** 一个供券商户一个月的对账单。确认时抄下收款账户，之后按这份账户打款。 */
@Entity
@Table(name = "alliance_statements")
@Getter
@Setter
public class AllianceStatement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tenantId;
    private String period;
    private int itemCount;
    private long faceTotalMinor;
    private long settleTotalMinor;
    private String status = "PENDING_CONFIRM";
    private Instant confirmDueAt;
    private Long confirmedBy;
    private Instant confirmedAt;
    private boolean autoConfirmed;
    private String objectionNote;
    private Instant objectedAt;
    private String platformNote;
    private String payeeAccountType;
    private String payeeAccountName;
    private String payeeBankName;
    private String payeeBranchName;
    private byte[] payeeAccountCiphertext;
    private String payeeAccountLast4;
    private String paidReference;
    private LocalDate paidDate;
    private Long paidBy;
    private Instant paidAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
