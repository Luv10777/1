package com.yifangzhi.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 软件的界面：一整屏的一方志直播工作台。
 * 网页本身归后台服务所有（见 LiveService），这里只是在自己显示的时候把它借来放在屏幕上。
 */
public final class ConsoleActivity extends Activity {
    private FrameLayout frame;
    private LiveService service;
    private boolean visible;
    private View gate;
    private boolean foregroundOnly;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((LiveService.Link) binder).service();
            if (visible) reattach();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFFF8F6F3);
        setContentView(frame);

        // 授权、测试声音、运行记录都在“设置”里（也就是验证版那个界面）。
        TextView settings = new TextView(this);
        settings.setText("设置");
        settings.setTextSize(12);
        settings.setTextColor(Color.WHITE);
        settings.setBackgroundColor(0x99202020);
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        settings.setPadding(pad * 2, pad, pad * 2, pad);
        settings.setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class)));
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        corner.setMargins(pad, pad, pad, pad * 3);
        frame.addView(settings, corner);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Intent live = new Intent(this, LiveService.class);
        startForegroundService(live);
        bindService(live, connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        if (service != null) reattach();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 可能是刚从系统的授权页回来。
        checkBackgroundAllowed();
    }

    /**
     * 直播时抖音在前台，一方志在后台。没有“不限制后台耗电”这项允许，系统会在它退到后台不久把它冻住：
     * 一台荣耀 X50 上实测，最短二十秒就被冻住，声音停掉、连接断开，而界面上没有任何提示。给了这项允许的那几场都没有被冻。
     * 所以没有这项允许时，一打开就拦住并带用户去开，不等播到一半哑掉。
     */
    private void checkBackgroundAllowed() {
        boolean allowed = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        if (allowed || foregroundOnly) {
            if (gate != null) frame.removeView(gate);
            gate = null;
            return;
        }
        if (gate != null) return;
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * dp);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundColor(0xFFF8F6F3);
        // 盖住下面的网页，点不穿。
        card.setClickable(true);

        card.addView(words("还差一步：允许一方志在后台运行", 20, true, 0xFF2A2A2A));
        card.addView(words("直播时抖音在前台，一方志在后台读弹幕、放 AI 的声音。\n\n不给这项允许，手机会在一方志退到后台后把它暂停：AI 讲到一半就没有声音了，弹幕也不再回答，而且没有任何提示。", 15, false, 0xFF444444));
        Button allow = new Button(this);
        allow.setText("去允许");
        allow.setAllCaps(false);
        allow.setTextSize(16);
        allow.setOnClickListener(view -> askBackground());
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wide.topMargin = pad;
        card.addView(allow, wide);
        card.addView(words("在弹出的窗口里选“允许”。\n\n荣耀、华为手机如果允许之后直播中仍然没声音，再到“设置 → 应用和服务 → 应用启动管理”里找到一方志，改成手动管理，把“允许后台活动”打开。各机型的叫法略有不同。", 13, false, 0xFF777777));
        TextView skip = words("先不允许，只在前台用（两台手机开播、一方志一直开在屏幕上时可以这样）", 13, false, 0xFFB5503C);
        skip.setOnClickListener(view -> {
            foregroundOnly = true;
            checkBackgroundAllowed();
        });
        card.addView(skip);

        gate = card;
        frame.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView words(String content, int size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.35f);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        int gap = Math.round(10 * getResources().getDisplayMetrics().density);
        view.setPadding(0, gap, 0, gap);
        return view;
    }

    /** 系统的询问窗口；有的手机没有这个窗口，就打开对应的设置页。 */
    private void askBackground() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException noDialog) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException noPage) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        }
    }

    @Override
    protected void onStop() {
        visible = false;
        if (service != null) service.hide();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unbindService(connection);
        super.onDestroy();
    }

    /** 把控制台网页借来放到屏幕上，垫在“设置”按钮下面。 */
    void reattach() {
        if (service == null || !visible) return;
        WebView web = service.show(this);
        if (web != null && web.getParent() == null) frame.addView(web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void onBackPressed() {
        // 返回键不退出：直播时一不小心按到，播报就断了。退到后台，网页接着运行；要彻底退出用通知栏里的“退出”。
        if (service != null && service.goBack()) return;
        moveTaskToBack(true);
    }
}
