package com.wuyao.growth.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** No prices or exchange rates are assumed. Every sellable rate has an evidence URL and expiry. */
@Component @ConfigurationProperties(prefix="growth.billing.video") @Getter @Setter
public class VideoPricingProperties {
    private List<Rate> rates = List.of();
    @Getter @Setter public static class Rate {
        private String providerBaseUrl;
        private String providerModel;
        private String resolution;
        private String videoInput;
        private BigDecimal usdPerMillionTokens;
        private BigDecimal cnyPerUsd;
        private BigDecimal sellingMultiplier;
        // Verified provider token formula and maximum pixel area for this resolution.
        // Zero/absent values disable the rate; aspect ratios must fit this verified upper bound.
        private long maxOutputPixels;
        private int framesPerSecond;
        private int tokenAreaDivisor;
        private Boolean referenceVideoBilled;
        private String sourceUrl;
        private Instant verifiedAt;
        private Instant validUntil;
    }
}
