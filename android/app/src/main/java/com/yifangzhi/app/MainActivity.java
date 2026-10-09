package com.yifangzhi.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 验证版只有这一个界面：填直播间、开始、看结果、把结果复制出来。
 * 正式的软件不长这样；这一版只为了在真手机上回答 ProbeService 里写的那三个问题。
 */
public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService lookups = Executors.newSingleThreadExecutor();
    private final Runnable tick = this::tick;
    private ProbeState state;
    private EditText roomInput;
    private CheckBox overlayBox;
    private CheckBox soundBox;
    private Button startButton;
    private Button stopButton;
    private Button notifyButton;
    private Button batteryButton;
    private Button overlayButton;
    private TextView statusView;
    private TextView numbersView;
    private TextView logView;
    private String error = "";
    private boolean looking;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        state = ProbeState.of(this);
        SharedPreferences prefs = getSharedPreferences(ProbeService.PREFS, MODE_PRIVATE);
        int pad = dp(16);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pad, pad, pad, pad);

        page.addView(text("一方志 · 验证版", 20, true));
        page.addView(text("用来确认三件事：抖音在前台直播时，这里在后台还能不能读到弹幕；系统会不会把它关掉；后台放的声音直播间里听不听得到。只读弹幕文字，不登录抖音，不读观众的昵称和账号。", 13, false));

        page.addView(label("第一步：授权（都点成“已允许”最好；不允许也能测，结果会不一样）"));
        notifyButton = button("允许通知", view -> askNotifications());
        batteryButton = button("不限制后台耗电", view -> askBattery());
        overlayButton = button("允许悬浮窗", view -> askOverlay());
        page.addView(notifyButton);
        page.addView(batteryButton);
        page.addView(overlayButton);

        page.addView(label("第二步：填直播间"));
        roomInput = new EditText(this);
        roomInput.setHint("在抖音直播间点“分享 → 复制链接”，整段粘贴到这里；也可以直接填直播间号");
        roomInput.setTextSize(14);
        roomInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        roomInput.setMinLines(2);
        roomInput.setText(prefs.getString("input", ""));
        page.addView(roomInput);

        overlayBox = new CheckBox(this);
        overlayBox.setText("用悬浮窗方式（屏幕上会多一个小标记；先不勾测一次，读不到或中断了再勾上测一次）");
        overlayBox.setChecked(prefs.getBoolean(ProbeService.EXTRA_OVERLAY, false));
        page.addView(overlayBox);
        soundBox = new CheckBox(this);
        soundBox.setText("每 20 秒放一段测试声音（“这是一方志的测试声音，一二三四五”）");
        soundBox.setChecked(prefs.getBoolean(ProbeService.EXTRA_SOUND, true));
        page.addView(soundBox);

        page.addView(label("第三步：开始，然后切到抖音开播"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("开始", view -> start());
        stopButton = button("停止", view -> startService(new Intent(this, ProbeService.class).setAction(ProbeService.ACTION_STOP)));
        row.addView(startButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(stopButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(row);

        statusView = text("", 16, true);
        numbersView = text("", 13, false);
        page.addView(statusView);
        page.addView(numbersView);

        page.addView(label("第四步：测完点这里，把结果发给开发"));
        page.addView(button("复制结果", view -> copyResult()));
        page.addView(button("清空记录", view -> state.clearLog()));

        page.addView(label("记录（最新的在最下面）"));
        logView = text("", 11, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        page.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        state.watch(this::render);
        render();
        main.postDelayed(tick, 1000);
    }

    @Override
    protected void onStop() {
        state.watch(null);
        main.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        lookups.shutdownNow();
        super.onDestroy();
    }

    /** 运行时间每秒在变，授权状态也可能是刚从系统设置里改回来的。 */
    private void tick() {
        render();
        main.postDelayed(tick, 1000);
    }

    private void start() {
        if (looking) return;
        String input = roomInput.getText().toString();
        boolean overlay = overlayBox.isChecked();
        boolean sound = soundBox.isChecked();
        getSharedPreferences(ProbeService.PREFS, MODE_PRIVATE).edit().putString("input", input).apply();
        String unsupported = DanmakuCollector.unsupported();
        if (unsupported != null) {
            error = unsupported + "（" + webView() + "）";
            render();
            return;
        }
        error = "";
        looking = true;
        render();
        // 粘贴的是分享链接时，要去打开它才知道是哪个直播间，得联网。
        lookups.execute(() -> {
            String room = null;
            String failure = "";
            try {
                room = RoomLookup.resolve(input);
            } catch (RoomLookup.Failed failed) {
                failure = failed.getMessage();
            }
            String found = room;
            String reason = failure;
            main.post(() -> {
                looking = false;
                error = reason;
                if (found != null) {
                    startForegroundService(new Intent(this, ProbeService.class).setAction(ProbeService.ACTION_START)
                            .putExtra(ProbeService.EXTRA_ROOM, found).putExtra(ProbeService.EXTRA_OVERLAY, overlay).putExtra(ProbeService.EXTRA_SOUND, sound));
                }
                render();
            });
        });
    }

    private void render() {
        boolean notifications = getSystemService(NotificationManager.class).areNotificationsEnabled();
        boolean battery = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        boolean overlay = Settings.canDrawOverlays(this);
        notifyButton.setText(notifications ? "通知：已允许 ✓" : "允许通知");
        batteryButton.setText(battery ? "后台耗电：已不限制 ✓" : "不限制后台耗电");
        overlayButton.setText(overlay ? "悬浮窗：已允许 ✓" : "允许悬浮窗");

        startButton.setEnabled(!looking && !state.running);
        startButton.setText(looking ? "正在查找直播间…" : "开始");
        stopButton.setEnabled(state.running);
        roomInput.setEnabled(!state.running && !looking);
        overlayBox.setEnabled(!state.running && !looking);
        soundBox.setEnabled(!state.running && !looking);

        statusView.setText(error.isEmpty() ? state.summary() : error);
        long now = System.currentTimeMillis();
        numbersView.setText(state.startedAt == 0 ? "" : "收到 " + state.frames + " 帧，弹幕 " + state.chats + " 条"
                + (state.lastChatAt > 0 ? "，最近一条在 " + (now - state.lastChatAt) / 1000 + " 秒前" : "")
                + (state.lastFrameAt > 0 && state.running ? "，最近一帧在 " + (now - state.lastFrameAt) / 1000 + " 秒前" : "")
                + "\n相邻两帧最长隔了 " + state.longestGap / 1000 + " 秒（一直连着的话应该只有十几秒）");
        logView.setText(state.log(120));
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void askBattery() {
        if (getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        else open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
    }

    private void askOverlay() {
        open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    /** 各家手机的设置页不一样，有的没有对应的页面。 */
    private void open(Intent settings) {
        try {
            startActivity(settings);
        } catch (RuntimeException missing) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException alsoMissing) {
                Toast.makeText(this, "打不开设置页，请到系统设置里找到“一方志验证版”手动打开", Toast.LENGTH_LONG).show();
            }
        }
    }

    private String webView() {
        PackageInfo engine = WebViewCompat.getCurrentWebViewPackage(this);
        return engine == null ? "没有找到系统浏览器内核" : engine.packageName + " " + engine.versionName;
    }

    /** 开头是这台手机的情况，后面是记录。读结果的人靠开头这几行知道是在什么手机上测的。 */
    private void copyResult() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // 自己的包总是在的。
        }
        String result = "一方志验证版 " + version + "\n"
                + "手机：" + Build.MANUFACTURER + " " + Build.MODEL + "，安卓 " + Build.VERSION.RELEASE + "（" + Build.VERSION.SDK_INT + "）\n"
                + "浏览器内核：" + webView() + "\n"
                + "能在网页脚本之前放入脚本：" + yes(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                + "，网页能把数据交给软件：" + yes(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                + "，能不带软件包名：" + yes(WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) + "\n"
                + "通知：" + yes(getSystemService(NotificationManager.class).areNotificationsEnabled())
                + "，不限制后台耗电：" + yes(getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()))
                + "，悬浮窗：" + yes(Settings.canDrawOverlays(this)) + "\n"
                + "现在：" + state.summary() + "\n\n"
                + state.log(400);
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("一方志验证结果", result));
        Toast.makeText(this, "已复制，可以粘贴发出去了", Toast.LENGTH_SHORT).show();
    }

    private static String yes(boolean value) {
        return value ? "是" : "否";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String content, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setLineSpacing(0, 1.3f);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private TextView label(String content) {
        TextView view = text(content, 13, true);
        view.setPadding(0, dp(18), 0, dp(4));
        return view;
    }

    private Button button(String content, View.OnClickListener action) {
        Button view = new Button(this);
        view.setText(content);
        view.setAllCaps(false);
        view.setOnClickListener(action);
        return view;
    }
}
