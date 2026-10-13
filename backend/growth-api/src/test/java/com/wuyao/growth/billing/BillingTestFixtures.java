package com.wuyao.growth.billing;
import java.math.BigDecimal;
import java.time.Instant;
/** Synthetic values confined to tests; never used as production commercial rates. */
public final class BillingTestFixtures {
    private BillingTestFixtures() {}
    public static void activateMembership(org.springframework.jdbc.core.JdbcTemplate owner, Long... tenants) {
        String plan = """
            {"id":1,"code":"TEST","name":"Test member","description":"test-only",
             "priceMinor":100,"durationDays":1,"giftCredits":0,"imageEnabled":true,"videoEnabled":true}
            """;
        for (Long tenant : tenants) owner.update("INSERT INTO billing_accounts(tenant_id,plan_snapshot,member_until) "
                + "VALUES (?,?::jsonb,now()+interval '1 day')", tenant, plan);
    }
    public static VideoPricingService.Quote quote() {
        return new VideoPricingService.Quote(true,10,"test", "https://api.onlyrouter.ai", "test-model", "480p", "t2v",1000,0,
                BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,409920,24,1024,"https://test.invalid/rate",Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600),"a".repeat(64));
    }
}
