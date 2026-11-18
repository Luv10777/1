package com.wuyao.growth.partner;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
class PartnerTeamActivity {
    private final PartnerService partners;
    private final EntityManager em;

    PartnerDtos.TeamActivityLog logs(long userId, int page, int size, String kind, Long memberNumber) {
        var access = partners.requirePartner(userId);
        PartnerService.checkPage(page, size);
        if (kind != null && !Set.of("CREATION", "DISTRIBUTION").contains(kind)) throw PartnerService.bad("请选择有效的操作类型");
        if (memberNumber != null && memberNumber < 1) throw PartnerService.bad("成员编号必须大于零");
        var partner = access.partner();
        if (partner.teamTenantId() == null) return new PartnerDtos.TeamActivityLog(new PartnerDtos.Page<>(List.of(), page, size, 0), 0, 0);
        return partners.inScope(partner.teamTenantId(), true, () -> read(partner.id(), partner.teamTenantId(),
                access.perms().finance() ? 0 : userId, page, size, kind, memberNumber));
    }

    private PartnerDtos.TeamActivityLog read(long partner, long tenant, long viewer, int page, int size, String kind, Long memberNumber) {
        List<?> rows = em.createNativeQuery("""
                WITH activity AS (
                    SELECT l.id, 'CREATION' AS kind, COALESCE(r.created_by, l.actor_user_id) AS actor,
                           -l.held_delta AS credits, COALESCE(r.capability, l.generation_capability) AS capability,
                           l.description, 'CAPTURED' AS status, l.created_at
                    FROM billing_ledger l
                    JOIN partners p ON p.id=:partner AND p.team_tenant_id=l.tenant_id
                    LEFT JOIN billing_reservations r ON r.id=l.reservation_id AND r.tenant_id=l.tenant_id
                    WHERE l.tenant_id=:tenant AND l.type='CAPTURE' AND l.held_delta < 0
                      AND (l.payer_partner_id=p.id OR (
                          l.payer_partner_id IS NULL
                          AND (r.payer_tenant_id=l.tenant_id OR l.generation_capability IS NOT NULL)
                          AND p.created_at <= COALESCE(r.created_at, l.created_at)
                          AND NOT EXISTS (
                              SELECT 1 FROM partners other
                              WHERE other.id<>p.id AND other.team_tenant_id=l.tenant_id
                                AND other.created_at <= COALESCE(r.created_at, l.created_at)
                                AND (other.status<>'DELETED' OR other.updated_at > COALESCE(r.created_at, l.created_at))
                          )
                      ))
                    UNION ALL
                    SELECT l.id, 'DISTRIBUTION', d.actor_user_id, -(l.gift_delta+l.purchased_delta),
                           NULL, l.description, d.status, l.created_at
                    FROM partner_distributions d
                    JOIN billing_ledger l ON l.tenant_id=:tenant AND l.event_key='partner-account-out:'||d.request_key
                    WHERE d.partner_id=:partner AND d.source='ACCOUNT' AND d.recipient_type='TENANT'
                      AND l.type='TRANSFER' AND l.gift_delta+l.purchased_delta < 0
                ), filtered AS (
                    SELECT a.*, n.member_number, COALESCE(n.display_name, u.name) AS actor_name
                    FROM activity a
                    LEFT JOIN partner_member_numbers n ON n.partner_id=:partner AND n.user_id=a.actor
                    LEFT JOIN users u ON u.id=n.user_id
                    WHERE (:viewer=0 OR a.actor=:viewer) AND (:kind='' OR a.kind=:kind)
                      AND (:member=0 OR n.member_number=:member)
                ), totals AS (
                    SELECT COUNT(*) AS total,
                           COALESCE(SUM(credits) FILTER(WHERE kind='CREATION'),0) AS consumed,
                           COALESCE(SUM(credits) FILTER(WHERE kind='DISTRIBUTION'),0) AS distributed
                    FROM filtered
                )
                SELECT a.id, a.kind, a.member_number, a.actor_name, a.credits, a.capability, a.description, a.status, a.created_at,
                       totals.total, totals.consumed, totals.distributed
                FROM totals LEFT JOIN (
                    SELECT * FROM filtered ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset
                ) a ON true
                ORDER BY a.created_at DESC, a.id DESC
                """).setParameter("partner", partner).setParameter("tenant", tenant).setParameter("viewer", viewer)
                .setParameter("kind", kind == null ? "" : kind).setParameter("member", memberNumber == null ? 0L : memberNumber)
                .setParameter("size", size).setParameter("offset", (long) page * size).getResultList();
        var items = new ArrayList<PartnerDtos.TeamActivity>();
        for (Object result : rows) {
            var row = (Object[]) result;
            if (row[0] == null) continue;
            items.add(new PartnerDtos.TeamActivity(number(row[0]), (String) row[1], row[2] == null ? null : number(row[2]),
                    (String) row[3], number(row[4]), (String) row[5], (String) row[6], (String) row[7], instant(row[8])));
        }
        var total = (Object[]) rows.getFirst();
        return new PartnerDtos.TeamActivityLog(new PartnerDtos.Page<>(items, page, size, number(total[9])), number(total[10]), number(total[11]));
    }

    private static long number(Object value) { return ((Number) value).longValue(); }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        return ((Timestamp) value).toInstant();
    }
}
