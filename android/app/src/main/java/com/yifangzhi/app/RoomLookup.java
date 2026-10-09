package com.yifangzhi.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商家填进来的东西 → 网页端直播间号（live.douyin.com/ 后面那一串）。
 * 可以直接填直播间号或直播间网址，也可以粘贴抖音 App 里“分享 → 复制链接”的整段内容。
 *
 * 和桌面端的 desktop/src/roomLookup.js 是同一套规则。
 */
final class RoomLookup {
    private static final Pattern ROOM_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final Pattern ROOM_URL = Pattern.compile("live\\.douyin\\.com/([A-Za-z0-9_.-]+)");
    private static final Pattern SHARE_LINK = Pattern.compile("https?://v\\.douyin\\.com/([A-Za-z0-9_-]{4,32})/?");
    // 短链只会跳到抖音自己的落地页；跳去别处就不跟。
    private static final Pattern LANDING_HOST = Pattern.compile("(^|\\.)(douyin\\.com|amemv\\.com|iesdouyin\\.com)$");
    private static final Pattern WEB_RID = Pattern.compile("\\\\?\"webRid\\\\?\"\\s*:\\s*\\\\?\"([A-Za-z0-9_.-]{1,64})\\\\?\"");
    private static final int MAX_HOPS = 5;
    private static final int TIMEOUT = 10000;
    private static final int MAX_PAGE = 2 * 1024 * 1024;
    // 落地页只对手机浏览器返回带直播间信息的页面。
    private static final String MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    private RoomLookup() {}

    /** 填得不对、或者抖音那边打不开时的原因，可以直接给用户看。 */
    static final class Failed extends Exception {
        Failed(String message) {
            super(message);
        }
    }

    /** 接受直播间号，或整条 live.douyin.com 网址。 */
    static String normalizeRoomId(String input) throws Failed {
        String value = input == null ? "" : input.trim();
        Matcher fromUrl = ROOM_URL.matcher(value);
        String id = fromUrl.find() ? fromUrl.group(1) : value;
        if (!ROOM_ID.matcher(id).matches()) throw new Failed("没有认出直播间：请粘贴抖音 App 里“分享 → 复制链接”得到的整段内容");
        return id;
    }

    /** 复制出来的往往是一段话，短链夹在中间。取出短链本身；没有就返回 null。 */
    static String extractShareLink(String text) {
        Matcher match = SHARE_LINK.matcher(text == null ? "" : text);
        return match.find() ? "https://v.douyin.com/" + match.group(1) + "/" : null;
    }

    /** 落地页里内嵌的直播间信息带有网页端直播间号（webRid）。 */
    static String extractWebRid(String html) {
        Matcher match = WEB_RID.matcher(html == null ? "" : html);
        return match.find() ? match.group(1) : null;
    }

    /** 跳转的下一站只能是抖音自己的 https 地址。 */
    static boolean isLanding(URL url) {
        return "https".equals(url.getProtocol()) && LANDING_HOST.matcher(url.getHost().toLowerCase()).find();
    }

    /** 像手机浏览器那样打开分享短链，返回网页端直播间号。要联网，不能在主线程上调用。 */
    static String resolveShareLink(String text) throws Failed {
        String link = extractShareLink(text);
        if (link == null) throw new Failed("这不是抖音的分享链接");
        try {
            URL url = new URL(link);
            for (int hop = 0; hop < MAX_HOPS; hop++) {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                try {
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(TIMEOUT);
                    connection.setReadTimeout(TIMEOUT);
                    connection.setRequestProperty("User-Agent", MOBILE_UA);
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location");
                        URL next = new URL(url, location == null ? "" : location);
                        if (!isLanding(next)) throw new Failed("分享链接跳到了抖音以外的地址，没有继续打开");
                        url = next;
                        continue;
                    }
                    if (status < 200 || status >= 300) throw new Failed("抖音分享页打不开（" + status + "）");
                    String id = extractWebRid(read(connection.getInputStream()));
                    if (id == null) throw new Failed("分享页里没有找到直播间：可能不是直播间的分享链接，或抖音改了页面");
                    return id;
                } finally {
                    connection.disconnect();
                }
            }
            throw new Failed("分享链接跳转次数过多");
        } catch (IOException error) {
            throw new Failed("抖音分享页打不开，请检查网络后重试");
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int read; out.size() < MAX_PAGE && (read = in.read(chunk)) > 0; ) out.write(chunk, 0, read);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 分享链接先换成直播间号；其余按直播间号或直播间网址处理。要联网，不能在主线程上调用。 */
    static String resolve(String input) throws Failed {
        return normalizeRoomId(extractShareLink(input) != null ? resolveShareLink(input) : input);
    }
}
