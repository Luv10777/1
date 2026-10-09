package com.yifangzhi.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 抖音直播间网页收到的弹幕是一帧帧二进制数据（protobuf，外面再包一层 gzip）。
 * 这里只读用得着的几个字段：消息类型、弹幕 ID、弹幕文字、下播通知。
 * 观众的昵称、头像、账号都在别的字段里，这里从不读取。
 *
 * 和桌面端的 desktop/src/douyinFrames.js 是同一套读法，两边的测试用例一一对应；改一边要同时改另一边。
 */
final class DouyinFrames {
    private static final String CHAT = "WebcastChatMessage";
    private static final String CONTROL = "WebcastControlMessage";
    // ControlMessage.action 的取值里，3 表示本场直播结束。
    private static final long ACTION_ENDED = 3;

    private DouyinFrames() {}

    /** 一条弹幕：只有 ID 和文字。 */
    static final class Chat {
        final String id;
        final String text;

        Chat(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    /** 一帧里和我们有关的东西，以及各类消息各有几条（只用于排查，不含内容）。 */
    static final class Frame {
        /** 最外层的类型：msg 才带直播消息，另外还有心跳 hb 和确认 ack。 */
        final String type;
        /** 下播通知之前的弹幕，按到达的先后。 */
        final List<Chat> chats = new ArrayList<>();
        /** 这一帧里有下播通知。它后面的弹幕不再算数。 */
        boolean ended;
        final Map<String, Integer> methods = new LinkedHashMap<>();

        Frame(String type) {
            this.type = type;
        }
    }

    /** 读不懂的帧。由调用方决定忽略。 */
    static final class Unreadable extends Exception {
        Unreadable(String message) {
            super(message);
        }
    }

    /** 一条 protobuf 消息里的一个字段。只展开用得着的两种编码，其余在读的时候就跳过了。 */
    private static final class Field {
        int number;
        boolean isBytes;
        long value;
        int start;
        int end;
    }

    /** 顺着一段数据往后读字段。 */
    private static final class Reader {
        private final byte[] buffer;
        private final int end;
        private int at;

        Reader(byte[] buffer, int start, int end) {
            this.buffer = buffer;
            this.at = start;
            this.end = end;
        }

        private long varint() throws Unreadable {
            long value = 0;
            int shift = 0;
            for (;;) {
                if (at >= end || shift > 63) throw new Unreadable("数据不完整");
                int piece = buffer[at++] & 0xff;
                value |= (long) (piece & 0x7f) << shift;
                if ((piece & 0x80) == 0) return value;
                shift += 7;
            }
        }

        /** 读下一个用得着的字段到 field 里；读完了返回 false。 */
        boolean next(Field field) throws Unreadable {
            while (at < end) {
                long key = varint();
                int wire = (int) (key & 7);
                field.number = (int) (key >>> 3);
                if (wire == 0) {
                    field.isBytes = false;
                    field.value = varint();
                    return true;
                }
                if (wire == 2) {
                    long length = varint();
                    if (length < 0 || length > end - at) throw new Unreadable("数据不完整");
                    field.isBytes = true;
                    field.start = at;
                    field.end = at + (int) length;
                    at = field.end;
                    return true;
                }
                if (wire == 1) at += 8;
                else if (wire == 5) at += 4;
                else throw new Unreadable("无法识别的数据格式");
            }
            return false;
        }
    }

    private static String text(byte[] buffer, int start, int end) {
        return new String(buffer, start, end - start, StandardCharsets.UTF_8);
    }

    private static boolean isGzip(byte[] buffer, int start, int end) {
        return end - start > 2 && (buffer[start] & 0xff) == 0x1f && (buffer[start + 1] & 0xff) == 0x8b;
    }

    private static byte[] gunzip(byte[] buffer, int start, int end) throws Unreadable {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(buffer, start, end - start))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, (end - start) * 4));
            byte[] chunk = new byte[8192];
            for (int read; (read = in.read(chunk)) > 0; ) out.write(chunk, 0, read);
            return out.toByteArray();
        } catch (IOException error) {
            throw new Unreadable("数据不完整");
        }
    }

    /** 去掉两头的空白，全角空格之类也算。 */
    private static String trim(String value) {
        int from = 0;
        int to = value.length();
        while (from < to && isBlank(value.charAt(from))) from++;
        while (to > from && isBlank(value.charAt(to - 1))) to--;
        return value.substring(from, to);
    }

    private static boolean isBlank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '﻿';
    }

    private static Chat chat(byte[] buffer, int start, int end, long fallbackId) throws Unreadable {
        long id = fallbackId;
        String content = "";
        Reader reader = new Reader(buffer, start, end);
        Field field = new Field();
        while (reader.next(field)) {
            if (field.number == 1 && field.isBytes) {
                Reader common = new Reader(buffer, field.start, field.end);
                Field inner = new Field();
                while (common.next(inner)) if (inner.number == 2 && !inner.isBytes) id = inner.value;
            } else if (field.number == 3 && field.isBytes) {
                content = text(buffer, field.start, field.end);
            }
        }
        // ID 是无符号的 64 位整数，比 long 能表示的正数大，要按无符号来写成文字。
        return new Chat(id != 0 ? Long.toUnsignedString(id) : "", trim(content));
    }

    private static boolean ended(byte[] buffer, int start, int end) throws Unreadable {
        Reader reader = new Reader(buffer, start, end);
        Field field = new Field();
        while (reader.next(field)) if (field.number == 2 && !field.isBytes && field.value == ACTION_ENDED) return true;
        return false;
    }

    /** 读一帧。读不懂的帧抛出 Unreadable。 */
    static Frame read(byte[] buffer) throws Unreadable {
        String type = "";
        int payloadStart = -1;
        int payloadEnd = -1;
        Reader outer = new Reader(buffer, 0, buffer.length);
        Field field = new Field();
        while (outer.next(field)) {
            if (field.number == 7 && field.isBytes) type = text(buffer, field.start, field.end);
            else if (field.number == 8 && field.isBytes) {
                payloadStart = field.start;
                payloadEnd = field.end;
            }
        }
        Frame frame = new Frame(type);
        if (!"msg".equals(type) || payloadStart < 0) return frame;

        byte[] response = buffer;
        int from = payloadStart;
        int to = payloadEnd;
        if (isGzip(buffer, payloadStart, payloadEnd)) {
            response = gunzip(buffer, payloadStart, payloadEnd);
            from = 0;
            to = response.length;
        }
        Reader messages = new Reader(response, from, to);
        Field message = new Field();
        while (messages.next(message)) {
            if (message.number != 1 || !message.isBytes) continue;
            String method = "";
            int start = -1;
            int end = -1;
            long id = 0;
            Reader parts = new Reader(response, message.start, message.end);
            Field part = new Field();
            while (parts.next(part)) {
                if (part.number == 1 && part.isBytes) method = text(response, part.start, part.end);
                else if (part.number == 2 && part.isBytes) {
                    start = part.start;
                    end = part.end;
                } else if (part.number == 3 && !part.isBytes) id = part.value;
            }
            frame.methods.merge(method, 1, Integer::sum);
            if (start < 0) continue;
            if (CHAT.equals(method)) {
                Chat chat = chat(response, start, end, id);
                if (!chat.text.isEmpty() && !frame.ended) frame.chats.add(chat);
            } else if (CONTROL.equals(method) && ended(response, start, end)) {
                frame.ended = true;
            }
        }
        return frame;
    }
}
