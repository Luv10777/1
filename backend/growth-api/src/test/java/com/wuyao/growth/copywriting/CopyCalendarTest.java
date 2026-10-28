package com.wuyao.growth.copywriting;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CopyCalendarTest {
    @Test void anOrdinaryDayKnowsItsTermAndWhatIsComing() {
        var facts = CopyCalendar.of(LocalDate.of(2026, 10, 28));
        assertThat(facts.month()).isEqualTo("十月");
        assertThat(facts.weekday()).isEqualTo("星期三");
        assertThat(facts.term()).isEqualTo("霜降");
        assertThat(facts.termLabel()).isEqualTo("霜降秋末");
        assertThat(facts.todays()).isEmpty();
        assertThat(facts.upcoming()).extracting(CopyCalendar.Upcoming::name).contains("立冬");
        assertThat(facts.solemn()).isFalse();
    }

    @Test void lunarFestivalsComeFromTheCalendarNotFromAGuess() {
        // 2026 年中秋是 9 月 25 日，2027 年春节是 2 月 6 日。
        assertThat(CopyCalendar.of(LocalDate.of(2026, 9, 25)).todays()).contains("中秋节");
        assertThat(CopyCalendar.of(LocalDate.of(2027, 2, 6)).todays()).contains("春节");
        assertThat(CopyCalendar.of(LocalDate.of(2026, 9, 20)).upcoming())
                .contains(new CopyCalendar.Upcoming("中秋节", 5));
    }

    @Test void solemnDaysAreMarked() {
        var qingming = CopyCalendar.of(LocalDate.of(2026, 4, 5));
        assertThat(qingming.todays()).contains("清明");
        assertThat(qingming.solemn()).isTrue();
        var memorial = CopyCalendar.of(LocalDate.of(2026, 12, 13));
        assertThat(memorial.solemn()).isTrue();
        assertThat(memorial.todays()).contains("国家公祭日");
    }

    @Test void theTermOfTheDayItStartsCounts() {
        assertThat(CopyCalendar.of(LocalDate.of(2026, 11, 7)).term()).isEqualTo("立冬");
        assertThat(CopyCalendar.of(LocalDate.of(2026, 11, 6)).term()).isEqualTo("霜降");
    }
}
