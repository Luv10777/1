package com.wuyao.growth.community;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** Explicit allowlist for platform operators. Merchant OWNER status is not enough. */
@Component
@ConfigurationProperties(prefix = "growth.platform")
@Getter
@Setter
public class PlatformProperties {
    private List<Long> operatorUserIds = List.of();
}
