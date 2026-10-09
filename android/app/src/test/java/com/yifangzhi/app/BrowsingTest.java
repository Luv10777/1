package com.yifangzhi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrowsingTest {
    @Test
    public void thePageIsAskedForAsADesktopChromeOfThePhonesOwnEngineVersion() {
        String phone = "Mozilla/5.0 (Linux; Android 14; 23013RK75C Build/UKQ1.230804.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.188 Mobile Safari/537.36";
        String desktop = Browsing.desktopUserAgent(phone);
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36", desktop);
        for (String phoneOnly : new String[] {"Android", "Mobile", "wv", "Version/4.0", "23013RK75C"}) assertFalse(phoneOnly, desktop.contains(phoneOnly));
        assertTrue(Browsing.desktopUserAgent(null).contains("Chrome/124.0.0.0"));
        assertTrue(Browsing.desktopUserAgent("something else entirely").contains("Windows NT 10.0"));
    }

    @Test
    public void theLiveVideoPicturesAndFontsAreNotDownloaded() {
        assertTrue(Browsing.refuses("pull-flv-l11.douyinliving.com", "/stage/stream-123_or4.flv"));
        assertTrue(Browsing.refuses("PULL-HLS-L6.DOUYINLIVING.COM", "/third/stream-1/index.M3U8"));
        assertTrue(Browsing.refuses("p3-webcast.douyinpic.com", "/img/webcast/abc~tplv-obj.image"));
        assertTrue(Browsing.refuses("lf-douyin-pc-web.douyinstatic.com", "/obj/douyin-pc-web/uc/login/logo.png"));
        assertTrue(Browsing.refuses("lf-cdn-tos.bytescm.com", "/obj/static/fonts/DouyinSans.woff2"));
        assertTrue(Browsing.refuses("example.com", "/a/b.JPEG"));
    }

    @Test
    public void whatThePageNeedsToRunAndToReceiveCommentsIsLetThrough() {
        assertFalse(Browsing.refuses("live.douyin.com", "/850050208045"));
        assertFalse(Browsing.refuses("live.douyin.com", "/webcast/room/web/enter/"));
        assertFalse(Browsing.refuses("webcast100-ws-web-lq.douyin.com", "/webcast/im/push/v2/"));
        assertFalse(Browsing.refuses("lf-douyin-pc-web.douyinstatic.com", "/obj/douyin-pc-web/ies/douyin_web/chunks/main.js"));
        assertFalse(Browsing.refuses("lf-c-flwb.bytetos.com", "/obj/rc-client-security/c-webmssdk/1.0.0.20/webmssdk.es5.js"));
        // A site that merely contains the name is not the media site.
        assertFalse(Browsing.refuses("notdouyinliving.com", "/x"));
        assertFalse(Browsing.refuses(null, null));
    }

    @Test
    public void onlyDouyinsOwnSitesCountAsDouyin() {
        assertTrue(Browsing.isDouyin("live.douyin.com"));
        assertTrue(Browsing.isDouyin("www.iesdouyin.com"));
        assertTrue(Browsing.isDouyin("douyin.com"));
        assertFalse(Browsing.isDouyin("douyin.com.evil.test"));
        assertFalse(Browsing.isDouyin("evildouyin.com"));
        assertFalse(Browsing.isDouyin(null));
    }
}
