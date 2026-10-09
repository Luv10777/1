package com.yifangzhi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** The page reads these with parseDanmakuMessage; the fields below are the ones it looks at. */
public class WireTest {
    @Test
    public void aRoomStatusCarriesItsCodeWhetherItIsLiveAndTheWordsToShow() throws Exception {
        JSONObject message = new JSONObject(Wire.status("ROOM_ONLINE", true, "直播间已开播，正在接收弹幕"));
        assertEquals("system", message.getString("type"));
        assertEquals("live_status", message.getString("event"));
        assertEquals("ROOM_ONLINE", message.getString("code"));
        assertTrue(message.getBoolean("live"));
        assertEquals("直播间已开播，正在接收弹幕", message.getString("status_text"));
        assertFalse(new JSONObject(Wire.status("ROOM_OFFLINE", false, "直播间未开播")).getBoolean("live"));
    }

    @Test
    public void aCommentCarriesOnlyItsIdAndItsWords() throws Exception {
        JSONObject message = new JSONObject(Wire.chat("18446744073709551615", "多少钱\"一杯\"？\n"));
        assertEquals("WebcastChatMessage", message.getString("method"));
        // The id stays text: it is larger than a number in the page can hold exactly.
        assertEquals("18446744073709551615", message.getJSONObject("common").getString("msgId"));
        assertEquals("多少钱\"一杯\"？\n", message.getString("content"));
        assertEquals(3, message.length());
        assertEquals(1, message.getJSONObject("common").length());
    }
}
