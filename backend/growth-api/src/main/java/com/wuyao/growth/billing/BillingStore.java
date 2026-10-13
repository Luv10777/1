package com.wuyao.growth.billing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import com.wuyao.growth.common.tenant.TenantContext;
import java.util.List;
import java.util.Optional;

@Repository @RequiredArgsConstructor
public class BillingStore {
    private final EntityManager em;
    public BillingAccount lockAccount() {
        long tenant = TenantContext.require();
        em.createNativeQuery("INSERT INTO billing_accounts(tenant_id) VALUES (:tenant) ON CONFLICT DO NOTHING")
            .setParameter("tenant",tenant).executeUpdate();
        return em.find(BillingAccount.class,tenant,LockModeType.PESSIMISTIC_WRITE);
    }
    public BillingAccount account() { return em.find(BillingAccount.class,TenantContext.require()); }
    public BillingPlan plan(Long id) { return em.find(BillingPlan.class,id); }
    public List<BillingPlan> plans(boolean includeUnpublished) {
        return em.createQuery("from BillingPlan p where :all = true or p.published = true order by p.priceMinor,p.id",BillingPlan.class)
            .setParameter("all",includeUnpublished).getResultList();
    }
    public void lockPlanCode(String code) {
        em.createNativeQuery("SELECT true FROM pg_advisory_xact_lock(hashtextextended(:key,0))")
            .setParameter("key","billing-plan:"+code).getSingleResult();
    }
    public boolean publishedPlanExists(String code,Long exceptId) {
        return em.createQuery("select count(p) from BillingPlan p where p.code=:code and p.published=true and (:id is null or p.id<>:id)",Long.class)
            .setParameter("code",code).setParameter("id",exceptId).getSingleResult()>0;
    }
    public boolean claimRecovery(long intervalMs) {
        return em.createNativeQuery("UPDATE billing_accounts SET recovery_after=now()+(:delay * interval '1 millisecond') "
                +"WHERE tenant_id=:tenant AND (recovery_after IS NULL OR recovery_after<=now())")
            .setParameter("tenant",TenantContext.require()).setParameter("delay",intervalMs).executeUpdate()==1;
    }
    public List<String> unsettledReservations(String after) {
        return em.createQuery("select r.id from BillingReservation r where r.tenantId=:tenant and r.id>:after and r.giftHeld+r.purchasedHeld>0 order by r.id",String.class)
            .setParameter("tenant",TenantContext.require()).setParameter("after",after).setMaxResults(100).getResultList();
    }
    public Optional<BillingOrder> orderByKey(String key) {
        return em.createQuery("from BillingOrder o where o.tenantId = :tenant and o.requestKey = :key",BillingOrder.class)
            .setParameter("tenant",TenantContext.require()).setParameter("key",key).getResultStream().findFirst();
    }
    public BillingOrder lockOrder(String id) { return em.find(BillingOrder.class,id,LockModeType.PESSIMISTIC_WRITE); }
    public boolean pendingMembership() {
        return em.createQuery("select count(o) from BillingOrder o where o.tenantId=:tenant and o.kind='MEMBERSHIP' and o.state in ('PENDING_PAYMENT','EXPIRED')",Long.class)
            .setParameter("tenant",TenantContext.require()).getSingleResult()>0;
    }
    public List<String> reconciliationOrders() {
        return em.createQuery("select o.id from BillingOrder o where o.tenantId=:tenant and o.paymentMethod='WECHAT' and o.state in ('PENDING_PAYMENT','EXPIRED') order by o.createdAt",String.class)
            .setParameter("tenant",TenantContext.require()).setMaxResults(20).getResultList();
    }
    public List<BillingOrder> orders(int page,int size) {
        return em.createQuery("from BillingOrder o where o.tenantId = :tenant order by o.createdAt desc,o.id",BillingOrder.class)
            .setParameter("tenant",TenantContext.require()).setFirstResult(Math.multiplyExact(page,size)).setMaxResults(size+1).getResultList();
    }
    public Optional<BillingReservation> reservationByKey(String key) {
        return em.createQuery("from BillingReservation r where r.tenantId = :tenant and r.requestKey = :key",BillingReservation.class)
            .setParameter("tenant",TenantContext.require()).setParameter("key",key).getResultStream().findFirst();
    }
    public BillingReservation reservation(String id) { return em.find(BillingReservation.class,id,LockModeType.PESSIMISTIC_WRITE); }
    public BillingReservation reservationSnapshot(String id) { return em.find(BillingReservation.class,id); }
    public boolean eventExists(String key) {
        return em.createQuery("select count(l) from BillingLedger l where l.tenantId = :tenant and l.eventKey = :key",Long.class)
            .setParameter("tenant",TenantContext.require()).setParameter("key",key).getSingleResult()>0;
    }
    public List<BillingLedger> ledger(int page,int size) {
        return em.createQuery("from BillingLedger l where l.tenantId = :tenant order by l.id desc",BillingLedger.class)
            .setParameter("tenant",TenantContext.require()).setFirstResult(Math.multiplyExact(page,size)).setMaxResults(size+1).getResultList();
    }
    public void save(Object entity) { em.persist(entity); }
    public void flush() { em.flush(); }
}
