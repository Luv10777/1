package com.wuyao.growth.partner;

import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

/** Only called inside PartnerCrmService transactions, after partner and BD authorization. */
@Repository @RequiredArgsConstructor
class PartnerCrmStore {
    private final EntityManager em;
    Query query(String sql,Object... args) {
        var q=em.createNativeQuery(sql);
        for(int i=0;i<args.length;i+=2) q.setParameter((String)args[i],args[i+1] instanceof Instant t?Timestamp.from(t):args[i+1]);
        return q;
    }
    List<Object[]> rows(String sql,Object... args) {
        return query(sql,args).getResultList().stream().map(v -> (Object[])v).toList();
    }
    void lockTenant(Long tenant) { query("SELECT tenant_id FROM partner_tenants WHERE tenant_id=:t FOR UPDATE","t",tenant).getResultList(); }
    PartnerCrmDtos.Profile profile(Long partner,Long tenant) {
        var rows=rows("SELECT contact_name,contact_phone,industry,region,stage,next_follow_up_at,note,version,updated_at FROM partner_merchant_profiles WHERE partner_id=:p AND tenant_id=:t","p",partner,"t",tenant);
        return rows.isEmpty()?new PartnerCrmDtos.Profile("","","","","ACTIVE",null,"",0,null):profile(rows.getFirst());
    }
    static PartnerCrmDtos.Profile profile(Object[] r) { return new PartnerCrmDtos.Profile((String)r[0],(String)r[1],(String)r[2],(String)r[3],(String)r[4],time(r[5]),(String)r[6],number(r[7]),time(r[8])); }
    void save(Long p,Long t,Long actor,PartnerCrmDtos.SaveProfile in) {
        query("INSERT INTO partner_merchant_profiles(partner_id,tenant_id,contact_name,contact_phone,industry,region,stage,next_follow_up_at,note,version,updated_by) VALUES (:p,:t,:name,:phone,:industry,:region,:stage,:next,:note,1,:actor) ON CONFLICT (partner_id,tenant_id) DO UPDATE SET contact_name=:name,contact_phone=:phone,industry=:industry,region=:region,stage=:stage,next_follow_up_at=:next,note=:note,version=partner_merchant_profiles.version+1,updated_by=:actor,updated_at=now()",
                "p",p,"t",t,"name",text(in.contactName()),"phone",text(in.contactPhone()),"industry",text(in.industry()),"region",text(in.region()),"stage",in.stage(),"next",in.nextFollowUpAt(),"note",text(in.note()),"actor",actor).executeUpdate();
    }
    List<Object[]> followUps(Long p,Long t,int page,int size) {
        return query("SELECT f.id,f.channel,f.content,f.stage,f.next_follow_up_at,f.actor_user_id,u.name,f.created_at FROM partner_follow_ups f JOIN users u ON u.id=f.actor_user_id WHERE f.partner_id=:p AND f.tenant_id=:t ORDER BY f.id DESC","p",p,"t",t)
                .setFirstResult(page*size).setMaxResults(size).getResultList().stream().map(v -> (Object[])v).toList();
    }
    static PartnerCrmDtos.FollowUp followUp(Object[] r) { return new PartnerCrmDtos.FollowUp(number(r[0]),(String)r[1],(String)r[2],(String)r[3],time(r[4]),number(r[5]),(String)r[6],time(r[7])); }
    static long number(Object v) { return ((Number)v).longValue(); }
    static Instant time(Object v) { return v==null?null:v instanceof Instant i?i:v instanceof Timestamp t?t.toInstant():((OffsetDateTime)v).toInstant(); }
    static String text(String v) { return v==null?"":v.trim(); }
}
