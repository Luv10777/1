package com.wuyao.growth.video;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/** URL checks shared by provider redirects and the durable video downloader. */
final class VideoUrlSecurity {
    private VideoUrlSecurity() {}

    static URI checkedHttps(String value) {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("供应商视频 URL 格式无效", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException("供应商视频 URL 必须使用 HTTPS");
        }
        rejectPrivateHost(uri.getHost());
        return uri;
    }

    static void rejectPrivateHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.equals("0.0.0.0")
                || normalized.equals("::") || normalized.equals("::1")
                || normalized.startsWith("127.") || normalized.startsWith("169.254.")
                || normalized.startsWith("10.") || normalized.startsWith("192.168.")) {
            throw new IllegalArgumentException("供应商视频 URL 指向受保护的网络地址");
        }
        if (normalized.indexOf(':') >= 0 && (normalized.startsWith("fc") || normalized.startsWith("fd")
                || normalized.startsWith("fe80:"))) {
            throw new IllegalArgumentException("供应商视频 URL 指向受保护的网络地址");
        }
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("供应商视频 URL 的主机无法解析", e);
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || address.getHostAddress().toLowerCase(Locale.ROOT).startsWith("fc")
                    || address.getHostAddress().toLowerCase(Locale.ROOT).startsWith("fd")) {
                throw new IllegalArgumentException("供应商视频 URL 指向受保护的网络地址");
            }
        }
    }
}
