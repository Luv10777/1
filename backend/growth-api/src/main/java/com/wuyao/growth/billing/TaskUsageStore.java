package com.wuyao.growth.billing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

/** Read-only reporting projection. Generation entities and the immutable ledger remain owned by their modules. */
@Repository
@RequiredArgsConstructor
public class TaskUsageStore {
    private final EntityManager em;

    private static final String IMAGE_ITEMS = """
        SELECT i.*, t.finished_at AS task_finished_at,
               CASE WHEN i.previewed_at IS NOT NULL THEN 'SUCCEEDED'
                    WHEN i.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','UPSTREAM_UNKNOWN','INTERRUPTED')
                    AND t.status IN ('FAILED','CANCELLED') THEN 'INTERRUPTED' ELSE i.status END AS effective_status,
               (c.parent_id IS NOT NULL AND c.request_key <> c.request->>'requestKey'
                    AND i.task_id IS NULL AND i.status = 'SUCCEEDED') AS reused
        FROM image_items i JOIN image_creations c ON c.id=i.creation_id AND c.tenant_id=i.tenant_id
        LEFT JOIN tasks t ON t.id=i.task_id AND t.tenant_id=i.tenant_id
        WHERE i.tenant_id=:tenant
        """;

    static final String REPORT = """
        WITH settlements AS (
            SELECT reservation_id,
                   SUM(CASE WHEN type='CAPTURE' THEN -held_delta ELSE 0 END) AS charged,
                   SUM(held_delta) AS held,
                   SUM(CASE WHEN type='RELEASE' THEN -held_delta ELSE 0 END) AS released,
                   MAX(created_at) FILTER (WHERE type IN ('CAPTURE','RELEASE')) AS settled_at
            FROM billing_ledger WHERE tenant_id=:tenant AND reservation_id IS NOT NULL GROUP BY reservation_id
        ), image_items_scoped AS (
        """ + IMAGE_ITEMS + """
        ), image_counts AS (
            SELECT creation_id, COUNT(*) AS quantity,
                   COUNT(*) FILTER (WHERE effective_status='SUCCEEDED') AS succeeded,
                   COUNT(*) FILTER (WHERE effective_status IN ('FAILED','INTERRUPTED')) AS failed,
                   COUNT(*) FILTER (WHERE effective_status='CANCELLED') AS cancelled,
                   COUNT(*) FILTER (WHERE effective_status='UPSTREAM_UNKNOWN') AS unknown,
                   COUNT(*) FILTER (WHERE effective_status NOT IN
                       ('SUCCEEDED','FAILED','INTERRUPTED','CANCELLED','UPSTREAM_UNKNOWN')) AS pending,
                   STRING_AGG(DISTINCT NULLIF(model,''), ', ' ORDER BY NULLIF(model,'')) AS model,
                   MAX(GREATEST(persisted_at, task_finished_at)) AS finished_at,
                   MIN(previewed_at) AS previewed_at
            FROM image_items_scoped WHERE NOT reused GROUP BY creation_id
        ), sources AS (
            SELECT 'image-'||c.id AS id, c.id AS task_id, 'IMAGE_GENERATION' AS module,
                   c.request->>'workflow' AS workflow, COALESCE(NULLIF(c.title,''),c.request->>'brief','图片创作') AS title,
                   '一方志Image'::text AS model, a.model AS provider_model,
                   CASE WHEN a.previewed_at IS NOT NULL THEN 'SUCCEEDED'
                        WHEN a.quantity > 0 THEN
                       CASE WHEN a.succeeded=a.quantity THEN 'SUCCEEDED' WHEN a.cancelled=a.quantity THEN 'CANCELLED'
                            WHEN a.pending>0 THEN 'RUNNING' WHEN a.unknown>0 THEN 'UPSTREAM_UNKNOWN'
                            WHEN a.succeeded>0 THEN 'PARTIAL' ELSE 'FAILED' END
                       WHEN c.status='NEEDS_INPUT' THEN 'NEEDS_INPUT'
                       WHEN c.status IN ('FAILED','INTERRUPTED') OR t.status='FAILED' THEN 'FAILED'
                       WHEN c.status='CANCELLED' THEN 'CANCELLED' ELSE 'RUNNING' END AS status,
                   c.created_at, COALESCE(a.quantity,(c.request->>'count')::int,0) AS quantity,
                   COALESCE(a.succeeded,0) AS succeeded,
                   COALESCE(a.failed,CASE WHEN c.status IN ('FAILED','INTERRUPTED') OR t.status='FAILED'
                       THEN (c.request->>'count')::int ELSE 0 END) AS failed,
                   COALESCE(a.cancelled,CASE WHEN c.status='CANCELLED' THEN (c.request->>'count')::int ELSE 0 END) AS cancelled,
                   '张' AS unit, c.billing_reservation_id AS reservation_id,
                   c.request->>'quality' AS resolution, NULL::int AS duration_seconds,
                   CASE WHEN a.previewed_at IS NULL THEN c.error END AS error,
                   GREATEST(a.finished_at,t.finished_at) AS source_finished_at, a.previewed_at
            FROM image_creations c LEFT JOIN image_counts a ON a.creation_id=c.id
            LEFT JOIN tasks t ON t.id=c.task_id AND t.tenant_id=c.tenant_id WHERE c.tenant_id=:tenant
            UNION ALL
            SELECT 'video-'||v.id, v.id,
                   CASE WHEN v.request->>'sourceModule'='DIGITAL_HUMAN'
                        OR (v.request->>'sourceModule' IS NULL AND v.request->>'prompt' LIKE '生成数字人口播带货视频。%')
                        THEN 'DIGITAL_HUMAN_VIDEO' ELSE 'VIDEO_GENERATION' END,
                   NULL, v.request->>'prompt',
                   CASE v.model WHEN 'SEEDANCE_2_5' THEN 'Seedance 2.5' WHEN 'SEEDANCE_2_0' THEN 'Seedance 2.0'
                       WHEN 'SEEDANCE_2_0_MINI' THEN 'Seedance 2.0 Mini' WHEN 'SEEDANCE_2_0_FAST' THEN 'Seedance 2.0 Fast'
                       ELSE v.model END,
                   COALESCE(NULLIF(v.provider_submit_request->>'model',''),NULLIF(v.billing_quote->>'providerModel',''),v.model),
                   CASE WHEN v.status IN ('SUCCEEDED','FAILED','CANCELLED') THEN v.status
                        WHEN t.status IN ('FAILED','CANCELLED') THEN 'FAILED' ELSE 'RUNNING' END,
                   v.created_at, 1, CASE WHEN v.status='SUCCEEDED' THEN 1 ELSE 0 END,
                   CASE WHEN v.status='FAILED' OR (v.status NOT IN ('SUCCEEDED','CANCELLED')
                       AND t.status IN ('FAILED','CANCELLED')) THEN 1 ELSE 0 END,
                   CASE WHEN v.status='CANCELLED' THEN 1 ELSE 0 END, '条', v.billing_reservation_id,
                   v.resolution, v.duration_seconds, v.error_message,
                   CASE WHEN v.status IN ('SUCCEEDED','FAILED','CANCELLED') THEN v.updated_at
                        WHEN t.status IN ('FAILED','CANCELLED') THEN t.finished_at END, NULL::timestamptz
            FROM video_workflows v LEFT JOIN tasks t ON t.id=v.task_id AND t.tenant_id=v.tenant_id WHERE v.tenant_id=:tenant
            UNION ALL
            SELECT 'analysis-'||v.id, v.id, 'VIDEO_ANALYSIS', v.mode, v.name,
                   '一方志反推', NULL::text,
                   CASE WHEN v.status IN ('SUCCEEDED','FAILED') THEN v.status
                        WHEN t.status IN ('FAILED','CANCELLED') THEN 'FAILED' ELSE 'RUNNING' END,
                   v.created_at, 1, CASE WHEN v.status='SUCCEEDED' THEN 1 ELSE 0 END,
                   CASE WHEN v.status='FAILED' OR (v.status<>'SUCCEEDED' AND t.status IN ('FAILED','CANCELLED'))
                       THEN 1 ELSE 0 END, 0, '次', v.billing_reservation_id, NULL, NULL::int, v.error_message,
                   CASE WHEN v.status IN ('SUCCEEDED','FAILED') THEN v.updated_at
                        WHEN t.status IN ('FAILED','CANCELLED') THEN t.finished_at END, NULL::timestamptz
            FROM video_analyses v LEFT JOIN tasks t ON t.id=v.task_id AND t.tenant_id=v.tenant_id WHERE v.tenant_id=:tenant
            UNION ALL
            SELECT 'plan-'||p.id, p.id, 'VIDEO_PLAN',
                   CASE WHEN p.previous_plan_id IS NULL THEN 'INITIAL' ELSE 'REGENERATE' END,
                   COALESCE(NULLIF(p.idea,''),'视频方案'), '一方志提示词', NULL::text,
                   CASE WHEN p.status IN ('SUCCEEDED','FAILED') THEN p.status
                        WHEN t.status IN ('FAILED','CANCELLED') THEN 'FAILED' ELSE 'RUNNING' END,
                   p.created_at, 3, CASE WHEN p.status='SUCCEEDED' THEN 3 ELSE 0 END,
                   CASE WHEN p.status='FAILED' OR (p.status<>'SUCCEEDED' AND t.status IN ('FAILED','CANCELLED'))
                       THEN 3 ELSE 0 END, 0, '个方案', p.billing_reservation_id, NULL, NULL::int, p.error_message,
                   CASE WHEN p.status IN ('SUCCEEDED','FAILED') THEN p.updated_at
                        WHEN t.status IN ('FAILED','CANCELLED') THEN t.finished_at END, NULL::timestamptz
            FROM video_plans p LEFT JOIN tasks t ON t.id=p.task_id AND t.tenant_id=p.tenant_id WHERE p.tenant_id=:tenant
        ), settled_logs AS (
            SELECT s.*, CASE WHEN s.reservation_id IS NULL THEN NULL ELSE COALESCE(b.charged,0) END AS charged,
                   COALESCE(b.held,0) AS held, COALESCE(b.released,0) AS released,
                   CASE WHEN s.previewed_at IS NOT NULL THEN s.previewed_at
                        WHEN s.status NOT IN ('RUNNING','NEEDS_INPUT')
                        THEN GREATEST(s.source_finished_at,b.settled_at) END AS finished_at
            FROM sources s LEFT JOIN settlements b ON b.reservation_id=s.reservation_id
        ), logs AS (
            SELECT s.*, CASE WHEN s.status NOT IN ('RUNNING','NEEDS_INPUT') AND s.finished_at IS NULL THEN NULL
                        ELSE GREATEST(0,EXTRACT(EPOCH FROM (COALESCE(s.finished_at,CURRENT_TIMESTAMP)-s.created_at))*1000)::bigint
                        END AS elapsed_millis,
                   CASE WHEN s.previewed_at IS NOT NULL THEN
                       GREATEST(0,EXTRACT(EPOCH FROM (s.previewed_at-s.created_at))*1000)::bigint
                   END AS preview_elapsed_millis
            FROM settled_logs s
        )
        """;

    List<Tuple> report(String select, String where, Map<String,Object> parameters) {
        return query(REPORT + select + " FROM logs " + where, parameters);
    }

    List<Tuple> items(long creation, long tenant) {
        return query("WITH image_items_scoped AS (" + IMAGE_ITEMS + """
            ) SELECT i.id, i.ordinal, COALESCE(NULLIF(i.spec->>'headline',''),i.spec->>'role','图片') AS title,
                     i.model, i.effective_status AS status, i.reused,
                     CASE WHEN i.previewed_at IS NULL THEN i.error END AS error,
                     CASE WHEN c.billing_reservation_id IS NULL THEN NULL ELSE COALESCE((
                         SELECT SUM(-l.held_delta) FROM billing_ledger l WHERE l.tenant_id=:tenant
                         AND l.reservation_id=c.billing_reservation_id AND l.type='CAPTURE'
                         AND split_part(l.event_key,':',1)='image-delivered'
                         AND split_part(l.event_key,':',2)=i.id::text),0) END AS charged
              FROM image_items_scoped i JOIN image_creations c ON c.id=i.creation_id AND c.tenant_id=i.tenant_id
              WHERE i.creation_id=:creation ORDER BY i.ordinal,i.id
            """, Map.of("tenant",tenant,"creation",creation));
    }

    List<Tuple> events(String reservation, long tenant) {
        return query("""
            SELECT id,type,ABS(held_delta) AS credits,description,created_at FROM billing_ledger
            WHERE tenant_id=:tenant AND reservation_id=:reservation ORDER BY id
            """, Map.of("tenant",tenant,"reservation",reservation));
    }

    @SuppressWarnings("unchecked")
    private List<Tuple> query(String sql, Map<String,Object> parameters) {
        var query=em.createNativeQuery(sql,Tuple.class);
        parameters.forEach(query::setParameter);
        return query.getResultList();
    }
}
