package com.yifangzhi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.junit.Test;

/** The same cases as desktop/src/douyinFrames.test.js, one for one. */
public class DouyinFramesTest {
    // A small protobuf writer, enough to build frames shaped like the ones the live page receives.
    private static byte[] varint(BigInteger value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BigInteger rest = value;
        do {
            int piece = rest.and(BigInteger.valueOf(0x7f)).intValue();
            rest = rest.shiftRight(7);
            out.write(rest.signum() != 0 ? piece | 0x80 : piece);
        } while (rest.signum() != 0);
        return out.toByteArray();
    }

    private static byte[] varint(long value) {
        return varint(BigInteger.valueOf(value));
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] key(int number, int wire) {
        return varint(((long) number << 3) | wire);
    }

    private static byte[] num(int number, long value) {
        return join(key(number, 0), varint(value));
    }

    private static byte[] num(int number, BigInteger value) {
        return join(key(number, 0), varint(value));
    }

    private static byte[] bytes(int number, byte[] body) {
        return join(key(number, 2), varint(body.length), body);
    }

    private static byte[] bytes(int number, String body) {
        return bytes(number, body.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] fixed(int number, int wire, int length) {
        byte[] filler = new byte[length];
        Arrays.fill(filler, (byte) 7);
        return join(key(number, wire), filler);
    }

    private static byte[] common(BigInteger msgId) {
        return join(bytes(1, "WebcastChatMessage"), num(2, msgId), num(3, new BigInteger("7694155249048734474")));
    }

    private static final byte[] VIEWER = join(num(1, 99887766L), bytes(3, "观众昵称不该被读到"));

    private static byte[] chatPayload(BigInteger msgId, String content) {
        return join(bytes(1, common(msgId)), bytes(2, VIEWER), bytes(3, content), num(4, 0));
    }

    private static byte[] chatPayload(long msgId, String content) {
        return chatPayload(BigInteger.valueOf(msgId), content);
    }

    private static byte[] message(String method, byte[] payload, long msgId) {
        return bytes(1, join(bytes(1, method), bytes(2, payload), num(3, msgId), num(4, 1)));
    }

    private static byte[] message(String method, byte[] payload) {
        return message(method, payload, 0);
    }

    private static byte[] response(byte[]... messages) {
        return join(join(messages), bytes(2, "cursor-1"), num(8, 10000), num(9, 1));
    }

    private static byte[] gzip(byte[] body) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(body);
            }
            return out.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] frame(byte[] payload, String type, boolean compressed) {
        return join(
                num(1, 12), num(2, new BigInteger("7301234567890123456")),
                bytes(5, join(bytes(1, "compress_type"), bytes(2, compressed ? "gzip" : "none"))),
                bytes(6, "pb"), bytes(7, type), bytes(8, compressed ? gzip(payload) : payload));
    }

    private static byte[] frame(byte[] payload) {
        return frame(payload, "msg", true);
    }

    private static DouyinFrames.Frame read(byte[] buffer) {
        try {
            return DouyinFrames.read(buffer);
        } catch (DouyinFrames.Unreadable error) {
            throw new AssertionError(error);
        }
    }

    @Test
    public void aViewerCommentComesOutAsItsIdAndItsWordsAndNothingAboutTheViewer() {
        DouyinFrames.Frame result = read(frame(response(message("WebcastChatMessage", chatPayload(new BigInteger("7694155249048799999"), "  多少钱一杯？ ")))));
        assertEquals(1, result.chats.size());
        assertEquals("7694155249048799999", result.chats.get(0).id);
        assertEquals("多少钱一杯？", result.chats.get(0).text);
        assertFalse(result.ended);
    }

    @Test
    public void anIdLargerThanASignedNumberSurvivesExactly() {
        BigInteger id = new BigInteger("18446744073709551615");
        assertEquals(id.toString(), read(frame(response(message("WebcastChatMessage", chatPayload(id, "在吗"))))).chats.get(0).id);
    }

    @Test
    public void severalMessagesInOneFrameAreReadInOrderAndOnlyCommentsAndTheEndOfTheStreamAreKept() {
        DouyinFrames.Frame result = read(frame(response(
                message("WebcastLikeMessage", join(bytes(1, common(BigInteger.ONE)), num(2, 3))),
                message("WebcastChatMessage", chatPayload(11, "第一条")),
                message("WebcastGiftMessage", bytes(1, common(BigInteger.TWO))),
                message("WebcastChatMessage", chatPayload(12, "第二条")),
                message("WebcastControlMessage", join(bytes(1, common(BigInteger.valueOf(3))), num(2, 3))))));
        assertEquals(2, result.chats.size());
        assertEquals("11", result.chats.get(0).id);
        assertEquals("第一条", result.chats.get(0).text);
        assertEquals("12", result.chats.get(1).id);
        assertEquals("第二条", result.chats.get(1).text);
        assertTrue(result.ended);
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("WebcastLikeMessage", 1);
        expected.put("WebcastChatMessage", 2);
        expected.put("WebcastGiftMessage", 1);
        expected.put("WebcastControlMessage", 1);
        assertEquals(expected, result.methods);
    }

    @Test
    public void commentsThatArriveAfterTheStreamEndedInTheSameFrameAreNotCounted() {
        DouyinFrames.Frame result = read(frame(response(
                message("WebcastChatMessage", chatPayload(11, "下播前")),
                message("WebcastControlMessage", join(bytes(1, common(BigInteger.valueOf(3))), num(2, 3))),
                message("WebcastChatMessage", chatPayload(12, "下播后"))), "msg", false));
        assertEquals(1, result.chats.size());
        assertEquals("下播前", result.chats.get(0).text);
        assertTrue(result.ended);
    }

    @Test
    public void aControlMessageThatIsNotTheEndOfTheStreamIsNotTakenForOne() {
        DouyinFrames.Frame result = read(frame(response(message("WebcastControlMessage", join(bytes(1, common(BigInteger.valueOf(3))), num(2, 1))))));
        assertTrue(result.chats.isEmpty());
        assertFalse(result.ended);
    }

    @Test
    public void heartbeatsAndAcknowledgementsCarryNothingAndArePassedOver() {
        DouyinFrames.Frame heartbeat = read(frame("ignored".getBytes(StandardCharsets.UTF_8), "hb", false));
        assertEquals("hb", heartbeat.type);
        assertTrue(heartbeat.chats.isEmpty());
        assertTrue(heartbeat.methods.isEmpty());
        assertTrue(read(frame("ignored".getBytes(StandardCharsets.UTF_8), "ack", false)).chats.isEmpty());
    }

    @Test
    public void anUncompressedPayloadIsReadJustTheSame() {
        DouyinFrames.Frame result = read(frame(response(message("WebcastChatMessage", chatPayload(5, "没压缩"))), "msg", false));
        assertEquals("5", result.chats.get(0).id);
        assertEquals("没压缩", result.chats.get(0).text);
    }

    @Test
    public void theOuterMessageIdIsUsedWhenTheCommentItselfCarriesNoneAndAnEmptyCommentIsDropped() {
        byte[] noCommonId = join(bytes(1, bytes(1, "WebcastChatMessage")), bytes(3, "有货吗"));
        DouyinFrames.Frame result = read(frame(response(message("WebcastChatMessage", noCommonId, 77))));
        assertEquals("77", result.chats.get(0).id);
        assertEquals("有货吗", result.chats.get(0).text);
        assertTrue(read(frame(response(message("WebcastChatMessage", chatPayload(8, "   "))))).chats.isEmpty());
    }

    @Test
    public void fieldsOfKindsThisReaderDoesNotExpandAreSkippedWithoutLosingItsPlace() {
        byte[] payload = join(fixed(20, 1, 8), bytes(1, common(BigInteger.valueOf(9))), fixed(21, 5, 4), bytes(3, "还在"), fixed(22, 1, 8));
        DouyinFrames.Frame result = read(frame(response(message("WebcastChatMessage", payload))));
        assertEquals("9", result.chats.get(0).id);
        assertEquals("还在", result.chats.get(0).text);
    }

    @Test
    public void aFrameCutShortOrOfAnUnknownShapeIsRefusedRatherThanHalfRead() {
        byte[] whole = frame(response(message("WebcastChatMessage", chatPayload(1, "完整的一条"))), "msg", false);
        assertEquals("数据不完整", assertThrows(DouyinFrames.Unreadable.class, () -> DouyinFrames.read(Arrays.copyOf(whole, whole.length - 5))).getMessage());
        assertEquals("无法识别的数据格式", assertThrows(DouyinFrames.Unreadable.class, () -> DouyinFrames.read(new byte[] {0x0b, 0x01})).getMessage());
        assertEquals("数据不完整", assertThrows(DouyinFrames.Unreadable.class, () -> DouyinFrames.read(new byte[] {(byte) 0x80})).getMessage());
        // A payload that claims to be compressed but is not.
        byte[] broken = join(bytes(7, "msg"), bytes(8, new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00, 0x01}));
        assertEquals("数据不完整", assertThrows(DouyinFrames.Unreadable.class, () -> DouyinFrames.read(broken)).getMessage());
    }
}
