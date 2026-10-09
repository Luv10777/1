package com.yifangzhi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URL;

import org.junit.Test;

/** The cases of desktop/src/roomLookup.test.js that need no network. */
public class RoomLookupTest {
    private static final String LINK = "https://v.douyin.com/eTtr9w0D3gY/";

    @Test
    public void aRoomIdIsTakenAsTypedOrOutOfALiveDouyinAddressAndNothingElseIsAccepted() throws Exception {
        assertEquals("850050208045", RoomLookup.normalizeRoomId(" 850050208045 "));
        assertEquals("850050208045", RoomLookup.normalizeRoomId("https://live.douyin.com/850050208045?enter_from=web"));
        assertEquals("shop_abc.1", RoomLookup.normalizeRoomId("live.douyin.com/shop_abc.1"));
        for (String bad : new String[] {"", null, "123/../admin", "8500 5020", "https://example.com/850050208045"}) {
            RoomLookup.Failed failed = assertThrows(String.valueOf(bad), RoomLookup.Failed.class, () -> RoomLookup.normalizeRoomId(bad));
            assertTrue(failed.getMessage().contains("没有认出直播间"));
        }
    }

    @Test
    public void theShortLinkIsPickedOutOfWhateverWasCopiedAlongWithIt() {
        assertEquals(LINK, RoomLookup.extractShareLink(LINK));
        assertEquals(LINK, RoomLookup.extractShareLink("3.84 复制打开抖音，看看【小店的直播】 https://v.douyin.com/eTtr9w0D3gY/ w@f.oD 05/21"));
        assertEquals("https://v.douyin.com/i-AB_cd9/", RoomLookup.extractShareLink("http://v.douyin.com/i-AB_cd9"));
        for (String other : new String[] {"", null, "850050208045", "eTtr9w0D3gY", "https://live.douyin.com/850050208045", "https://v.douyin.com.evil.test/abcd1234/"}) {
            assertNull(String.valueOf(other), RoomLookup.extractShareLink(other));
        }
    }

    @Test
    public void theRoomIdIsReadFromTheLandingPageWhetherOrNotItsDataIsEscaped() {
        assertEquals("850050208045", RoomLookup.extractWebRid("<script>{\"room\":{\"webRid\":\"850050208045\",\"title\":\"x\"}}</script>"));
        assertEquals("850050208045", RoomLookup.extractWebRid("self.__pace_f.push([1,\"{\\\"webRid\\\":\\\"850050208045\\\",\\\"shortId\\\":577937233}\"])"));
        assertNull(RoomLookup.extractWebRid("<html>没有直播间信息</html>"));
        assertNull(RoomLookup.extractWebRid(null));
    }

    @Test
    public void aRedirectThatLeavesDouyinIsNotFollowed() throws Exception {
        assertTrue(RoomLookup.isLanding(new URL("https://webcast.amemv.com/douyin/webcast/reflow/7694004416784943922?u_code=abc")));
        assertTrue(RoomLookup.isLanding(new URL("https://www.iesdouyin.com/share/live/1")));
        assertTrue(RoomLookup.isLanding(new URL("https://douyin.com/x")));
        for (String location : new String[] {"https://example.com/landing", "http://webcast.amemv.com/x", "https://amemv.com.evil.test/x", "http://169.254.169.254/latest/meta-data", "https://notdouyin.com/x"}) {
            assertFalse(location, RoomLookup.isLanding(new URL(location)));
        }
    }

    @Test
    public void somethingThatIsNotAShareLinkIsRefusedBeforeAnyRequestIsMade() {
        RoomLookup.Failed failed = assertThrows(RoomLookup.Failed.class, () -> RoomLookup.resolveShareLink("850050208045"));
        assertEquals("这不是抖音的分享链接", failed.getMessage());
    }
}
