package com.wuyao.growth.partner;

import com.wuyao.growth.common.web.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.wuyao.growth.partner.PartnerCrmStore.*;

@Service @RequiredArgsConstructor
public class PartnerCrmService {
    private final PartnerService partners;
    private final PartnerCrmStore store;

    @Transactional(readOnly=true)
    public PartnerCrmDtos.Crm crm(Long actor,Long tenant,int page,int size) {
        paging(page,size); var a=access(actor); partners.owned(a,tenant);
        long total=number(store.query("SELECT count(*) FROM partner_follow_ups WHERE partner_id=:p AND tenant_id=:t","p",a.partner().id(),"t",tenant).getSingleResult());
        return new PartnerCrmDtos.Crm(store.profile(a.partner().id(),tenant),new PartnerDtos.Page<>(store.followUps(a.partner().id(),tenant,page,size).stream().map(PartnerCrmStore::followUp).toList(),page,size,total));
    }
    private PartnerStore.Access lock(Long actor,Long tenant) {
        var a=access(actor); partners.owned(a,tenant);
        store.query("SELECT id FROM partners WHERE id=:p FOR UPDATE", "p", a.partner().id()).getSingleResult();
        store.lockTenant(tenant); a=access(actor); partners.owned(a,tenant); return a;
    }
    /** 商户档案和跟进记录要有「商户」权限，名下商户整个服务商共有。 */
    private PartnerStore.Access access(Long actor) {
        return partners.require(actor,PartnerStore.Perms::tenants,"商户");
    }
    @Transactional
    public PartnerCrmDtos.Profile saveProfile(Long actor,Long tenant,PartnerCrmDtos.SaveProfile input) {
        var a=lock(actor,tenant); var old=store.profile(a.partner().id(),tenant); version(old,input.version()); next(input.nextFollowUpAt());
        store.save(a.partner().id(),tenant,actor,input); return store.profile(a.partner().id(),tenant);
    }
    @Transactional
    public PartnerCrmDtos.Crm followUp(Long actor,Long tenant,PartnerCrmDtos.NewFollowUp input) {
        var a=lock(actor,tenant); Long p=a.partner().id();
        var existing=store.rows("SELECT tenant_id,actor_user_id,channel,content,stage,next_follow_up_at FROM partner_follow_ups WHERE partner_id=:p AND request_key=:key","p",p,"key",input.requestKey());
        if(!existing.isEmpty()) {
            var r=existing.getFirst();
            if(number(r[0])!=tenant || number(r[1])!=actor || !Objects.equals(r[2],input.channel()) || !Objects.equals(r[3],input.content().trim()) || !Objects.equals(r[4],input.stage()) || !sameTime(time(r[5]),input.nextFollowUpAt())) throw conflict("请求编号已用于另一条跟进记录");
            return crm(actor,tenant,0,20);
        }
        var old=store.profile(p,tenant); version(old,input.version()); next(input.nextFollowUpAt());
        store.query("INSERT INTO partner_follow_ups(partner_id,tenant_id,request_key,channel,content,stage,next_follow_up_at,actor_user_id) VALUES (:p,:t,:key,:channel,:content,:stage,:next,:actor)","p",p,"t",tenant,"key",input.requestKey(),"channel",input.channel(),"content",input.content().trim(),"stage",input.stage(),"next",input.nextFollowUpAt(),"actor",actor).executeUpdate();
        store.save(p,tenant,actor,new PartnerCrmDtos.SaveProfile(old.contactName(),old.contactPhone(),old.industry(),old.region(),input.stage(),input.nextFollowUpAt(),old.note(),old.version()));
        return crm(actor,tenant,0,20);
    }
    @Transactional(readOnly=true)
    public List<PartnerCrmDtos.Due> due(Long actor) {
        var a=access(actor); var names=partners.tenantNames();
        return store.rows("SELECT tenant_id,contact_name,contact_phone,industry,region,stage,next_follow_up_at,note,version,updated_at FROM partner_merchant_profiles WHERE partner_id=:p AND next_follow_up_at IS NOT NULL AND next_follow_up_at<=:until ORDER BY next_follow_up_at,tenant_id","p",a.partner().id(),"until",Instant.now().plus(Duration.ofDays(7))).stream()
                .map(r -> new PartnerCrmDtos.Due(number(r[0]),names.get(number(r[0])),profile(Arrays.copyOfRange(r,1,10)))).toList();
    }
    private static void paging(int p,int s) { if(p<0 || p>1000 || s<1 || s>100) throw bad("分页参数无效"); }
    private static void version(PartnerCrmDtos.Profile p,long v) { if(p.version()!=v) throw conflict("商户档案已被其他成员更新，请刷新后重新提交"); }
    private static void next(Instant t) { if(t!=null && (t.isBefore(Instant.now().minus(Duration.ofDays(1))) || t.isAfter(Instant.now().plus(Duration.ofDays(730))))) throw bad("请填写有效的下次跟进时间"); }
    private static boolean sameTime(Instant a,Instant b) { return Objects.equals(a==null?null:a.truncatedTo(java.time.temporal.ChronoUnit.MICROS),b==null?null:b.truncatedTo(java.time.temporal.ChronoUnit.MICROS)); }
    private static BizException bad(String m) { return BizException.of(ErrorCode.BAD_REQUEST,m); }
    private static BizException conflict(String m) { return BizException.of(ErrorCode.CONFLICT,m); }
}
