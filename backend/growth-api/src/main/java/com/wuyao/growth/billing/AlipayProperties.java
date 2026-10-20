package com.wuyao.growth.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;

/** Alipay open platform in public-key mode. The sandbox only differs by gateway and keys. */
@Component @ConfigurationProperties(prefix="growth.billing.alipay") @Getter @Setter
public class AlipayProperties {
    private String gateway = "https://openapi.alipay.com/gateway.do";
    private String appId = "";
    private String sellerId = "";
    private String privateKeyPath = "";
    private String alipayPublicKeyPath = "";
    private String notifyUrl = "";
    public boolean configured() {
        return gateway.startsWith("https://")&&!appId.isBlank()&&!sellerId.isBlank()&&notifyUrl.startsWith("https://")
            &&!privateKeyPath.isBlank()&&!alipayPublicKeyPath.isBlank()
            &&Files.isReadable(Path.of(privateKeyPath))&&Files.isReadable(Path.of(alipayPublicKeyPath));
    }
}
