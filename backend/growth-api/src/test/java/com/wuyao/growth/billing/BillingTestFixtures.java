package com.wuyao.growth.billing;
import java.math.BigDecimal;
import java.time.Instant;
/** Synthetic values confined to tests; never used as production commercial rates. */
public final class BillingTestFixtures {
    private BillingTestFixtures() {}
    public static VideoPricingService.Quote quote() {
        return new VideoPricingService.Quote(true,10,"test", "https://api.onlyrouter.ai", "test-model", "480p", "t2v",1000,0,
                BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,409920,24,1024,"https://test.invalid/rate",Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600),"a".repeat(64));
    }
}
