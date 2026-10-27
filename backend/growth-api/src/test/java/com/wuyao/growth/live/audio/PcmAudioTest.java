package com.wuyao.growth.live.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

class PcmAudioTest {
    private static final int RATE = 24000;

    @Test void pauseOffsetsFallInsideActualSustainedSilenceWithSpacing() {
        byte[] pcm = tone(6000);
        silence(pcm, 900, 1250);
        silence(pcm, 2900, 3300);
        silence(pcm, 4500, 4950);
        var points = PcmAudio.quietPoints(pcm, RATE);
        assertThat(points).hasSize(3);
        long previous = -2000;
        for (long point : points) {
            assertThat(point - previous).isGreaterThanOrEqualTo(1500);
            int index = Math.toIntExact(point * RATE / 1000) * 2;
            assertThat(pcm[index]).isZero();
            assertThat(pcm[index + 1]).isZero();
            previous = point;
        }
        assertThat(points.get(0)).isBetween(900L, 1250L);
        assertThat(points.get(1)).isBetween(2900L, 3300L);
        assertThat(points.get(2)).isBetween(4500L, 4950L);
    }

    @Test void loudAudioAndShortGapsDoNotCreatePausePoints() {
        byte[] pcm = tone(4000);
        assertThat(PcmAudio.quietPoints(pcm, RATE)).isEmpty();
        silence(pcm, 1000, 1100);
        silence(pcm, 2000, 2180);
        assertThat(PcmAudio.quietPoints(pcm, RATE)).isEmpty();
    }

    @Test void wavHeaderDeclaresMonoPcm16AndPreservesSamplesExactly() {
        byte[] pcm = tone(750);
        byte[] wav = PcmAudio.wav(pcm, RATE);
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(header.getInt(4)).isEqualTo(wav.length - 8);
        assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(header.getShort(20)).isEqualTo((short) 1);
        assertThat(header.getShort(22)).isEqualTo((short) 1);
        assertThat(header.getInt(24)).isEqualTo(RATE);
        assertThat(header.getInt(28)).isEqualTo(RATE * 2);
        assertThat(header.getShort(32)).isEqualTo((short) 2);
        assertThat(header.getShort(34)).isEqualTo((short) 16);
        assertThat(header.getInt(40)).isEqualTo(pcm.length);
        assertThat(Arrays.copyOfRange(wav, 44, wav.length)).isEqualTo(pcm);
        assertThat(PcmAudio.durationMillis(pcm, RATE)).isEqualTo(750);
    }

    @Test void invalidPcmCannotProduceMisleadingDurationsOrPauseMetadata() {
        assertThatThrownBy(() -> PcmAudio.durationMillis(new byte[1], RATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PcmAudio.quietPoints(new byte[1], RATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PcmAudio.wav(new byte[2], 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PcmAudio.durationMillis(new byte[2], -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] tone(int millis) {
        var out = ByteBuffer.allocate(RATE * millis / 1000 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < out.capacity() / 2; i++) {
            out.putShort((short) (Math.sin(2 * Math.PI * 440 * i / RATE) * 12000));
        }
        return out.array();
    }
    private void silence(byte[] pcm, int fromMillis, int toMillis) {
        Arrays.fill(pcm, RATE * fromMillis / 1000 * 2, RATE * toMillis / 1000 * 2, (byte) 0);
    }

    @Test
    void paddingAroundSpeechIsTrimmedToAShortMarginAndSilentAudioIsLeftAlone() {
        byte[] pcm = new byte[24000 * 2 * 2];                       // 2 s
        for (int i = 12000; i < 24000; i++) pcm[2 * i + 1] = (byte) (i % 2 == 0 ? 40 : -40);   // speech from 0.5 s to 1 s
        // 30 ms of margin before the first sound (sample 12000), 40 ms after the last (sample 23999): 570 ms kept.
        int[] kept = PcmAudio.speechRange(pcm, 24000);
        assertThat(kept).containsExactly(12000 - 720, 24000 + 960);
        byte[] trimmed = Arrays.copyOfRange(pcm, kept[0] * 2, kept[1] * 2);
        assertThat(PcmAudio.durationMillis(trimmed, 24000)).isEqualTo(570);
        // Speech that already starts and ends at the edges keeps everything.
        assertThat(PcmAudio.speechRange(trimmed, 24000)).containsExactly(0, trimmed.length / 2);
        assertThat(PcmAudio.speechRange(new byte[48000], 24000)).isNull();
    }

    @Test
    void theQuietestMomentBetweenTwoTimesIsFoundInTheSamplesNotAssumedFromTheTimes() {
        byte[] pcm = new byte[24000 * 2];                            // 1 s of tone...
        for (int i = 0; i < 24000; i++) pcm[2 * i + 1] = (byte) (i % 2 == 0 ? 40 : -40);
        for (int i = 12000; i < 13200; i++) pcm[2 * i + 1] = 0;      // ...with a 50 ms pause at 500 ms
        PcmAudio.Dip dip = PcmAudio.quietest(pcm, 24000, 400, 620);
        assertThat(dip.atMillis()).isBetween(505L, 545L);
        assertThat(dip.rms()).isZero();
        // Where the speaker never lets up, the level found says so.
        assertThat(PcmAudio.quietest(pcm, 24000, 100, 300).rms()).isGreaterThan(0.2);
        // Times outside the clip are clamped rather than read past its end.
        assertThat(PcmAudio.quietest(pcm, 24000, -500, 99_000)).isNotNull();
        assertThat(PcmAudio.speechRange(new byte[48000], 24000)).isNull();
        assertThat(PcmAudio.speechRange(pcm, 24000)).containsExactly(0, 24000);
    }
}
