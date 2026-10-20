package com.wuyao.growth.alliance;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.community.PlatformAuditLog;
import com.wuyao.growth.iam.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 月度对账单，都在供券商户的租户里执行：生成、确认、打款只动一家商户，一个事务内完成。
 * 只收截至月底申诉期已满、没冻结的结算单，所以进了对账单的钱不会再被申诉撤回。
 */
@Service
@RequiredArgsConstructor
public class AllianceStatementService {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final AllianceStatementRepository statements;
    private final AllianceSettlementRepository settlements;
    private final AllianceAccountService accounts;
    private final AllianceProperties config;
    private final AccountService users;
    private final PlatformAuditLog audit;

    /**
     * 给当前商户生成某个月的对账单。这个月已有有效对账单、或没有符合条件的结算单时不生成。
     * 只能生成已经结束的月份。并发重复生成时由（商户, 月份）唯一索引兜底，后到的会失败回滚。
     */
    @Transactional
    public Optional<AllianceDtos.StatementView> generate(YearMonth period) {
        if (!period.isBefore(YearMonth.now(BUSINESS_ZONE))) throw BizException.of(ErrorCode.BAD_REQUEST, "只能生成已经结束的月份");
        Long tenantId = TenantContext.require();
        String key = period.toString();
        if (statements.existsByTenantIdAndPeriodAndStatusNot(tenantId, key, "VOID")) return Optional.empty();
        Instant cutoff = period.plusMonths(1).atDay(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        List<AllianceSettlement> items = settlements.eligibleForStatement(tenantId, cutoff);
        if (items.isEmpty()) return Optional.empty();

        var statement = new AllianceStatement();
        statement.setTenantId(tenantId);
        statement.setPeriod(key);
        statement.setItemCount(items.size());
        statement.setFaceTotalMinor(items.stream().mapToLong(AllianceSettlement::getFaceValueMinor).sum());
        statement.setSettleTotalMinor(items.stream().mapToLong(AllianceSettlement::getSettleAmountMinor).sum());
        statement.setConfirmDueAt(Instant.now().plus(Duration.ofDays(config.getStatementConfirmDays())));
        statements.saveAndFlush(statement);
        Instant now = Instant.now();
        items.forEach(s -> {
            s.setStatementId(statement.getId());
            s.setUpdatedAt(now);
        });
        return Optional.of(AllianceDtos.StatementView.of(statement));
    }

    @Transactional(readOnly = true)
    public List<AllianceDtos.StatementView> mine() {
        return statements.findAllByTenantIdOrderByPeriodDescIdDesc(TenantContext.require()).stream()
                .map(AllianceDtos.StatementView::of).toList();
    }

    @Transactional(readOnly = true)
    public AllianceDtos.StatementDetail detail(Long id) {
        var statement = mineOrMissing(id);
        var lines = settlements.findAllByStatementIdOrderByCreatedAtAscIdAsc(statement.getId()).stream()
                .map(s -> new AllianceDtos.StatementLine(s.getId(), s.getProductName(), s.getFaceValueMinor(),
                        s.getSettleAmountMinor(), s.getCreatedAt()))
                .toList();
        return new AllianceDtos.StatementDetail(AllianceDtos.StatementView.of(statement), lines);
    }

    /** 商户确认：收款账户必须已核对，确认时把账户抄到对账单上。 */
    @Transactional
    public AllianceDtos.StatementView confirm(Long id, Long userId) {
        users.requireOwner(userId);
        var statement = lockMine(id);
        if (!"PENDING_CONFIRM".equals(statement.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "这张对账单已经处理过了");
        markConfirmed(statement, userId, false);
        return AllianceDtos.StatementView.of(statement);
    }

    @Transactional
    public AllianceDtos.StatementView object(Long id, String reason, Long userId) {
        users.requireOwner(userId);
        var statement = lockMine(id);
        if (!"PENDING_CONFIRM".equals(statement.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "只有待确认的对账单可以提出异议");
        statement.setStatus("OBJECTED");
        statement.setObjectionNote(reason.trim());
        statement.setObjectedAt(Instant.now());
        statement.setUpdatedAt(Instant.now());
        return AllianceDtos.StatementView.of(statement);
    }

    /**
     * 到期没处理的对账单自动确认。收款账户还没核对的跳过，等账户核对后的下一轮。
     * @return 这次自动确认的张数
     */
    @Transactional
    public int autoConfirmDue() {
        Long tenantId = TenantContext.require();
        var ids = statements.dueForAutoConfirm(tenantId, Instant.now());
        if (ids.isEmpty() || !"VERIFIED".equals(accounts.status().orElse(null))) return 0;
        int confirmed = 0;
        for (Long id : ids) {
            var statement = lockMine(id);
            if (!"PENDING_CONFIRM".equals(statement.getStatus())) continue;
            markConfirmed(statement, null, true);
            confirmed++;
        }
        return confirmed;
    }

    // ---------- 平台运营（调用方已切到供券商户的租户并校验运营身份） ----------

    @Transactional(readOnly = true)
    public List<AllianceStatement> all() {
        return statements.findAllByTenantIdOrderByPeriodDescIdDesc(TenantContext.require());
    }

    /** 异议成立：作废，里面的结算单放回，之后重新生成。 */
    @Transactional
    public AllianceDtos.StatementView voidStatement(Long id, String note, Long operatorId) {
        var statement = lockMine(id);
        if (!"OBJECTED".equals(statement.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "只有有异议的对账单可以作废");
        Instant now = Instant.now();
        settlements.findAllByStatementIdOrderByCreatedAtAscIdAsc(statement.getId()).forEach(s -> {
            s.setStatementId(null);
            s.setUpdatedAt(now);
        });
        statement.setStatus("VOID");
        statement.setPlatformNote(note.trim());
        statement.setUpdatedAt(now);
        return AllianceDtos.StatementView.of(statement);
    }

    /** 异议核实无误：说明原因后转为已确认（同样要求收款账户已核对）。 */
    @Transactional
    public AllianceDtos.StatementView resolve(Long id, String note, Long operatorId) {
        var statement = lockMine(id);
        if (!"OBJECTED".equals(statement.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "只有有异议的对账单可以核实处理");
        statement.setPlatformNote(note.trim());
        markConfirmed(statement, operatorId, false);
        return AllianceDtos.StatementView.of(statement);
    }

    /** 线下打款后标记：对账单和里面每一条结算单一起变成已结算。 */
    @Transactional
    public AllianceDtos.StatementView markPaid(Long id, String reference, LocalDate paidDate, Long operatorId) {
        var statement = lockMine(id);
        if (!"CONFIRMED".equals(statement.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "只有已确认的对账单可以标记打款");
        if (paidDate.isAfter(LocalDate.now(BUSINESS_ZONE))) throw BizException.of(ErrorCode.BAD_REQUEST, "打款日期不能晚于今天");
        Instant now = Instant.now();
        settlements.findAllByStatementIdOrderByCreatedAtAscIdAsc(statement.getId()).forEach(s -> {
            s.setStatus("PAID");
            s.setPaidReference(reference.trim());
            s.setPaidBy(operatorId);
            s.setPaidAt(now);
            s.setUpdatedAt(now);
        });
        statement.setStatus("PAID");
        statement.setPaidReference(reference.trim());
        statement.setPaidDate(paidDate);
        statement.setPaidBy(operatorId);
        statement.setPaidAt(now);
        statement.setUpdatedAt(now);
        return AllianceDtos.StatementView.of(statement);
    }

    /** 平台查看对账单上抄下的完整收款账号（打款时用），写明用途并留审计。 */
    @Transactional
    public AllianceDtos.RevealView revealPayee(Long id, String purpose, Long operatorId) {
        var statement = mineOrMissing(id);
        if (statement.getPayeeAccountCiphertext() == null) throw BizException.of(ErrorCode.CONFLICT, "这张对账单还没确认，没有收款账户");
        audit.record(operatorId, AllianceAccountService.AUDIT_TARGET, statement.getId(), "REVEAL_STATEMENT", purpose);
        return new AllianceDtos.RevealView(statement.getPayeeAccountName(), statement.getPayeeBankName(),
                statement.getPayeeBranchName(), accounts.decrypt(statement.getPayeeAccountCiphertext()));
    }

    private void markConfirmed(AllianceStatement statement, Long confirmedBy, boolean automatic) {
        var account = accounts.requireVerified();
        Instant now = Instant.now();
        statement.setStatus("CONFIRMED");
        statement.setConfirmedBy(confirmedBy);
        statement.setConfirmedAt(now);
        statement.setAutoConfirmed(automatic);
        statement.setPayeeAccountType(account.getAccountType());
        statement.setPayeeAccountName(account.getAccountName());
        statement.setPayeeBankName(account.getBankName());
        statement.setPayeeBranchName(account.getBranchName());
        statement.setPayeeAccountCiphertext(account.getAccountNumberCiphertext());
        statement.setPayeeAccountLast4(account.getAccountNumberLast4());
        statement.setUpdatedAt(now);
    }

    private AllianceStatement mineOrMissing(Long id) {
        return statements.findById(id).filter(s -> TenantContext.require().equals(s.getTenantId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "对账单不存在"));
    }

    private AllianceStatement lockMine(Long id) {
        return statements.findForUpdate(id).filter(s -> TenantContext.require().equals(s.getTenantId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "对账单不存在"));
    }
}
