package com.yifangzhi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
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
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * 验证版的后台部分。要回答三个问题：
 *   1. 抖音在前台直播时，这里在后台还能不能一直读到弹幕；
 *   2. 系统会不会把它暂停或关掉；
 *   3. 在后台放出来的声音，直播间里听不听得到。
 * 所以它只做三件事：读弹幕、每隔一会儿放一段测试声音、每分钟记一笔“还在运行”。
 * 记录里哪一分钟缺了，就是那一分钟被系统暂停或关掉了。
 */
public final class ProbeService extends Service implements DanmakuCollector.Listener {
    static final String ACTION_START = "com.yifangzhi.app.START";
    static final String ACTION_STOP = "com.yifangzhi.app.STOP";
    static final String EXTRA_ROOM = "room";
    static final String EXTRA_OVERLAY = "overlay";
    static final String EXTRA_SOUND = "sound";
    static final String PREFS = "probe";

    private static final String CHANNEL = "probe";
    private static final int NOTIFICATION = 1;
    private static final long REPORT_EVERY = 60_000;
    private static final long SOUND_EVERY = 20_000;
    private static final int REMEMBER_IDS = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final Runnable report = this::report;
    private final Runnable sound = this::playSound;
    private ProbeState state;
    private DanmakuCollector collector;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private MediaPlayer player;
    private View overlay;
    private TextView badge;
    private String room = "";
    private boolean useOverlay;
    private boolean useSound;
    private String lastStatus = "";
    private boolean foregroundRefused;

    @Override
    public void onCreate() {
        super.onCreate();
        state = ProbeState.of(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (intent == null) {
            // 系统把服务关掉后自己又拉了起来。之前内存里的东西都没了，按上次的设置接着跑。
            if (!prefs.getBoolean("running", false)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            state.note("[中断] 后台服务被系统关掉过，现在又被拉起来了；中间这段没有读弹幕");
            begin(prefs.getString(EXTRA_ROOM, ""), prefs.getBoolean(EXTRA_OVERLAY, false), prefs.getBoolean(EXTRA_SOUND, false));
            return START_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            finish("手动停止");
            stopSelf();
            return START_NOT_STICKY;
        }
        begin(intent.getStringExtra(EXTRA_ROOM), intent.getBooleanExtra(EXTRA_OVERLAY, false), intent.getBooleanExtra(EXTRA_SOUND, false));
        return START_STICKY;
    }

    private void begin(String roomId, boolean overlayWanted, boolean soundWanted) {
        if (collector != null) release();
        room = roomId == null ? "" : roomId;
        useOverlay = overlayWanted;
        useSound = soundWanted;
        state.reset(room);
        lastStatus = "";
        seen.clear();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("running", true).putString(EXTRA_ROOM, room).putBoolean(EXTRA_OVERLAY, useOverlay).putBoolean(EXTRA_SOUND, useSound).apply();

        showNotification();
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yifangzhi:probe");
        wakeLock.acquire();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yifangzhi:probe");
            wifiLock.acquire();
        }

        boolean overlayAllowed = Settings.canDrawOverlays(this);
        state.note("[开始] 直播间 " + room + "，方式：" + (useOverlay && overlayAllowed ? "悬浮窗" : "纯后台")
                + (useOverlay && !overlayAllowed ? "（想用悬浮窗，但没有授权）" : "")
                + "，测试声音：" + (useSound ? "每 20 秒一次" : "不放")
                + "，电池优化：" + (power.isIgnoringBatteryOptimizations(getPackageName()) ? "已不限制" : "仍受限制"));
        startCollector();
        main.postDelayed(report, REPORT_EVERY);
        if (useSound) main.postDelayed(sound, 3000);
    }

    private void startCollector() {
        collector = new DanmakuCollector(this, room, this);
        collector.start();
        if (useOverlay && Settings.canDrawOverlays(this) && collector.view() != null) attachOverlay(collector.view());
    }

    /**
     * 悬浮窗方式：把那张网页放进一个盖在其他软件上面的小窗口里（网页本身只占一个像素，旁边是一个小标记）。
     * 这样在系统眼里它是“正在显示”的网页，不容易被放慢或暂停。
     */
    private void attachOverlay(View page) {
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout box = new FrameLayout(this);
        box.addView(page, new FrameLayout.LayoutParams(1, 1));
        badge = new TextView(this);
        badge.setTextSize(11);
        badge.setTextColor(Color.WHITE);
        badge.setPadding((int) (8 * dp), (int) (3 * dp), (int) (8 * dp), (int) (3 * dp));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xB3202020);
        pill.setCornerRadius(12 * dp);
        badge.setBackground(pill);
        badge.setText("一方志");
        box.addView(badge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (int) (8 * dp);
        params.y = (int) (96 * dp);
        try {
            getSystemService(WindowManager.class).addView(box, params);
            overlay = box;
        } catch (RuntimeException error) {
            state.note("悬浮窗没有显示出来（" + error.getMessage() + "），改用纯后台方式");
            box.removeView(page);
            badge = null;
        }
    }

    private void detachOverlay() {
        if (overlay != null) {
            try {
                getSystemService(WindowManager.class).removeView(overlay);
            } catch (RuntimeException ignored) {
                // 已经不在了。
            }
        }
        overlay = null;
        badge = null;
    }

    private void release() {
        main.removeCallbacks(report);
        main.removeCallbacks(sound);
        if (collector != null) collector.stop();
        collector = null;
        detachOverlay();
        if (player != null) player.release();
        player = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null;
        wifiLock = null;
    }

    private void finish(String reason) {
        if (!state.running) return;
        if (collector != null) state.blocked = collector.refused();
        release();
        state.note("[结束] " + reason + "。" + totals());
        state.running = false;
        state.status = "已停止";
        state.changed();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("running", false).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private String totals() {
        return String.format(Locale.US, "已运行 %s，帧 %d（心跳 %d，读不懂 %d），弹幕 %d（重复 %d），相邻两帧最长隔了 %d 秒，没让网页下载的请求 %d 个，放了 %d 次声音",
                ProbeState.span(System.currentTimeMillis() - state.startedAt), state.frames, state.heartbeats, state.unreadable,
                state.chats, state.repeats, state.longestGap / 1000, state.blocked, state.sounds);
    }

    /** 每分钟记一笔。记录里缺了哪一分钟，就是那一分钟这里没在运行。 */
    private void report() {
        if (collector != null) state.blocked = collector.refused();
        boolean screenOn = getSystemService(PowerManager.class).isInteractive();
        state.note("[仍在运行] " + totals() + (screenOn ? "" : "，屏幕是灭的"));
        showNotification();
        main.postDelayed(report, REPORT_EVERY);
    }

    /** 不去抢别的软件的声音（不申请音频焦点），就像背景里多了一个人说话。 */
    private void playSound() {
        try {
            if (player != null) player.release();
            AudioAttributes voice = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            player = MediaPlayer.create(this, R.raw.test_voice, voice, getSystemService(AudioManager.class).generateAudioSessionId());
            if (player == null) throw new IllegalStateException("播放器没有建起来");
            player.setOnCompletionListener(done -> {
                done.release();
                if (player == done) player = null;
            });
            player.start();
            state.sounds += 1;
            state.note("[声音] 第 " + state.sounds + " 次播放测试声音");
        } catch (RuntimeException error) {
            state.note("[声音] 没放出来：" + error.getMessage());
        }
        main.postDelayed(sound, SOUND_EVERY);
    }

    private void showNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "验证运行状态", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ProbeService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("一方志验证版正在后台运行")
                .setContentText(state.summary())
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION, notification);
        } catch (RuntimeException error) {
            // 被系统关掉后在后台重新拉起时，新版安卓可能不让它再挂通知。记下来，接着跑，只是更容易再被关掉。
            if (!foregroundRefused) state.note("[提示] 系统没让后台服务挂上通知（" + error.getClass().getSimpleName() + "），它更容易被关掉");
            foregroundRefused = true;
        }
    }

    @Override
    public void onStatus(String code, boolean live, String text) {
        state.status = text;
        state.live = live;
        // 没开播时每半分钟重查一次，同样的状态不重复记。
        if (!"OPENING".equals(code) && !code.equals(lastStatus)) {
            state.note("[状态] " + text);
            showNotification();
        }
        if (!"OPENING".equals(code)) lastStatus = code;
        state.changed();
    }

    @Override
    public void onChat(String id, String text) {
        if (!id.isEmpty() && !seen.add(id)) {
            state.repeats += 1;
            return;
        }
        if (seen.size() > REMEMBER_IDS) {
            Iterator<String> oldest = seen.iterator();
            oldest.next();
            oldest.remove();
        }
        state.chats += 1;
        state.lastChat = text;
        state.lastChatAt = System.currentTimeMillis();
        if (badge != null) badge.setText("一方志 · 弹幕 " + state.chats);
        state.note("[弹幕] " + text);
    }

    @Override
    public void onFrame(DouyinFrames.Frame frame) {
        long now = System.currentTimeMillis();
        if (state.lastFrameAt > 0) state.longestGap = Math.max(state.longestGap, now - state.lastFrameAt);
        state.lastFrameAt = now;
        if (frame == null) state.unreadable += 1;
        else {
            state.frames += 1;
            if ("hb".equals(frame.type)) state.heartbeats += 1;
        }
    }

    @Override
    public void onNote(String text) {
        state.note(text);
    }

    @Override
    public void onRendererGone() {
        if (collector == null) return;
        state.blocked = collector.refused();
        collector.stop();
        collector = null;
        detachOverlay();
        main.postDelayed(() -> {
            if (state.running && collector == null) startCollector();
        }, 3000);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (state.running) state.note("[提示] 软件被从最近任务里划掉了，后台服务还在");
    }

    @Override
    public void onDestroy() {
        // 正常停止时 finish() 已经收拾过了；走到这里还在运行，说明是被系统结束的。
        if (state.running) {
            state.note("[中断] 后台服务被系统结束了");
            release();
            state.running = false;
            state.status = "后台服务被系统结束了";
            state.changed();
        }
        super.onDestroy();
    }
}
