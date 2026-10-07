package com.wuyao.growth.live.player;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Generated local test fixture; contains no recorded or cloned voice. */
final class PlayerTestAudio {
    static final String URL = "/api/player/test-audio.wav";
    static final int SAMPLE_RATE = 16000;
    static final long DURATION_MILLIS = 12000;
    static final byte[] WAV = generate();
    static final List<Long> PAUSE_OFFSETS = quietPoints(WAV);
    private PlayerTestAudio() {}

    private static byte[] generate() {
        int samples = SAMPLE_RATE * (int) DURATION_MILLIS / 1000;
        ByteBuffer wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + samples * 2);
        wav.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1);
        wav.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short)2).putShort((short)16);
        wav.put(new byte[]{'d','a','t','a'}).putInt(samples * 2);
        for (int sample = 0; sample < samples; sample++) {
            double seconds = sample / (double) SAMPLE_RATE;
            double cycle = seconds % 3;
            boolean quiet = cycle >= 2.4;
            double fade = Math.min(1, Math.min(cycle * 25, (2.4 - cycle) * 25));
            short amplitude = quiet ? 0 : (short) (Math.sin(2 * Math.PI * 440 * seconds) * 5000 * fade);
            wav.putShort(amplitude);
        }
        return wav.array();
    }

    private static List<Long> quietPoints(byte[] wav) {
        ByteBuffer samples = ByteBuffer.wrap(wav, 44, wav.length - 44).order(ByteOrder.LITTLE_ENDIAN);
        int window = SAMPLE_RATE / 10;
        List<Long> points = new ArrayList<>();
        int quietStart = -1;
        int windowIndex = 0;
        while (samples.remaining() >= window * 2) {
            double square = 0;
            for (int i = 0; i < window; i++) { double value = samples.getShort(); square += value * value; }
            boolean quiet = Math.sqrt(square / window) < 100;
            if (quiet && quietStart < 0) quietStart = windowIndex;
            if (!quiet && quietStart >= 0) {
                if (windowIndex - quietStart >= 3) points.add((quietStart + windowIndex) * 50L);
                quietStart = -1;
            }
            windowIndex++;
        }
        return List.copyOf(points);
    }
}
