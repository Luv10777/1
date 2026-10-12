package com.wuyao.growth.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;

@Component @ConfigurationProperties(prefix="growth.billing.wechat") @Getter @Setter
public class WechatPayProperties {
    private String appId = "";
    private String merchantId = "";
    private String merchantSerial = "";
    private String privateKeyPath = "";
    private String apiV3Key = "";
    private String platformKeyId = "";
    private String platformPublicKeyPath = "";
    private String notifyUrl = "";
    public boolean configured() {
        return !appId.isBlank()&&!merchantId.isBlank()&&!merchantSerial.isBlank()&&apiV3Key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length==32
            &&!platformKeyId.isBlank()&&notifyUrl.startsWith("https://")&&!privateKeyPath.isBlank()&&!platformPublicKeyPath.isBlank()
            &&Files.isReadable(Path.of(privateKeyPath))&&Files.isReadable(Path.of(platformPublicKeyPath));
    }
}
