package com.wuyao.growth.video;

import com.wuyao.growth.common.task.NonRetryableTaskException;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Direct public HTTPS media links only; resolved addresses are checked at connection time. */
@Component
public class VideoAnalysisUrlImporter {
    public URI validate(String value) {
        URI uri = VideoUrlSecurity.checkedHttps(value);
        if (uri.getPort() != -1 && uri.getPort() != 443) throw new IllegalArgumentException("视频链接必须使用 HTTPS 默认端口");
        return uri;
    }

    public void download(String value, Path target, long maxBytes) {
        try {
            URI uri = validate(value);
            var dns = new SystemDefaultDnsResolver() {
                @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                    var addresses = super.resolve(host);
                    for (var address : addresses) {
                        String ip = address.getHostAddress().toLowerCase(Locale.ROOT);
                        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                                || address.isSiteLocalAddress() || address.isMulticastAddress()
                                || ip.startsWith("fc") || ip.startsWith("fd")) throw new UnknownHostException("受保护的网络地址");
                    }
                    return addresses;
                }
            };
            var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns).build();
            try (var http = HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries()
                    .disableRedirectHandling().build()) {
                var request = new HttpGet(uri);
                request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(5))
                        .setConnectTimeout(Timeout.ofSeconds(10)).setResponseTimeout(Timeout.ofSeconds(15)).build());
                http.execute(request, response -> {
                    if (response.getCode() != 200 || response.getEntity() == null) reject("无法读取视频直链，请下载视频后使用本地上传");
                    if (response.getEntity().getContentLength() > maxBytes) reject("视频链接对应的文件超过大小限制");
                    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
                    try (var input = response.getEntity().getContent(); var output = Files.newOutputStream(target)) {
                        byte[] buffer = new byte[64 * 1024];
                        long total = 0;
                        int length;
                        while ((length = input.read(buffer)) != -1) {
                            total += length;
                            if (total > maxBytes) reject("视频链接对应的文件超过大小限制");
                            if (System.nanoTime() > deadline) reject("视频链接下载超时，请使用本地上传");
                            output.write(buffer, 0, length);
                        }
                        if (total <= 0 || (response.getEntity().getContentLength() >= 0
                                && total != response.getEntity().getContentLength())) reject("视频链接返回空文件或不完整文件");
                    }
                    return null;
                });
            }
        } catch (NonRetryableTaskException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            reject("视频直链无法访问，只支持公开 HTTPS 视频文件；也可下载后上传");
        }
    }
    private void reject(String message) { throw new NonRetryableTaskException("VIDEO_ANALYSIS_URL", message, null); }
}
