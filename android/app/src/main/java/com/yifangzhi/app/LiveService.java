package com.yifangzhi.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * 软件的后台部分。两张网页都归它所有：显示控制台的那张（出 AI 的声音），和读弹幕用的那张不显示的抖音直播间网页。
 *
 * 一台手机开播时，抖音在前台，一方志在后台。界面那时可能被系统收走，所以网页不能跟着界面走；
 * 它们放在这里，界面回来时把控制台那张借去显示，离开时还回来。
 *
 * 系统会放慢、暂停“看不见的网页”。有悬浮窗权限时，网页不在界面上的时候就放进一个盖在其他软件上面的小窗口
 * （网页本身只占一个像素，旁边是一个小标记），在系统眼里它一直是“正在显示”的。验证版用这个办法在后台
 * 连着跑过十分钟。没有这项权限时网页照样在后台运行，只是更可能被放慢。
 */
public final class LiveService extends Service implements DanmakuCollector.Listener, ConsoleWeb.Host {
    static final String ACTION_QUIT = "com.yifangzhi.app.QUIT";

    private static final String CHANNEL = "live";
    private static final int NOTIFICATION = 2;

    /** 界面拿到它，就能找到这个服务。 */
    final class Link extends Binder {
        LiveService service() {
            return LiveService.this;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Link link = new Link();
    private ProbeState log;
    private ConsoleWeb console;
    private DanmakuCollector collector;
    private String room = "";
    private WeakReference<Activity> screen = new WeakReference<>(null);
    private boolean shown;
    private View overlay;
    private TextView badge;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String status = "";
    private int chats;
    private boolean quitting;

    @Override
    public void onCreate() {
        super.onCreate();
        log = ProbeState.of(this);
        console = new ConsoleWeb(this, this);
        console.start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return link;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_QUIT.equals(intent.getAction())) {
            quit();
            return START_NOT_STICKY;
        }
        showNotification();
        // 被系统关掉后不自己再起来：起来了也只是一张没人点“开始播报”的网页，不如等用户重新打开。
        return START_NOT_STICKY;
    }

    /* ---------------- 界面来借、来还控制台那张网页 ---------------- */

    /** 界面回到前台：把控制台网页借给它显示。 */
    WebView show(Activity activity) {
        screen = new WeakReference<>(activity);
        shown = true;
        console.detach();
        console.shownIn(activity);
        arrangeOverlay();
        return console.view();
    }

    /** 界面离开前台：网页还回来，接着运行。 */
    void hide() {
        if (!shown) return;
        shown = false;
        console.detach();
        console.shownIn(null);
        arrangeOverlay();
    }

    boolean goBack() {
        return console.goBack();
    }

    /* ---------------- 悬浮窗 ---------------- */

    /**
     * 哪张网页现在不在界面上，就把它放进悬浮窗：读弹幕的那张在读的时候一直在里面，控制台那张在界面离开时进去。
     * 每次有变化都拆了重搭，免得记一堆状态。
     */
    private void arrangeOverlay() {
        WindowManager windows = getSystemService(WindowManager.class);
        View reader = collector == null ? null : collector.view();
        if (overlay != null) {
            ((ViewGroup) overlay).removeAllViews();
            try {
                windows.removeView(overlay);
            } catch (RuntimeException alreadyGone) {
                // 没挂上去过。
            }
            overlay = null;
            badge = null;
        }
        boolean consoleAway = !shown && console.view() != null;
        if (quitting || (reader == null && !consoleAway) || !Settings.canDrawOverlays(this)) return;

        float dp = getResources().getDisplayMetrics().density;
        FrameLayout box = new FrameLayout(this);
        if (reader != null) box.addView(reader, new FrameLayout.LayoutParams(1, 1));
        if (consoleAway) box.addView(console.view(), new FrameLayout.LayoutParams(1, 1));
        // 标记只在一方志自己不在前台时显示：告诉用户它还在后台干活。
        if (!shown) {
            badge = new TextView(this);
            badge.setTextSize(11);
            badge.setTextColor(Color.WHITE);
            badge.setPadding((int) (8 * dp), (int) (3 * dp), (int) (8 * dp), (int) (3 * dp));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0xB3202020);
            pill.setCornerRadius(12 * dp);
            badge.setBackground(pill);
            badge.setText(badgeText());
            box.addView(badge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (int) (8 * dp);
        params.y = (int) (96 * dp);
        try {
            windows.addView(box, params);
            overlay = box;
        } catch (RuntimeException refused) {
            box.removeAllViews();
            badge = null;
            log.note("[工作台] 悬浮窗没有显示出来（" + refused.getMessage() + "），网页改为纯后台运行");
        }
    }

    private String badgeText() {
        return collector == null ? "一方志" : "一方志 · 弹幕 " + chats;
    }

    /* ---------------- 页面请软件做的事 ---------------- */

    @Override
    public void startCollector(String roomId) {
        stopCollector();
        room = roomId;
        chats = 0;
        status = "";
        collector = new DanmakuCollector(this, roomId, this);
        collector.start();
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yifangzhi:live");
        wakeLock.acquire();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yifangzhi:live");
            wifiLock.acquire();
        }
        log.note("[工作台] 开始读直播间 " + roomId + "，悬浮窗：" + (Settings.canDrawOverlays(this) ? "可用" : "没有授权")
                + "，电池优化：" + (power.isIgnoringBatteryOptimizations(getPackageName()) ? "已不限制" : "仍受限制"));
        arrangeOverlay();
        showNotification();
    }

    @Override
    public void stopCollector() {
        if (collector == null) return;
        collector.stop();
        collector = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null;
        wifiLock = null;
        log.note("[工作台] 停止读直播间，这一次读到 " + chats + " 条弹幕");
        arrangeOverlay();
        showNotification();
    }

    @Override
    public void note(String text) {
        log.note("[工作台] " + text);
    }

    @Override
    public void consoleGone() {
        if (quitting) return;
        stopCollector();
        console.destroy();
        console = new ConsoleWeb(this, this);
        console.start();
        Activity activity = screen.get();
        // 界面还开着：让它重新来借一次新的网页。
        if (shown && activity instanceof ConsoleActivity) ((ConsoleActivity) activity).reattach();
        else arrangeOverlay();
    }

    /* ---------------- 读弹幕的那张网页送来的东西，转交给控制台页面 ---------------- */

    @Override
    public void onStatus(String code, boolean live, String text) {
        console.danmaku(Wire.status(code, live, text));
        if (!"OPENING".equals(code) && !text.equals(status)) {
            status = text;
            log.note("[工作台] " + text);
            showNotification();
        }
    }

    @Override
    public void onChat(String id, String text) {
        chats += 1;
        console.danmaku(Wire.chat(id, text));
        if (badge != null) badge.setText(badgeText());
    }

    @Override
    public void onFrame(DouyinFrames.Frame frame) {
        // 正式使用时不统计帧。
    }

    @Override
    public void onNote(String text) {
        log.note("[工作台] " + text);
    }

    @Override
    public void onRendererGone() {
        if (collector == null) return;
        String again = room;
        collector.stop();
        collector = null;
        arrangeOverlay();
        main.postDelayed(() -> {
            // 页面那边仍以为在读；换一张新的网页接着读，状态会重新送过去。
            if (!quitting && collector == null && again.equals(room)) {
                collector = new DanmakuCollector(this, again, this);
                collector.start();
                arrangeOverlay();
            }
        }, 3000);
    }

    /* ---------------- 通知和退出 ---------------- */

    private void showNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "直播运行状态", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ConsoleActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent leave = PendingIntent.getService(this, 1, new Intent(this, LiveService.class).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("一方志正在运行")
                .setContentText(collector == null ? "点这里回到直播工作台" : (status.isEmpty() ? "正在连接直播间" : status))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "退出", leave).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION, notification);
        } catch (RuntimeException refused) {
            log.note("[工作台] 系统没让后台服务挂上通知（" + refused.getClass().getSimpleName() + "），它更容易被关掉");
        }
    }

    /** 彻底退出：不读弹幕、不出声、不留在后台。 */
    private void quit() {
        quitting = true;
        stopCollector();
        arrangeOverlay();
        Activity activity = screen.get();
        if (activity != null) activity.finishAndRemoveTask();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        quitting = true;
        if (collector != null) collector.stop();
        collector = null;
        arrangeOverlay();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        console.destroy();
        super.onDestroy();
    }
}
