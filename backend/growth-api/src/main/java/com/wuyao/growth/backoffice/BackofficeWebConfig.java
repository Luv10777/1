package com.wuyao.growth.backoffice;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.web.*;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import com.wuyao.growth.partner.PartnerService;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;

/** Default-deny platform route policy and common append-only auditing for both back offices. */
@Configuration @RequiredArgsConstructor @Slf4j
public class BackofficeWebConfig implements WebMvcConfigurer {
    private final BackofficeAccess access;
    private final BackofficeAudit audit;
    private final PlatformAccountDirectory directory;
    private final PartnerService partners;

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                res.setHeader("Cache-Control", "no-store");
                AuthPrincipal me = principal();
                if (me==null) throw BizException.of(ErrorCode.UNAUTHORIZED, "请先登录");
                req.setAttribute("backoffice.actor", me.userId());
                String path=req.getRequestURI();
                try {
                    if(path.startsWith("/api/partner/")) {
                        req.setAttribute("backoffice.partner", partners.auditPartnerId(me.userId()));
                    } else if(!path.equals("/api/platform/access")) {
                        Set<String> required=permissions(path,req.getMethod());
                        if(required.isEmpty() || Collections.disjoint(access.access(me.userId()).permissions(),required))
                            throw BizException.of(ErrorCode.FORBIDDEN, "没有此项后台操作权限");
                    }
                } catch (BizException denied) { record(req,403); throw denied; }
                return true;
            }
            @Override public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
                record(req,res.getStatus());
            }
        }).addPathPatterns("/api/platform/**","/api/billing/operations/**","/api/partner/**");
    }
    private void record(HttpServletRequest req,int status) {
        if(Set.of("GET","HEAD","OPTIONS").contains(req.getMethod()) || Boolean.TRUE.equals(req.getAttribute("backoffice.audited"))) return;
        Long actor=(Long)req.getAttribute("backoffice.actor"); if(actor==null) return;
        try {
            String path=req.getRequestURI(),detail=(String)req.getAttribute("backoffice.action");
            audit.append(actor,directory.user(actor).name(),(Long)req.getAttribute("backoffice.partner"),req.getMethod(),path,detail==null?action(path):detail,status);
            req.setAttribute("backoffice.audited",true);
        } catch(RuntimeException failure) { log.error("Backoffice audit persistence failed for {} {}",req.getMethod(),req.getRequestURI(),failure); }
    }
    static Set<String> permissions(String p, String method) {
        if(p.startsWith("/api/billing/operations")) return Set.of("FINANCE");
        if(p.startsWith("/api/platform/security/")) return Set.of("SECURITY");
        if(p.startsWith("/api/platform/partners")) return Set.of("PARTNERS");
        if(p.startsWith("/api/platform/referral")) return Set.of("REFERRAL");
        if(p.startsWith("/api/platform/users/") && (p.endsWith("/credits") || p.endsWith("/membership"))) return Set.of("USER_BILLING");
        if(p.startsWith("/api/platform/users") || p.contains("/identities/") || p.equals("/api/platform/identities")) return Set.of("USERS");
        if(p.contains("/alliance/")) {
            if(p.contains("/offers") || p.contains("/disputes") || p.endsWith("/dispute-resolution") || p.endsWith("/alliance/summary")
                    || ("GET".equals(method) && p.endsWith("/alliance/accounts")) || p.endsWith("/settlement-account/review")) return Set.of("REVIEW","ALLIANCE");
            return Set.of("ALLIANCE");
        }
        if(p.equals("/api/platform/summary") || p.equals("/api/platform/information") || p.contains("/information/")) return Set.of("REVIEW");
        if(p.equals("/api/platform/merchants") || p.endsWith("/reveal") && p.contains("/merchants/")) return Set.of("REVIEW","USERS");
        if(p.contains("/merchants/") && p.endsWith("/review")) return Set.of("REVIEW");
        if(p.contains("/audit/")) return p.contains("/IDENTITY/") || p.contains("/USER/") ? Set.of("USERS") : Set.of("REVIEW","USERS");
        return Set.of();
    }
    private static AuthPrincipal principal() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.getPrincipal() instanceof AuthPrincipal p?p:null;
    }
    private static String action(String p) {
        if(p.contains("/security/roles")) return "调整管理员角色";
        if(p.contains("/follow-ups")) return "记录商户跟进";
        if(p.endsWith("/profile")) return "更新商户档案";
        if(p.contains("/assignments/") || p.endsWith("/bd")) return "调整商户归属";
        if(p.endsWith("/credits")) return "分配烟火";
        if(p.contains("/codes") || p.contains("/redeem-codes")) return "管理兑换码";
        if(p.endsWith("/confirm")) return "确认订单收款";
        if(p.contains("/orders/")) return "处理会员或充值订单";
        if(p.endsWith("/reveal")) return "查阅完整资料";
        if(p.contains("/review") || p.endsWith("/status")) return "审核或调整状态";
        if(p.contains("/statements")) return "处理联盟对账单";
        if(p.contains("/referral")) return "处理返利或提现";
        if(p.endsWith("/renewals")) return "续期商户会员";
        if(p.contains("/team") || p.contains("/members")) return "调整服务商团队";
        if(p.contains("/partners")) return "管理服务商";
        if(p.contains("/users")) return "处理用户账号";
        if(p.contains("/plans")) return "管理会员套餐";
        return "后台业务操作";
    }
}
