package com.yifangzhi.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 显示一方志控制台的那张网页，以及它和软件之间的桥。
 * 网页就是网站上的直播页（页面发现自己开在软件里，会收起平台的其余导航），所以页面上的改动发布网站后就生效，
 * 不用重装软件。
 *
 * 这张网页归后台服务所有，不归界面：切到抖音之后界面可能被系统收走，网页要接着运行、接着出声。
 *
 * 只能在主线程上用。
 */
final class ConsoleWeb {
    /** 页面请软件做的事。 */
    interface Host {
        void startCollector(String roomId);

        void stopCollector();

        void note(String text);

        /** 显示控制台的那个进程没了，这张网页不能再用，要换一张新的。 */
        void consoleGone();
    }

    private static final String TAG = "YfzConsole";
    private static final String JS_OBJECT = "YifangzhiNative";
    private static final String LIVE_PATH = "/digital-human";

    private final Context app;
    private final MutableContextWrapper context;
    private final Host host;
    private final String origin;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService lookups = Executors.newSingleThreadExecutor();
    private WebView web;
    /** 页面最近一次说话时留下的回话通道；软件主动往页面送东西要靠它。页面刷新后要等它再打一次招呼。 */
    private JavaScriptReplyProxy page;

    ConsoleWeb(Context context, Host host) {
        this.app = context.getApplicationContext();
        this.context = new MutableContextWrapper(app);
        this.host = host;
        Uri console = Uri.parse(BuildConfig.CONSOLE_URL);
        this.origin = console.getScheme() + "://" + console.getAuthority();
    }

    WebView view() {
        return web;
    }

    @SuppressLint("SetJavaScriptEnabled")
    void start() {
        if (web != null) return;
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        web = new WebView(context);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // AI 的声音要在没人点屏幕的时候自己播出来：抖音在前台时，这张网页根本点不到。
        settings.setMediaPlaybackRequiresUserGesture(false);
        // 标识里只加英文：后端在建立播报的长连接时会拒绝带非英文字符的标识（桌面端 0.1.0 栽过）。
        settings.setUserAgentString(settings.getUserAgentString() + " YifangzhiAndroid/" + BuildConfig.VERSION_NAME);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setTextZoom(100);

        Set<String> console = Collections.singleton(origin);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // 只有控制台自己的页面（不含它里面嵌的别的网页）可以使用软件的能力。
            WebViewCompat.addWebMessageListener(web, JS_OBJECT, console, (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                if (isMainFrame) asked(message.getData(), replyProxy);
            });
            WebViewCompat.addDocumentStartJavaScript(web, asset("console-bridge.js"), console);
        } else {
            host.note("这台手机的系统浏览器内核太旧，页面用不了软件读弹幕的能力");
        }
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.loadUrl(origin + LIVE_PATH);
    }

    /** 网页现在挂在哪个界面上；不挂在界面上时传 null。弹出输入法、选字这些要用到界面。 */
    void shownIn(Context activity) {
        context.setBaseContext(activity == null ? app : activity);
    }

    void detach() {
        if (web != null && web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
    }

    boolean goBack() {
        if (web == null || !web.canGoBack()) return false;
        web.goBack();
        return true;
    }

    void destroy() {
        lookups.shutdownNow();
        page = null;
        if (web == null) return;
        detach();
        web.stopLoading();
        web.destroy();
        web = null;
    }

    /** 把读到的直播间状态或弹幕送给页面。页面还没打招呼（刚刷新）时送不出去，丢掉。 */
    void danmaku(String wire) {
        try {
            tell(new JSONObject().put("kind", "danmaku").put("data", wire));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void tell(JSONObject message) {
        if (page == null) return;
        try {
            page.postMessage(message.toString());
        } catch (RuntimeException gone) {
            // 那一页已经不在了。
            page = null;
        }
    }

    private void answer(JavaScriptReplyProxy to, int id, Object value) {
        try {
            to.postMessage(new JSONObject().put("kind", "answer").put("id", id).put("ok", true).put("value", value == null ? JSONObject.NULL : value).toString());
        } catch (JSONException | RuntimeException ignored) {
            // 问的那一页已经不在了。
        }
    }

    /** 出错时把原因原样带回页面，页面直接显示给用户。 */
    private void refuse(JavaScriptReplyProxy to, int id, String reason) {
        try {
            to.postMessage(new JSONObject().put("kind", "answer").put("id", id).put("ok", false).put("message", reason).toString());
        } catch (JSONException | RuntimeException ignored) {
            // 同上。
        }
    }

    /** 页面请软件做一件事。 */
    private void asked(String data, JavaScriptReplyProxy from) {
        if (web == null || data == null) return;
        page = from;
        int id;
        String method;
        String first;
        try {
            JSONObject request = new JSONObject(data);
            id = request.getInt("id");
            method = request.getString("method");
            JSONArray args = request.optJSONArray("args");
            first = args == null || args.length() == 0 ? "" : args.optString(0, "");
        } catch (JSONException malformed) {
            return;
        }
        switch (method) {
            case "hello":
            case "link.take":
                // 还没有做“被网页唤起时带上门店”，所以这里总是没有。
                answer(from, id, null);
                break;
            case "danmaku.resolveRoom":
                // 粘贴的是分享链接时要去打开它才知道是哪个直播间，得联网，不能占着主线程。
                lookups.execute(() -> {
                    try {
                        String room = RoomLookup.resolve(first);
                        main.post(() -> answer(from, id, room));
                    } catch (RoomLookup.Failed failed) {
                        main.post(() -> refuse(from, id, failed.getMessage()));
                    }
                });
                break;
            case "danmaku.start":
                try {
                    host.startCollector(RoomLookup.normalizeRoomId(first));
                    answer(from, id, null);
                } catch (RoomLookup.Failed failed) {
                    refuse(from, id, failed.getMessage());
                }
                break;
            case "danmaku.stop":
                host.stopCollector();
                answer(from, id, null);
                break;
            default:
                refuse(from, id, "这个版本的软件还不支持这项功能，请更新软件");
                break;
        }
    }

    private String asset(String name) {
        try (InputStream in = app.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int read; (read = in.read(chunk)) > 0; ) out.write(chunk, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("安装包里缺少 " + name, error);
        }
    }

    private boolean isConsole(Uri url) {
        return origin.equals(url.getScheme() + "://" + url.getAuthority());
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (isConsole(url)) return false;
            // 控制台以外的链接交给系统浏览器，这张网页本身不离开控制台。
            if (request.isForMainFrame() && ("https".equals(url.getScheme()) || "http".equals(url.getScheme()))) {
                try {
                    context.startActivity(new Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException noBrowser) {
                    host.note("没有能打开这个链接的软件：" + url.getHost());
                }
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            // 换了一页：上一页留下的回话通道不能再用，上一页开着的采集也不该继续。
            page = null;
            host.stopCollector();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            host.note("控制台打不开：" + error.getDescription());
            String retry = origin + LIVE_PATH;
            view.loadDataWithBaseURL(null, "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<body style='font-family:sans-serif;padding:32px 24px;color:#333;line-height:1.8'>"
                    + "<h3>打不开一方志</h3><p>请检查网络后重试。</p><p style='color:#888;font-size:13px'>" + error.getDescription() + "</p>"
                    + "<p><a href='" + retry + "' style='display:inline-block;padding:10px 20px;background:#b5503c;color:#fff;border-radius:6px;text-decoration:none'>重试</a></p>",
                    "text/html", "utf-8", null);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            host.note(detail.didCrash() ? "显示控制台的进程崩溃了" : "显示控制台的进程被系统收走了（多半是内存紧张）");
            main.post(host::consoleGone);
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            // 录声音样本这类事在网页版里做；手机软件只管开播。
            request.deny();
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            if (BuildConfig.DEBUG && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w(TAG, message.message() + " @" + message.sourceId() + ":" + message.lineNumber());
            return true;
        }
    }
}
