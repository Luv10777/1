package com.yifangzhi.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 软件的界面：一整屏的一方志直播工作台。
 * 网页本身归后台服务所有（见 LiveService），这里只是在自己显示的时候把它借来放在屏幕上。
 */
public final class ConsoleActivity extends Activity {
    private FrameLayout frame;
    private LiveService service;
    private boolean visible;

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
