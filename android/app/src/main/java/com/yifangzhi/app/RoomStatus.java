package com.yifangzhi.app;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 直播间网页自己查询直播间状态得到的回应。只取两样：是否在播、这一场的内部房间号；其余资料不看。
 */
final class RoomStatus {
    // 回应里 status 为 2 表示正在直播。
    private static final int STATUS_LIVE = 2;

    final String id;
    final boolean live;

    private RoomStatus(String id, boolean live) {
        this.id = id;
        this.live = live;
    }

    /** 读不出来（不是预期的样子）时返回 null。 */
    static RoomStatus parse(String body) {
        try {
            JSONObject first = new JSONObject(body).getJSONObject("data").getJSONArray("data").getJSONObject(0);
            return new RoomStatus(first.optString("id_str", ""), first.optInt("status", 0) == STATUS_LIVE);
        } catch (JSONException | NullPointerException error) {
            return null;
        }
    }
}
