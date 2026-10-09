package com.yifangzhi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RoomStatusTest {
    @Test
    public void aRoomOnAirIsReadAsLiveWithItsInternalId() {
        RoomStatus status = RoomStatus.parse("{\"data\":{\"data\":[{\"id_str\":\"7694004416784943922\",\"status\":2,\"title\":\"不看\"}],\"user\":{\"nickname\":\"不看\"}}}");
        assertTrue(status.live);
        assertEquals("7694004416784943922", status.id);
    }

    @Test
    public void aRoomThatIsNotOnAirIsReadAsOffline() {
        RoomStatus status = RoomStatus.parse("{\"data\":{\"data\":[{\"id_str\":\"7694004416784943922\",\"status\":4}]}}");
        assertFalse(status.live);
        assertFalse(RoomStatus.parse("{\"data\":{\"data\":[{}]}}").live);
        assertEquals("", RoomStatus.parse("{\"data\":{\"data\":[{}]}}").id);
    }

    @Test
    public void anAnswerOfAnotherShapeIsNotGuessedAt() {
        for (String body : new String[] {"", "<html>验证</html>", "{}", "{\"data\":{}}", "{\"data\":{\"data\":[]}}", "{\"data\":{\"data\":\"x\"}}", "null"}) {
            assertNull(body, RoomStatus.parse(body));
        }
    }
}
