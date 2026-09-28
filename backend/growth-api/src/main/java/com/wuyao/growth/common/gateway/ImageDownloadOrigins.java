package com.wuyao.growth.common.gateway;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Origin rules for provider image URLs. A wildcard matches HTTPS subdomains only. */
public final class ImageDownloadOrigins {
    private ImageDownloadOrigins() {}

    public static URI checkedUri(String value) {
        URI uri = URI.create(value);
        if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
            || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("图片下载地址格式无效");
        return uri;
    }

    public static boolean allows(URI uri, List<String> rules) {
        if (rules == null) return false;
        for (String configured : rules) {
            if (configured == null || configured.isBlank()) continue;
            String rule = configured.trim();
            if (rule.startsWith("*.") && rule.indexOf('*', 1) < 0) {
                String suffix = rule.substring(1).toLowerCase(Locale.ROOT);
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                if ("https".equalsIgnoreCase(uri.getScheme()) && port(uri) == 443
                    && host.length() > suffix.length() && host.endsWith(suffix)) return true;
                continue;
            }
            URI origin = checkedUri(rule);
            if (origin.getRawQuery() == null && (origin.getPath() == null || origin.getPath().isEmpty()
                || origin.getPath().equals("/")) && uri.getScheme().equalsIgnoreCase(origin.getScheme())
                && uri.getHost().equalsIgnoreCase(origin.getHost()) && port(uri) == port(origin)) return true;
        }
        return false;
    }

    private static int port(URI uri) {
        return uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }
}
