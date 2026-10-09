package com.yifangzhi.app;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 采集器的事件 → 交给控制台页面的文本。
 * 页面那边由 src/services/liveDanmakuRelay.js 的 parseDanmakuMessage 读取；格式和桌面端的 desktop/src/wire.js 一样。
 */
final class Wire {
    private Wire() {}

    static String status(String code, boolean live, String text) {
        try {
            return new JSONObject().put("type", "system").put("event", "live_status").put("code", code).put("live", live).put("status_text", text).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String chat(String id, String text) {
        try {
            return new JSONObject().put("method", "WebcastChatMessage").put("common", new JSONObject().put("msgId", id)).put("content", text).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
