package com.yifangzhi.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 打开直播间网页时的两条规矩：以什么身份打开，哪些东西不下载。 */
final class Browsing {
    private static final Pattern CHROME = Pattern.compile("Chrome/(\\d+)");
    private static final Pattern STREAM = Pattern.compile("\\.(flv|m3u8|ts|mp4|m4s)$");
    private static final Pattern PICTURE_OR_FONT = Pattern.compile("\\.(png|jpe?g|gif|webp|avif|heic|ico|svg|woff2?|ttf|otf)$");
    // 直播的视频流、直播间里的图片各有自己的域名。
    private static final String[] MEDIA_SITES = {"douyinliving.com", "douyinpic.com"};
    private static final String[] DOUYIN_SITES = {"douyin.com", "amemv.com", "iesdouyin.com"};

    private Browsing() {}

    /**
     * 抖音对手机浏览器给的是“打开 App”的页面，带弹幕的直播间网页只给电脑浏览器。
     * 所以报成电脑上的 Chrome，版本号用手机里这个浏览器内核自己的，免得说的和实际的对不上。
     */
    static String desktopUserAgent(String phoneUserAgent) {
        Matcher match = CHROME.matcher(phoneUserAgent == null ? "" : phoneUserAgent);
        String major = match.find() ? match.group(1) : "124";
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + ".0.0.0 Safari/537.36";
    }

    private static boolean within(String host, String[] sites) {
        for (String site : sites) if (host.equals(site) || host.endsWith("." + site)) return true;
        return false;
    }

    /**
     * 不下载的东西：直播的视频、图片、字体。读弹幕用不着它们，不拦的话后台那张网页每小时要下几个 G。
     * 在电脑上量过（2026-10-09）：不拦时 50 秒下了 66 MB，拦掉之后 60 秒 9 MB，其中大部分是打开网页时一次性下载的脚本；弹幕一条不少。
     */
    static boolean refuses(String host, String path) {
        String site = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String file = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return within(site, MEDIA_SITES) || STREAM.matcher(file).find() || PICTURE_OR_FONT.matcher(file).find();
    }

    /** 这张网页只该待在抖音自己的站点里。 */
    static boolean isDouyin(String host) {
        return within(host == null ? "" : host.toLowerCase(Locale.ROOT), DOUYIN_SITES);
    }
}
