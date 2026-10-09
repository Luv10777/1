package com.yifangzhi.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 验证版把看到的东西记在这里：界面从这里读，后台服务往这里写。
 * 记录同时写进软件自己目录下的一个文件。要验证的正是“在后台会不会被系统关掉”，
 * 被关掉时内存里的记录就没了，文件还在。
 *
 * 只在主线程上读写。
 */
final class ProbeState {
    private static final int KEEP_LINES = 400;
    private static final long MAX_FILE = 512 * 1024;
    private static ProbeState instance;

    static ProbeState of(Context context) {
        if (instance == null) instance = new ProbeState(new File(context.getApplicationContext().getFilesDir(), "probe-log.txt"));
        return instance;
    }

    private final File file;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private Runnable watcher;

    boolean running;
    String room = "";
    String status = "未开始";
    boolean live;
    long startedAt;
    int frames;
    int heartbeats;
    int unreadable;
    int chats;
    int repeats;
    int blocked;
    int sounds;
    long lastFrameAt;
    long lastChatAt;
    String lastChat = "";
    /** 相邻两帧之间隔得最久的一次，毫秒。连接没断的话应该只有几秒到十几秒。 */
    long longestGap;

    private ProbeState(File file) {
        this.file = file;
        for (String line : readTail().split("\n")) if (!line.isEmpty()) lines.addLast(line);
        while (lines.size() > KEEP_LINES) lines.removeFirst();
    }

    void watch(Runnable watcher) {
        this.watcher = watcher;
    }

    void changed() {
        if (watcher != null) watcher.run();
    }

    void reset(String room) {
        this.room = room;
        running = true;
        status = "正在打开直播间网页…";
        live = false;
        startedAt = System.currentTimeMillis();
        frames = heartbeats = unreadable = chats = repeats = blocked = sounds = 0;
        lastFrameAt = lastChatAt = longestGap = 0;
        lastChat = "";
    }

    /** 记一行，带上时间。 */
    void note(String text) {
        String line = clock.format(new Date()) + " " + text;
        lines.addLast(line);
        while (lines.size() > KEEP_LINES) lines.removeFirst();
        try (FileOutputStream out = new FileOutputStream(file, file.length() < MAX_FILE)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 写不进文件不影响验证本身。
        }
        changed();
    }

    String log(int last) {
        StringBuilder text = new StringBuilder();
        int skip = Math.max(0, lines.size() - last);
        for (String line : lines) {
            if (skip-- > 0) continue;
            text.append(line).append('\n');
        }
        return text.toString();
    }

    void clearLog() {
        lines.clear();
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        changed();
    }

    private String readTail() {
        if (!file.isFile()) return "";
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long from = Math.max(0, in.length() - 64 * 1024);
            in.seek(from);
            byte[] bytes = new byte[(int) (in.length() - from)];
            in.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            // 从中间读起时，第一行多半是半行。
            return from > 0 ? text.substring(Math.max(0, text.indexOf('\n') + 1)) : text;
        } catch (IOException error) {
            return "";
        }
    }

    static String span(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    /** 一句话的现状，界面和通知栏都用它。 */
    String summary() {
        if (!running) return status;
        return status + " · 已运行 " + span(System.currentTimeMillis() - startedAt) + " · 弹幕 " + chats + " 条";
    }
}
