package com.wuyao.growth.live.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** PCM16 mono analysis. Quiet windows are calculated from actual samples, never estimated from text. */
public final class PcmAudio {
    private PcmAudio() { }
    public static long durationMillis(byte[] pcm, int sampleRate) {
        if (sampleRate <= 0 || pcm.length % 2 != 0) throw new IllegalArgumentException("Invalid PCM");
        return (long) pcm.length * 1000 / (sampleRate * 2L);
    }
    /** Below this a 10 ms window carries nothing a listener would miss: about 54 dB under full scale. */
    private static final double SILENCE_RMS = 0.002;
    private static final int LEAD_MILLIS = 30;
    private static final int TAIL_MILLIS = 40;

    /**
     * Removes the silence a synthesiser pads its output with, keeping a short margin so that a soft
     * first or last sound is not clipped. Measured on the provider in use, that padding is roughly
     * half a second before the speech and up to a second after it, which becomes a long dead gap
     * once sentences are synthesised one by one and joined. Audio with nothing audible is returned
     * unchanged rather than emptied.
     */
    public static byte[] trimSilence(byte[] pcm, int sampleRate) {
        int[] range = speechRange(pcm, sampleRate);
        return range == null || (range[0] == 0 && range[1] == pcm.length / 2) ? pcm
                : java.util.Arrays.copyOfRange(pcm, range[0] * 2, range[1] * 2);
    }

    /** @return the first and one-past-last sample worth keeping, or null when nothing is audible */
    public static int[] speechRange(byte[] pcm, int sampleRate) {
        durationMillis(pcm, sampleRate);
        int window = Math.max(1, sampleRate / 100); // 10 ms
        int samples = pcm.length / 2;
        int first = -1;
        int last = -1;
        for (int start = 0; start + window <= samples; start += window) {
            if (rms(pcm, start, window) < SILENCE_RMS) continue;
            if (first < 0) first = start;
            last = start + window;
        }
        if (first < 0) return null;
        return new int[]{Math.max(0, first - sampleRate * LEAD_MILLIS / 1000),
                Math.min(samples, last + sampleRate * TAIL_MILLIS / 1000)};
    }

    /** The quietest 10 ms of a stretch of audio: where it is and how loud it still is there. */
    public record Dip(long atMillis, double rms) { }

    /**
     * Looks for the best place to cut between two times. A time reported by a synthesiser is only
     * good to within a syllable; the samples say where the speaker actually pauses. Of equally quiet
     * windows the one nearest the middle is taken.
     */
    public static Dip quietest(byte[] pcm, int sampleRate, long fromMillis, long toMillis) {
        durationMillis(pcm, sampleRate);
        int window = Math.max(1, sampleRate / 100);
        int samples = pcm.length / 2;
        int from = (int) Math.max(0, Math.min(samples - window, fromMillis * sampleRate / 1000));
        int to = (int) Math.max(from, Math.min(samples - window, toMillis * sampleRate / 1000));
        if (samples < window) return null;
        double middle = (from + to) / 2.0;
        int best = from;
        double quietest = Double.MAX_VALUE;
        for (int start = from; start <= to; start += window) {
            double level = rms(pcm, start, window);
            if (level < quietest || (level == quietest && Math.abs(start - middle) < Math.abs(best - middle))) {
                quietest = level;
                best = start;
            }
        }
        return new Dip((best + window / 2) * 1000L / sampleRate, quietest);
    }

    private static double rms(byte[] pcm, int start, int window) {
        double squares = 0;
        for (int i = start; i < start + window; i++) {
            short value = (short) ((pcm[2*i] & 255) | (pcm[2*i+1] << 8));
            squares += (double) value * value;
        }
        return Math.sqrt(squares / window) / 32768.0;
    }
    public static List<Long> quietPoints(byte[] pcm, int sampleRate) {
        durationMillis(pcm, sampleRate);
        int window = Math.max(1, sampleRate / 50); // 20 ms
        int quietSamples = 0;
        long lastPoint = -2000;
        List<Long> result = new ArrayList<>();
        for (int start = 0; start + window <= pcm.length / 2; start += window) {
            double squares = 0;
            for (int i = start; i < start + window; i++) {
                short value = (short) ((pcm[2*i] & 255) | (pcm[2*i+1] << 8));
                squares += (double) value * value;
            }
            double rms = Math.sqrt(squares / window) / 32768.0;
            quietSamples = rms < 0.008 ? quietSamples + window : 0;
            long at = (long) (start + window) * 1000 / sampleRate;
            if (quietSamples >= sampleRate / 5 && at >= 700 && at - lastPoint >= 1500) {
                result.add(at - 80);
                lastPoint = at;
                quietSamples = 0;
            }
        }
        return List.copyOf(result);
    }
    public static byte[] wav(byte[] pcm, int sampleRate) {
        durationMillis(pcm, sampleRate);
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36 + pcm.length).put(new byte[]{'W','A','V','E'});
        b.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1);
        b.putInt(sampleRate).putInt(sampleRate * 2).putShort((short)2).putShort((short)16);
        b.put(new byte[]{'d','a','t','a'}).putInt(pcm.length).put(pcm);
        return b.array();
    }
}
