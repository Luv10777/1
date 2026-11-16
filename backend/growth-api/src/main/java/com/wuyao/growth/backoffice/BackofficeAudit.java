package com.wuyao.growth.backoffice;

import com.wuyao.growth.common.web.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.*;

/** Append-only request outcomes. Bodies, credentials and payment-account details are never logged here. */
@Service @RequiredArgsConstructor
public class BackofficeAudit {
    private final EntityManager em;
    private final BackofficeAccess access;
    public record Entry(Long id, Long actorUserId, String actorName, Long partnerId, String method, String path,
                        String action, int httpStatus, Instant createdAt) {}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void append(Long actor, String name, Long partnerId, String method, String path, String action, int status) {
        em.createNativeQuery("INSERT INTO backoffice_operations(actor_user_id,actor_name,partner_id,method,path,action,http_status) VALUES (:actor,:name,:partner,:method,:path,:action,:status)")
                .setParameter("actor", actor).setParameter("name", name).setParameter("partner", partnerId).setParameter("method", method)
                .setParameter("path", path).setParameter("action", action).setParameter("status", status).executeUpdate();
    }

    @Transactional(readOnly=true)
    public PageResult<Entry> list(Long actor, int page, int size, int days, Long actorId, String keyword, String result) {
        access.require(actor, "SECURITY");
        if (page<0 || page>1000 || size<1 || size>100 || days<1 || days>365 || (result!=null && !Set.of("SUCCESS","FAILED").contains(result)))
            throw BizException.of(ErrorCode.BAD_REQUEST, "日志筛选参数无效");
        String where = " WHERE created_at>=:since" + (actorId==null ? "" : " AND actor_user_id=:actor")
                + (keyword==null || keyword.isBlank() ? "" : " AND (strpos(lower(actor_name),:key)>0 OR strpos(lower(path),:key)>0 OR strpos(lower(action),:key)>0)")
                + (result==null ? "" : "SUCCESS".equals(result) ? " AND http_status BETWEEN 200 AND 299" : " AND http_status>=400");
        var params = new HashMap<String,Object>(); params.put("since", Instant.now().minusSeconds(days*86400L));
        if(actorId!=null) params.put("actor",actorId);
        if(keyword!=null && !keyword.isBlank()) params.put("key", keyword.trim().toLowerCase(Locale.ROOT));
        var count = em.createNativeQuery("SELECT count(*) FROM backoffice_operations"+where); params.forEach(count::setParameter);
        long total = ((Number)count.getSingleResult()).longValue();
        var query = em.createNativeQuery("SELECT id,actor_user_id,actor_name,partner_id,method,path,action,http_status,created_at FROM backoffice_operations"+where+" ORDER BY created_at DESC,id DESC");
        params.forEach(query::setParameter); query.setFirstResult(page*size).setMaxResults(size);
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        var entries=rows.stream().map(r -> new Entry(((Number)r[0]).longValue(),((Number)r[1]).longValue(),(String)r[2],r[3]==null?null:((Number)r[3]).longValue(),
                (String)r[4],(String)r[5],(String)r[6],((Number)r[7]).intValue(), instant(r[8]))).toList();
        return new PageResult<>(entries,page,size,total,(int)((total+size-1)/size));
    }
    private static Instant instant(Object v) { return v instanceof Instant i?i:v instanceof java.sql.Timestamp t?t.toInstant():((java.time.OffsetDateTime)v).toInstant(); }
}
