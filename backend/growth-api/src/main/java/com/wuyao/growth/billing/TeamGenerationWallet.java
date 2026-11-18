package com.wuyao.growth.billing;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.SQLException;

@Repository
@RequiredArgsConstructor
class TeamGenerationWallet {
    private final EntityManager em;

    private record Team(long tenantId, long partnerId, String name) {}
    record AccountTeam(boolean teamAccount, Long partnerId) {}

    boolean isTeamAccount(long tenantId) {
        return Boolean.TRUE.equals(em.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM partners WHERE team_tenant_id = :tenant AND status <> 'DELETED')
                """).setParameter("tenant", tenantId).getSingleResult());
    }

    AccountTeam accountTeam(long tenantId, Long userId) {
        var query = em.createNativeQuery("""
                SELECT COUNT(*), CASE WHEN COUNT(*) = 1 THEN MIN(p.id)
                       ELSE MIN(p.id) FILTER (WHERE m.user_id IS NOT NULL) END
                FROM partners p LEFT JOIN partner_members m ON m.partner_id = p.id AND m.user_id = :user
                WHERE p.team_tenant_id = :tenant AND p.status <> 'DELETED'
                """).setParameter("tenant", tenantId).unwrap(org.hibernate.query.NativeQuery.class);
        query.setParameter("user", userId, Long.class);
        var row = (Object[]) query.getSingleResult();
        return new AccountTeam(((Number) row[0]).longValue() > 0, row[1] == null ? null : ((Number) row[1]).longValue());
    }

    BillingDtos.GenerationTeam summary(Long userId) {
        var team = findTeam(userId, false);
        return team == null ? null : new BillingDtos.GenerationTeam(team.name(), account(team.tenantId(), false).available());
    }

    void requireOriginalTeam(BillingReservation reservation, Long userId) {
        var team = requireOwnerTeam(userId);
        if (reservation.getPayerTenantId() != team.tenantId()
                || (reservation.getPayerPartnerId() != null && reservation.getPayerPartnerId() != team.partnerId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "当前账号无权继续使用原任务的团队余额");
        }
    }

    void setOwnerPayer(BillingReservation reservation) {
        var team = requireOwnerTeam(reservation.getCreatedBy());
        reservation.setPayerTenantId(team.tenantId());
        reservation.setPayerPartnerId(team.partnerId());
    }

    private Team requireOwnerTeam(Long userId) {
        var team = findTeam(userId, true);
        if (team == null) {
            throw BizException.of(ErrorCode.FORBIDDEN, "当前账号没有可用的服务商团队余额");
        }
        return team;
    }

    private Team findTeam(Long userId, boolean lock) {
        if (userId == null) return null;
        var rows = em.createNativeQuery("""
                SELECT p.team_tenant_id, p.id, p.name
                FROM partners p JOIN partner_members m ON m.partner_id = p.id
                WHERE m.user_id = :user AND m.role = 'OWNER' AND p.status = 'ACTIVE'
                  AND p.team_tenant_id <> :current
                """ + (lock ? " FOR SHARE OF p, m" : ""))
                .setParameter("user", userId).setParameter("current", TenantContext.require()).getResultList();
        if (rows.isEmpty()) return null;
        var row = (Object[]) rows.getFirst();
        return new Team(((Number) row[0]).longValue(), ((Number) row[1]).longValue(), (String) row[2]);
    }

    BillingAccount account(long tenantId, boolean lock) {
        return inWallet(tenantId, connection -> {
            var account = new BillingAccount();
            account.setTenantId(tenantId);
            try (var select = connection.prepareStatement("""
                    SELECT gift_available, purchased_available, held FROM billing_accounts WHERE tenant_id = ?
                    """ + (lock ? " FOR UPDATE" : ""))) {
                select.setLong(1, tenantId);
                try (var row = select.executeQuery()) {
                    if (row.next()) {
                        account.setGiftAvailable(row.getLong(1));
                        account.setPurchasedAvailable(row.getLong(2));
                        account.setHeld(row.getLong(3));
                    }
                }
            }
            return account;
        });
    }

    boolean eventExists(BillingReservation reservation, String key) {
        return inWallet(reservation.getPayerTenantId(), connection -> {
            try (var query = connection.prepareStatement("SELECT 1 FROM billing_ledger WHERE tenant_id = ? AND event_key = ?")) {
                query.setLong(1, reservation.getPayerTenantId());
                query.setString(2, eventKey(reservation, key));
                try (var row = query.executeQuery()) { return row.next(); }
            }
        });
    }

    void post(BillingAccount account, BillingReservation reservation, String key, String type,
              String description, long gift, long purchased, long held) {
        // The reservation belongs to the creative workspace. Its foreign key is visible once flushed.
        em.flush();
        inWallet(account.getTenantId(), connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE billing_accounts SET gift_available = ?, purchased_available = ?, held = ?, updated_at = now()
                    WHERE tenant_id = ?
                    """)) {
                update.setLong(1, account.getGiftAvailable());
                update.setLong(2, account.getPurchasedAvailable());
                update.setLong(3, account.getHeld());
                update.setLong(4, account.getTenantId());
                if (update.executeUpdate() != 1) throw new SQLException("Missing team billing account");
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO billing_ledger (tenant_id, event_key, type, description, gift_delta, purchased_delta,
                      held_delta, available_after, held_after, reservation_id, actor_user_id, generation_capability, payer_partner_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setLong(1, account.getTenantId());
                insert.setString(2, eventKey(reservation, key));
                insert.setString(3, type);
                insert.setString(4, description);
                insert.setLong(5, gift);
                insert.setLong(6, purchased);
                insert.setLong(7, held);
                insert.setLong(8, account.available());
                insert.setLong(9, account.getHeld());
                insert.setString(10, reservation.getId());
                insert.setObject(11, reservation.getCreatedBy());
                insert.setString(12, reservation.getCapability());
                insert.setObject(13, reservation.getPayerPartnerId());
                insert.executeUpdate();
            }
            return null;
        });
    }

    private static String eventKey(BillingReservation reservation, String key) {
        return "generation:" + reservation.getTenantId() + ":" + key;
    }

    // Only billing JDBC runs under the payer's RLS scope. Both wallets and the creative task share one transaction.
    private <T> T inWallet(long payer, ReturningWork<T> work) {
        long workspace = TenantContext.require();
        return em.unwrap(Session.class).doReturningWork(connection -> {
            setTenant(connection, payer);
            T result;
            try {
                result = work.execute(connection);
            } catch (SQLException | RuntimeException failure) {
                try { setTenant(connection, workspace); }
                catch (SQLException reset) { failure.addSuppressed(reset); }
                throw failure;
            }
            setTenant(connection, workspace);
            return result;
        });
    }

    private static void setTenant(Connection connection, long tenant) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, Long.toString(tenant));
            statement.execute();
        }
    }
}
