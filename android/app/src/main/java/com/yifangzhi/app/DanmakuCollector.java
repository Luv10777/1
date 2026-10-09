package com.yifangzhi.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在一张不显示的网页里打开商家自己的直播间，读网页收到的弹幕。
 * 连接、签名都由抖音自己的网页完成，这里只把它收到的东西抄一份，所以抖音改签名算法不影响这里。
 * 网页不登录任何抖音账号。
 *
 * 做的事和桌面端的 desktop/src/danmakuCollector.js 一样，只是抄的办法不同：桌面端旁听网络，
 * 安卓上没有那种办法，改成在网页里放一小段脚本（assets/collector-hook.js）。
 *
 * 只能在主线程上用。
 */
final class DanmakuCollector {
    interface Listener {
        /** code 取 OPENING | ROOM_ONLINE | ROOM_OFFLINE | ROOM_ENDED | PAGE_FAILED */
        void onStatus(String code, boolean live, String text);

        void onChat(String id, String text);

        /** 收到一帧；读不懂的帧给 null。只用于统计。 */
        void onFrame(DouyinFrames.Frame frame);

        /** 排查用的一句话。 */
        void onNote(String text);

        /** 显示网页的那个进程被系统收走了，这个采集器不能再用，要换一个新的。 */
        void onRendererGone();
    }

    private static final String JS_OBJECT = "YifangzhiCollector";
    private static final Set<String> PAGE = Collections.singleton("https://live.douyin.com");
    // 打开网页后这么久还读不到直播间状态，就先当作没在播。
    private static final long WAIT_FOR_ROOM = 25_000;
    // 没在播时隔这么久重新打开一次网页，看看开播了没有。
    private static final long RECHECK = 30_000;

    private final Context context;
    private final String roomId;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger refused = new AtomicInteger();
    private final Runnable reopen = this::open;
    private final Runnable noAnswer = () -> offline("ROOM_OFFLINE", "没有读到直播间状态，稍后重试");

    private WebView web;
    private boolean stopped = true;
    private boolean live;
    /** 这一场的内部房间号。网页上还有推荐的其他直播间，只读这个直播间自己的连接。 */
    private String internalId = "";
    private boolean scriptSeen;
    private boolean socketSeen;
    private boolean pageSeen;

    DanmakuCollector(Context context, String roomId, Listener listener) {
        this.context = context.getApplicationContext();
        this.roomId = roomId;
        this.listener = listener;
    }

    /** 这台手机的系统浏览器内核够不够用；够用返回 null，不够用返回原因。 */
    static String unsupported() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return "这台手机的系统浏览器内核太旧，不能在网页自己的脚本之前放入脚本";
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return "这台手机的系统浏览器内核太旧，网页没法把数据交给软件";
        return null;
    }

    /** 那张网页。纯后台方式下它不挂在任何窗口上；悬浮窗方式下由调用方把它挂到悬浮窗里。 */
    View view() {
        return web;
    }

    /** 到现在为止没让网页下载的请求数（视频、图片、字体）。 */
    int refused() {
        return refused.get();
    }

    @SuppressLint("SetJavaScriptEnabled")
    void start() {
        if (!stopped) return;
        String reason = unsupported();
        if (reason != null) {
            listener.onStatus("PAGE_FAILED", false, reason);
            return;
        }
        stopped = false;
        web = new WebView(context);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(Browsing.desktopUserAgent(WebSettings.getDefaultUserAgent(context)));
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setBlockNetworkImage(true);
        settings.setLoadsImagesAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setGeolocationEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        // 默认情况下系统浏览器内核会在每个请求里带上软件的包名，告诉网站“这是从哪个 App 里打开的”。能关就关掉。
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, Collections.emptySet());
        }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        // 只有 live.douyin.com 自己的页面（不含它里面嵌的别的网页）能把数据交过来。
        WebViewCompat.addWebMessageListener(web, JS_OBJECT, PAGE, (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
            if (isMainFrame) receive(message.getData());
        });
        WebViewCompat.addDocumentStartJavaScript(web, hookScript(), PAGE);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });
        // 不挂在窗口上的网页没有大小，给它一个电脑屏幕的大小。
        web.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        web.layout(0, 0, 1280, 800);
        web.onResume();
        web.resumeTimers();
        open();
    }

    void stop() {
        if (stopped) return;
        stopped = true;
        main.removeCallbacks(reopen);
        main.removeCallbacks(noAnswer);
        WebView gone = web;
        web = null;
        if (gone != null) {
            gone.stopLoading();
            if (gone.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) gone.getParent()).removeView(gone);
            gone.destroy();
        }
    }

    private String hookScript() {
        try (InputStream in = context.getAssets().open("collector-hook.js")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int read; (read = in.read(chunk)) > 0; ) out.write(chunk, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("安装包里缺少 collector-hook.js", error);
        }
    }

    private void open() {
        if (stopped || web == null) return;
        main.removeCallbacks(reopen);
        main.removeCallbacks(noAnswer);
        internalId = "";
        live = false;
        listener.onStatus("OPENING", false, "正在打开直播间网页…");
        web.loadUrl("https://live.douyin.com/" + Uri.encode(roomId));
        main.postDelayed(noAnswer, WAIT_FOR_ROOM);
    }

    private void offline(String code, String text) {
        if (stopped) return;
        main.removeCallbacks(reopen);
        main.removeCallbacks(noAnswer);
        live = false;
        listener.onStatus(code, false, text);
        main.postDelayed(reopen, RECHECK);
    }

    private void online() {
        main.removeCallbacks(reopen);
        main.removeCallbacks(noAnswer);
        if (live) return;
        live = true;
        listener.onStatus("ROOM_ONLINE", true, "直播间已开播，正在接收弹幕");
    }

    /** 网页里的脚本交过来的一条消息。 */
    private void receive(String data) {
        if (stopped || data == null) return;
        JSONObject message;
        try {
            message = new JSONObject(data);
        } catch (JSONException error) {
            return;
        }
        switch (message.optString("kind")) {
            case "ready":
                if (!scriptSeen) listener.onNote("脚本已经在网页自己的脚本之前放进去了");
                scriptSeen = true;
                break;
            case "socket":
                if (!socketSeen) listener.onNote("网页建立了接收弹幕的连接");
                socketSeen = true;
                break;
            case "closed":
                if (live) listener.onNote("接收弹幕的连接断开了，等网页自己重连");
                break;
            case "room":
                looked(message.optString("body"));
                break;
            case "frame":
                frame(message.optString("room"), message.optString("data"));
                break;
            default:
                break;
        }
    }

    /** 网页查到了直播间状态。 */
    private void looked(String body) {
        RoomStatus status = RoomStatus.parse(body);
        if (status == null) return;
        internalId = status.id;
        if (status.live) online();
        else if (!live) offline("ROOM_OFFLINE", "直播间未开播");
    }

    private void frame(String room, String data) {
        if (!internalId.isEmpty() && !room.isEmpty() && !room.equals(internalId)) return;
        DouyinFrames.Frame frame;
        try {
            frame = DouyinFrames.read(Base64.decode(data, Base64.DEFAULT));
        } catch (DouyinFrames.Unreadable | IllegalArgumentException error) {
            // 读不懂的帧不影响后面的。
            listener.onFrame(null);
            return;
        }
        listener.onFrame(frame);
        for (DouyinFrames.Chat chat : frame.chats) {
            // 没等到状态查询的结果也算开播：弹幕已经在来了。
            online();
            listener.onChat(chat.id, chat.text);
        }
        if (frame.ended) offline("ROOM_ENDED", "直播间已下播");
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (request.isForMainFrame() || !Browsing.refuses(url.getHost(), url.getPath())) return null;
            refused.incrementAndGet();
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            Uri url = request.getUrl();
            String scheme = String.valueOf(url.getScheme());
            // 这张网页只该待在抖音自己的站点里；它想唤起 App 或跳去别处，都拦下并记一笔。
            if (!"https".equals(scheme)) {
                listener.onNote("网页想打开 " + scheme + ":// 的链接，已拦下");
                return true;
            }
            if (!Browsing.isDouyin(url.getHost())) {
                listener.onNote("网页想跳到 " + url.getHost() + "，已拦下");
                return true;
            }
            if (!"live.douyin.com".equals(url.getHost())) listener.onNote("网页跳到了 " + url.getHost() + url.getPath());
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (stopped || pageSeen) return;
            pageSeen = true;
            String title = view.getTitle() == null ? "" : view.getTitle();
            // 标题里带主播的名字，不记；只在它不像直播间时记下来，多半是被要求验证或登录了。
            // 网页刚打开时标题往往还没换上，显示的是网址本身，那不算。
            boolean named = !title.isEmpty() && !title.contains("douyin.com");
            listener.onNote(named && !title.contains("直播") ? "网页打开了，但看起来不是直播间：" + title : "直播间网页打开了");
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) offline("PAGE_FAILED", "直播间网页打不开（" + error.getDescription() + "）");
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            listener.onNote(detail.didCrash() ? "显示网页的进程崩溃了" : "显示网页的进程被系统收走了（多半是内存紧张）");
            main.post(listener::onRendererGone);
            return true;
        }
    }
}
