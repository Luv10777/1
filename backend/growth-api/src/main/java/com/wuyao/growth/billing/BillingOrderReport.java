package com.wuyao.growth.billing;

import com.wuyao.growth.backoffice.BackofficeAccess;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.*;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** Read actual orders in a separate tenant-scoped transaction for every merchant; RLS remains enabled. */
@Service @RequiredArgsConstructor
public class BillingOrderReport {
    private final EntityManager em;
    private final PlatformAccountDirectory directory;
    private final PlatformTransactionManager transactions;
    private final BackofficeAccess access;
    public record Entry(String id,Long tenantId,String tenantName,String kind,String planName,long amountMinor,long credits,
            String state,String paymentMethod,String receiptReference,Long confirmedBy,Instant reconciliationAfter,Instant createdAt,Instant expiresAt,Instant paidAt) {}
    public record Report(PageResult<Entry> page,long paidMinor,long pendingMinor,long paidCount,long pendingCount) {}
    public Report list(Long actor,int page,int size,int days,String state,String kind,String paymentMethod,String keyword) {
        access.require(actor,"FINANCE");
        if(page<0 || page>1000 || size<1 || size>100 || days<1 || days>365
                || state!=null && !Set.of("PAID","PENDING_PAYMENT","EXPIRED").contains(state)
                || kind!=null && !Set.of("TOPUP","MEMBERSHIP","ACTIVATION_CODE").contains(kind)
                || paymentMethod!=null && !Set.of("MANUAL","WECHAT","ALIPAY").contains(paymentMethod)) throw BizException.of(ErrorCode.BAD_REQUEST,"订单筛选参数无效");
        Instant now=Instant.now(), since=now.minus(Duration.ofDays(days));
        List<Entry> rows=new ArrayList<>(); long total=0,paidMinor=0,pendingMinor=0,paidCount=0,pendingCount=0;
        String key=keyword==null?"":keyword.trim().toLowerCase(Locale.ROOT);
        for(var tenant:directory.scopes()) {
            var tx=new TransactionTemplate(transactions); tx.setReadOnly(true); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            var result=TenantContext.runAs(tenant.id(),() -> tx.execute(status -> {
                String where=" where o.tenantId=:tenant and o.createdAt>=:since"+(kind==null?"":" and o.kind=:kind")+(paymentMethod==null?"":" and o.paymentMethod=:method")
                        +(state==null?"":"PAID".equals(state)?" and o.state='PAID'":"EXPIRED".equals(state)?" and (o.state='EXPIRED' or (o.state='PENDING_PAYMENT' and o.expiresAt<=:now))":" and o.state='PENDING_PAYMENT' and o.expiresAt>:now")
                        +(key.isBlank() || tenant.name().toLowerCase(Locale.ROOT).contains(key)?"":" and (locate(:key,lower(o.id))>0 or locate(:key,lower(coalesce(o.receiptReference,'')))>0)");
                var params=new HashMap<String,Object>(); params.put("tenant",tenant.id()); params.put("since",since);
                if(kind!=null) params.put("kind",kind); if(paymentMethod!=null) params.put("method",paymentMethod);
                if(state!=null && !"PAID".equals(state)) params.put("now",now);
                if(where.contains(":key")) params.put("key",key);
                var q=em.createQuery("from BillingOrder o"+where+" order by o.createdAt desc,o.id desc",BillingOrder.class); params.forEach(q::setParameter);
                var entries=q.setMaxResults(Math.addExact(page*size,size)).getResultList().stream().map(o -> new Entry(o.getId(),tenant.id(),tenant.name(),o.getKind(),o.getPlanSnapshot()==null?null:o.getPlanSnapshot().name(),o.getAmountMinor(),o.getCredits(),
                        "PENDING_PAYMENT".equals(o.getState()) && !o.getExpiresAt().isAfter(now)?"EXPIRED":o.getState(),o.getPaymentMethod(),o.getReceiptReference(),o.getConfirmedBy(),o.getReconciliationAfter(),o.getCreatedAt(),o.getExpiresAt(),o.getPaidAt())).toList();
                var aggregate=em.createQuery("select count(o),coalesce(sum(case when o.state='PAID' then o.amountMinor else 0 end),0),coalesce(sum(case when o.state='PENDING_PAYMENT' and o.expiresAt>:aggregateNow then o.amountMinor else 0 end),0),coalesce(sum(case when o.state='PAID' then 1 else 0 end),0),coalesce(sum(case when o.state='PENDING_PAYMENT' and o.expiresAt>:aggregateNow then 1 else 0 end),0) from BillingOrder o"+where,Object[].class);
                params.forEach(aggregate::setParameter); aggregate.setParameter("aggregateNow",now);
                return new ScopeResult(entries,aggregate.getSingleResult());
            }));
            rows.addAll(result.entries()); var a=result.totals(); total+=((Number)a[0]).longValue(); paidMinor+=((Number)a[1]).longValue(); pendingMinor+=((Number)a[2]).longValue(); paidCount+=((Number)a[3]).longValue(); pendingCount+=((Number)a[4]).longValue();
        }
        rows.sort(Comparator.comparing(Entry::createdAt).thenComparing(Entry::id).reversed());
        int start=Math.min(page*size,rows.size());
        return new Report(new PageResult<>(rows.subList(start,Math.min(start+size,rows.size())),page,size,total,(int)((total+size-1)/size)),paidMinor,pendingMinor,paidCount,pendingCount);
    }
    private record ScopeResult(List<Entry> entries,Object[] totals) {}
}
