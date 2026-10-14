package com.yifangzhi.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
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
    private LinearLayout volumeHint;
    private TextView volumeWords;
    private boolean volumeHintClosed;
    /** 用音量键调音量时系统会发这条广播；据此让提醒跟着出现或消失。 */
    private final BroadcastReceiver volumeChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            checkVolume();
        }
    };

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
        checkVolume();
        IntentFilter volume = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(volumeChanged, volume, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(volumeChanged, volume);
    }

    @Override
    protected void onPause() {
        unregisterReceiver(volumeChanged);
        super.onPause();
    }

    /**
     * 媒体音量太小时提醒一句。
     * AI 的声音是从手机喇叭放出来、再被抖音收进直播间的。音量小的时候，每段讲解停顿之后重新出声的头几个字
     * 会被抖音的降噪当成杂音压掉：荣耀 X50 上实测，音量 5/16 时直播间里听得出来，调大之后好一些。手机喇叭本身听不出这个问题，
     * 所以主播自己发现不了，要软件来说。
     */
    private void checkVolume() {
        AudioManager audio = getSystemService(AudioManager.class);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean quiet = max > 0 && now * 10 < max * 6;
        if (!quiet || volumeHintClosed || gate != null) {
            if (volumeHint != null) frame.removeView(volumeHint);
            volumeHint = null;
            return;
        }
        String words = "媒体音量偏小（现在 " + now + " 格，满格 " + max + "）。音量小的时候，直播间里每段讲解开头的几个字容易被抖音的降噪压掉。";
        if (volumeHint != null) {
            volumeWords.setText(words);
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * dp);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setPadding(pad + pad / 2, pad, pad + pad / 2, pad / 2);
        strip.setBackgroundColor(0xFFFFF1DC);
        strip.setClickable(true);
        volumeWords = words(words, 13, false, 0xFF5A4520);
        volumeWords.setPadding(0, 0, 0, 0);
        strip.addView(volumeWords);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        Button raise = new Button(this);
        raise.setText("调到合适音量");
        raise.setAllCaps(false);
        raise.setTextSize(13);
        raise.setOnClickListener(view -> raiseVolume());
        Button close = new Button(this);
        close.setText("知道了");
        close.setAllCaps(false);
        close.setTextSize(13);
        close.setOnClickListener(view -> {
            volumeHintClosed = true;
            checkVolume();
        });
        row.addView(close);
        row.addView(raise);
        strip.addView(row);
        volumeHint = strip;
        frame.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
    }

    /** 调到满格的四分之三，并让系统显示音量条，用户看得到改成了多少、可以接着自己调。 */
    private void raiseVolume() {
        AudioManager audio = getSystemService(AudioManager.class);
        int target = Math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.75f);
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
        } catch (RuntimeException refused) {
            // 勿扰模式下系统可能不让软件改音量：那就只把音量条叫出来，由用户自己调。
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
        }
        checkVolume();
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
        if (volumeHint != null) frame.removeView(volumeHint);
        volumeHint = null;
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
        card.addView(words("直播时抖音在前台，一方志在后台读弹幕、播放讲解语音。\n\n不给这项允许，手机会在一方志退到后台后把它暂停：播报到一半就没有声音了，弹幕也不再回答，而且没有任何提示。", 15, false, 0xFF444444));
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
            checkVolume();
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
