package com.wuyao.growth.billing;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service @RequiredArgsConstructor
public class BillingService {
    public static final int IMAGE_CREDITS = 3;
    public static final int VIDEO_ANALYSIS_CREDITS = 1;
    public static final int CREDITS_PER_YUAN = 10;
    private final BillingStore store;
    private final BillingProperties config;
    private final WechatPayGateway wechat;
    /** Generation writers use one account-first lock order, including cancellation and retries. */
    @Transactional
    public void lockForGeneration() { store.lockAccount(); }

    @Transactional(readOnly=true)
    public BillingDtos.Summary summary(boolean canManage,boolean operator) {
        var a=store.account();
        return new BillingDtos.Summary("烟火",CREDITS_PER_YUAN,IMAGE_CREDITS,VIDEO_ANALYSIS_CREDITS,
            a==null?0:a.available(),a==null?0:a.getGiftAvailable(),a==null?0:a.getPurchasedAvailable(),
            a==null?0:a.getHeld(),a!=null&&a.active(Instant.now()),a==null?null:a.getMemberUntil(),
            a==null?null:a.getPlanSnapshot(),canManage,operator,payment());
    }
    @Transactional(readOnly=true)
    public List<BillingDtos.PlanSnapshot> plans() { return store.plans(false).stream().map(BillingPlan::snapshot).toList(); }
    @Transactional(readOnly=true)
    public List<BillingDtos.PlanListing> operatorPlans() {
        return store.plans(true).stream().map(p->new BillingDtos.PlanListing(p.snapshot(),p.isPublished())).toList();
    }

    @Transactional
    public BillingDtos.OrderView topUp(BillingDtos.TopUp input) {
        if(input.amountMinor()<100 || input.amountMinor()>10000000 || input.amountMinor()%100!=0)
            throw bad("充值金额须为 1 至 100,000 元的整数");
        store.lockAccount();
        var old=store.orderByKey(input.requestKey());
        if(old.isPresent()) {
            if(!"TOPUP".equals(old.get().getKind())||old.get().getAmountMinor()!=input.amountMinor())
                throw bad("同一订单标识不能用于不同的充值金额");
            return view(old.get());
        }
        requirePayment();
        var o=new BillingOrder(); o.setTenantId(TenantContext.require()); o.setRequestKey(input.requestKey());
        o.setKind("TOPUP"); o.setAmountMinor(input.amountMinor()); o.setCredits(input.amountMinor()/10);
        o.setPaymentMethod(config.getPaymentMethod());
        o.setExpiresAt(Instant.now().plus("WECHAT".equals(o.getPaymentMethod())?Duration.ofMinutes(30):Duration.ofDays(7))); store.save(o);
        return view(o);
    }
    @Transactional
    public BillingDtos.OrderView subscribe(BillingDtos.Subscribe input) {
        var a=store.lockAccount();
        var old=store.orderByKey(input.requestKey());
        if(old.isPresent()) {
            if(!"MEMBERSHIP".equals(old.get().getKind())||!Objects.equals(old.get().getPlanSnapshot().id(),input.planId()))
                throw bad("同一订单标识不能用于不同的套餐");
            return view(old.get());
        }
        requirePayment();
        var p=store.plan(input.planId());
        if(p==null||!p.isPublished()) throw bad("该套餐暂不可订阅");
        checkRenewal(a,p.snapshot());
        if(store.pendingMembership()) throw bad("已有待支付的会员订单，请先完成或取消该订单");
        var o=new BillingOrder(); o.setTenantId(TenantContext.require()); o.setRequestKey(input.requestKey());
        o.setKind("MEMBERSHIP"); o.setAmountMinor(p.getPriceMinor()); o.setCredits(p.getGiftCredits());
        o.setPlanSnapshot(p.snapshot()); o.setPaymentMethod(config.getPaymentMethod());
        o.setExpiresAt(Instant.now().plus("WECHAT".equals(o.getPaymentMethod())?Duration.ofMinutes(30):Duration.ofDays(7))); store.save(o);
        return view(o);
    }
    @Transactional
    public BillingDtos.OrderView order(String id) {
        var o=requireOrder(store.lockOrder(id));
        if("PENDING_PAYMENT".equals(o.getState())&&!o.getExpiresAt().isAfter(Instant.now())) o.setState("EXPIRED");
        return view(o);
    }
    @Transactional
    public BillingDtos.OrderView cancelOrder(String id) {
        var o=requireOrder(store.lockOrder(id));
        if(java.util.Set.of("PENDING_PAYMENT","EXPIRED").contains(o.getState())) o.setState("CANCELLED");
        return view(o);
    }
    @Transactional(readOnly=true)
    public List<String> reconciliationOrders() { return store.reconciliationOrders(); }
    /** Only a platform operator may invoke this, after checking an actual receipt. */
    @Transactional
    public BillingDtos.OrderView confirmPayment(String id,BillingDtos.Confirmation input,Long operator) {
        if(!config.getOperatorUserIds().contains(operator)) throw BizException.of(ErrorCode.FORBIDDEN,"没有收款核销权限");
        var a=store.lockAccount(); var o=requireOrder(store.lockOrder(id));
        if(!"MANUAL".equals(o.getPaymentMethod())) throw bad("微信支付订单须以微信验证的支付结果入账");
        String receipt="manual:"+input.receiptReference().trim();
        if(o.getAmountMinor()!=input.receivedAmountMinor()) throw bad("实际到账金额与订单金额不一致");
        if("PAID".equals(o.getState())) {
            if(!receipt.equals(o.getReceiptReference())) throw bad("该订单已由另一笔收款核销");
            return view(o);
        }
        if(!"PENDING_PAYMENT".equals(o.getState())) throw bad("该订单已关闭，不能核销");
        if(!o.getExpiresAt().isAfter(Instant.now())) throw bad("订单已过期，请核对收款并重新创建订单");
        return fulfil(a,o,receipt,operator);
    }
    @Transactional
    public BillingDtos.OrderView confirmWechat(String id,com.fasterxml.jackson.databind.JsonNode transaction) {
        var a=store.lockAccount(); var o=requireOrder(store.lockOrder(id));
        if(!"WECHAT".equals(o.getPaymentMethod())) throw bad("订单收款方式不匹配");
        wechat.validatePaid(transaction,view(o),TenantContext.require());
        String receipt="wechat:"+transaction.path("transaction_id").asText();
        if("PAID".equals(o.getState())) {
            if(!receipt.equals(o.getReceiptReference())) throw bad("支付流水与已入账订单不一致");
            return view(o);
        }
        return fulfil(a,o,receipt,null);
    }
    @Transactional
    public BillingDtos.OrderView savePaymentCode(String id,String codeUrl) {
        var o=requireOrder(store.lockOrder(id));
        if("PENDING_PAYMENT".equals(o.getState())&&o.getPaymentCodeUrl()==null) o.setPaymentCodeUrl(codeUrl);
        return view(o);
    }
    private BillingDtos.OrderView fulfil(BillingAccount a,BillingOrder o,String receipt,Long operator) {
        if("MEMBERSHIP".equals(o.getKind())) {
            var p=o.getPlanSnapshot(); checkRenewal(a,p);
            Instant start=a.active(Instant.now())?a.getMemberUntil():Instant.now();
            a.setPlanSnapshot(p); a.setMemberUntil(start.plus(Duration.ofDays(p.durationDays())));
            a.setMembershipOrderId(o.getId()); a.setGiftAvailable(Math.addExact(a.getGiftAvailable(),o.getCredits()));
        } else a.setPurchasedAvailable(Math.addExact(a.getPurchasedAvailable(),o.getCredits()));
        o.setReceiptReference(receipt); o.setConfirmedBy(operator); o.setPaidAt(Instant.now()); o.setState("PAID");
        ledger(a,"order:"+o.getId(),"MEMBERSHIP".equals(o.getKind())?"GRANT":"PURCHASE",
            "MEMBERSHIP".equals(o.getKind())?o.getPlanSnapshot().name()+" · 会员赠送":"充值烟火",
            "MEMBERSHIP".equals(o.getKind())?o.getCredits():0,"TOPUP".equals(o.getKind())?o.getCredits():0,0,o.getId(),null);
        store.flush(); // receipt_reference uniqueness also prevents reusing one receipt across tenants.
        return view(o);
    }

    @Transactional
    public String reserve(String requestKey,String capability,long amount,String description) {
        if(amount<=0) throw bad("预占烟火必须大于零");
        var a=store.lockAccount();
        var existing=store.reservationByKey(requestKey);
        if(existing.isPresent()) {
            if(!capability.equals(existing.get().getCapability())||amount!=existing.get().getInitialAmount()) throw bad("预占标识已被用于不同的创作费用");
            return existing.get().getId();
        }
        requireEntitlement(a,capability);
        var r=new BillingReservation(); r.setTenantId(TenantContext.require()); r.setRequestKey(requestKey);
        r.setCapability(capability); r.setInitialAmount(amount); store.save(r);
        hold(a,r,amount,"reserve:"+requestKey,description);
        return r.getId();
    }
    @Transactional
    public void reserveMore(String reservationId,long amount,String eventKey,String description) {
        var a=store.lockAccount(); var r=requireReservation(store.reservation(reservationId));
        if(store.eventExists(eventKey)) return;
        requireEntitlement(a,r.getCapability()); hold(a,r,amount,eventKey,description);
    }
    @Transactional
    public void capture(String reservationId,long amount,String eventKey,String description) {
        var a=store.lockAccount(); var r=requireReservation(store.reservation(reservationId));
        if(store.eventExists(eventKey)) return;
        if(amount<=0||amount>r.remaining()) throw bad("扣费超出本次预占烟火");
        long gift=Math.min(r.getGiftHeld(),amount);
        r.setGiftHeld(r.getGiftHeld()-gift); r.setPurchasedHeld(r.getPurchasedHeld()-(amount-gift));
        r.setCaptured(Math.addExact(r.getCaptured(),amount)); a.setHeld(a.getHeld()-amount);
        ledger(a,eventKey,"CAPTURE",description,0,0,-amount,null,reservationId);
    }
    @Transactional
    public void release(String reservationId,String eventKey,String description) {
        var a=store.lockAccount(); var r=requireReservation(store.reservation(reservationId));
        if(store.eventExists(eventKey)||r.remaining()==0) return;
        long gift=r.getGiftHeld(),purchased=r.getPurchasedHeld();
        a.setGiftAvailable(Math.addExact(a.getGiftAvailable(),gift));
        a.setPurchasedAvailable(Math.addExact(a.getPurchasedAvailable(),purchased)); a.setHeld(a.getHeld()-gift-purchased);
        r.setGiftHeld(0); r.setPurchasedHeld(0);
        ledger(a,eventKey,"RELEASE",description,gift,purchased,-gift-purchased,null,reservationId);
    }
    @Transactional
    public long remaining(String reservationId) {
        var r=store.reservationSnapshot(reservationId); return r==null?0:r.remaining();
    }
    @Transactional
    public void releaseAmount(String reservationId,long amount,String eventKey,String description) {
        var a=store.lockAccount(); var r=requireReservation(store.reservation(reservationId));
        if(store.eventExists(eventKey)||r.remaining()==0) return;
        if(amount<=0||amount>r.remaining()) throw bad("释放烟火超出本次预占");
        // Return purchased credits first; the remaining held credits still consume gifts first.
        long purchased=Math.min(r.getPurchasedHeld(),amount),gift=amount-purchased;
        r.setPurchasedHeld(r.getPurchasedHeld()-purchased); r.setGiftHeld(r.getGiftHeld()-gift);
        a.setPurchasedAvailable(a.getPurchasedAvailable()+purchased); a.setGiftAvailable(a.getGiftAvailable()+gift); a.setHeld(a.getHeld()-amount);
        ledger(a,eventKey,"RELEASE",description,gift,purchased,-amount,null,reservationId);
    }
    @Transactional
    public void requireMembership(String capability) { requireEntitlement(store.lockAccount(),capability); }
    @Transactional(readOnly=true)
    public void requirePlatformMembership() { requireActiveMembership(store.account()); }
    private void requireActiveMembership(BillingAccount a) {
        if(a==null||!a.active(Instant.now())) throw BizException.of(ErrorCode.MEMBERSHIP_REQUIRED,"您还未开通会员");
    }
    private void requireEntitlement(BillingAccount a,String capability) {
        requireActiveMembership(a);
        var p=a.getPlanSnapshot();
        boolean enabled=switch(capability) { case "IMAGE"->p.imageEnabled(); case "VIDEO"->p.videoEnabled(); case "VIDEO_ANALYSIS"->true; default->false; };
        if(!enabled) throw BizException.of(ErrorCode.ENTITLEMENT_REQUIRED,"当前套餐尚未开放此项创作功能");
    }
    private void hold(BillingAccount a,BillingReservation r,long amount,String eventKey,String description) {
        if(amount<=0) throw bad("预占烟火必须大于零");
        if(a.available()<amount) throw BizException.of(ErrorCode.INSUFFICIENT_YANHUO,"烟火不足，本次需要 "+amount+" 烟火，请先充值");
        long gift=Math.min(a.getGiftAvailable(),amount),purchased=amount-gift;
        a.setGiftAvailable(a.getGiftAvailable()-gift); a.setPurchasedAvailable(a.getPurchasedAvailable()-purchased);
        a.setHeld(Math.addExact(a.getHeld(),amount));
        r.setGiftHeld(Math.addExact(r.getGiftHeld(),gift)); r.setPurchasedHeld(Math.addExact(r.getPurchasedHeld(),purchased));
        ledger(a,eventKey,"RESERVE",description,-gift,-purchased,amount,null,r.getId());
    }
    private void ledger(BillingAccount a,String key,String type,String description,long gift,long purchased,long held,String order,String reservation) {
        var l=new BillingLedger(); l.setTenantId(TenantContext.require()); l.setEventKey(key); l.setType(type);
        l.setDescription(description); l.setGiftDelta(gift); l.setPurchasedDelta(purchased); l.setHeldDelta(held);
        l.setAvailableAfter(a.available()); l.setHeldAfter(a.getHeld()); l.setOrderId(order); l.setReservationId(reservation);
        a.setUpdatedAt(Instant.now()); store.save(l);
    }
    private void checkRenewal(BillingAccount a,BillingDtos.PlanSnapshot p) {
        if(a.active(Instant.now())&&!a.getPlanSnapshot().code().equals(p.code()))
            throw bad("当前会员有效期内可续订同一套餐，其他套餐请在到期后订阅");
    }
    private void requirePayment() {
        if(!payment().available()) throw BizException.of(ErrorCode.PAYMENT_UNAVAILABLE,"收款服务暂未开放，请联系一方志客服");
    }
    private BillingDtos.PaymentInstructions payment() {
        if("WECHAT".equals(config.getPaymentMethod()))
            return new BillingDtos.PaymentInstructions(wechat.configured(),"","","","微信扫码支付，支付成功后自动到账。","");
        return config.payment();
    }
    private BillingOrder requireOrder(BillingOrder o) {
        if(o==null||!TenantContext.require().equals(o.getTenantId())) throw BizException.of(ErrorCode.NOT_FOUND,"订单不存在"); return o;
    }
    private BillingReservation requireReservation(BillingReservation r) {
        if(r==null||!TenantContext.require().equals(r.getTenantId())) throw bad("烟火预占记录不存在"); return r;
    }
    private BillingDtos.OrderView view(BillingOrder o) {
        String state="PENDING_PAYMENT".equals(o.getState())&&!o.getExpiresAt().isAfter(Instant.now())?"EXPIRED":o.getState();
        return new BillingDtos.OrderView(o.getId(),o.getKind(),o.getAmountMinor(),o.getCredits(),state,o.getPlanSnapshot(),
            o.getCreatedAt(),o.getExpiresAt(),o.getPaidAt(),payment(),o.getPaymentMethod(),o.getPaymentCodeUrl());
    }
    @Transactional(readOnly=true)
    public BillingDtos.History<BillingDtos.OrderView> orders(int page,int size) {
        checkPage(page,size); var list=store.orders(page,size);
        return new BillingDtos.History<>(list.stream().limit(size).map(this::view).toList(),list.size()>size);
    }
    @Transactional(readOnly=true)
    public BillingDtos.History<BillingDtos.LedgerView> ledger(int page,int size) {
        checkPage(page,size); var list=store.ledger(page,size);
        return new BillingDtos.History<>(list.stream().limit(size).map(l->new BillingDtos.LedgerView(l.getId(),l.getType(),l.getDescription(),
            l.getGiftDelta()+l.getPurchasedDelta(),l.getHeldDelta(),l.getAvailableAfter(),l.getHeldAfter(),l.getCreatedAt())).toList(),list.size()>size);
    }
    @Transactional
    public BillingDtos.PlanSnapshot createPlan(BillingDtos.NewPlan input) {
        var p=new BillingPlan(); p.setCode(input.code()); p.setName(input.name().trim()); p.setDescription(input.description().trim());
        p.setPriceMinor(input.priceMinor()); p.setDurationDays(input.durationDays()); p.setGiftCredits(input.giftCredits());
        p.setImageEnabled(input.imageEnabled()); p.setVideoEnabled(input.videoEnabled()); p.setPublished(input.published());
        store.save(p); store.flush(); return p.snapshot();
    }
    @Transactional
    public void publish(Long id,boolean published) {
        var p=store.plan(id); if(p==null) throw bad("套餐不存在"); p.setPublished(published);
    }
    private void checkPage(int page,int size) { if(page<0||page>100000||size<1||size>50) throw bad("分页参数无效"); }
    private BizException bad(String message) { return BizException.of(ErrorCode.BAD_REQUEST,message); }
}
