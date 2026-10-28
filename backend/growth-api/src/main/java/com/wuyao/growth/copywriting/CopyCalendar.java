package com.wuyao.growth.copywriting;

import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;

import java.time.LocalDate;
import java.util.*;

/**
 * 「今日宜发」的日期事实：节气、节日都由农历库按日期算出，模型只负责解读，不推日期。
 * 法定节假日的调休每年由国务院公布，这里不算。
 */
final class CopyCalendar {
    private static final String[] MONTHS = {"一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月"};
    private static final String[] WEEKDAYS = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
    /** 每个节气所在时段的两字说法，和节气名拼成卡片上竖排的四个字，例如「霜降秋末」。 */
    private static final Map<String, String> SEASONS = Map.ofEntries(
            Map.entry("立春", "春始"), Map.entry("雨水", "春初"), Map.entry("惊蛰", "春雷"), Map.entry("春分", "春半"),
            Map.entry("清明", "春深"), Map.entry("谷雨", "春末"), Map.entry("立夏", "夏始"), Map.entry("小满", "夏初"),
            Map.entry("芒种", "夏忙"), Map.entry("夏至", "夏半"), Map.entry("小暑", "夏热"), Map.entry("大暑", "夏盛"),
            Map.entry("立秋", "秋始"), Map.entry("处暑", "秋初"), Map.entry("白露", "秋凉"), Map.entry("秋分", "秋半"),
            Map.entry("寒露", "秋深"), Map.entry("霜降", "秋末"), Map.entry("立冬", "冬始"), Map.entry("小雪", "冬初"),
            Map.entry("大雪", "冬寒"), Map.entry("冬至", "冬半"), Map.entry("小寒", "冬深"), Map.entry("大寒", "冬末"));
    /** 农历库没有收的商家常用营销节点。 */
    private static final Map<String, String> MARKETING = Map.of("05-20", "520", "11-11", "双十一", "12-12", "双十二", "12-24", "平安夜");
    /** 这些日子不宜喜庆促销：清明当天和国家公祭日。 */
    private static final Set<String> SOLEMN_DATES = Set.of("12-13");
    static final int UPCOMING_DAYS = 21;

    record Upcoming(String name, int days) {}

    /**
     * @param term     当前所处的节气（今天是节气就是今天这个）
     * @param todays   今天的节气或节日
     * @param upcoming 未来三周内的节气和节日
     * @param solemn   今天是否不宜喜庆促销
     */
    record Facts(LocalDate date, String month, String weekday, String term, String termLabel, List<String> todays,
                 List<Upcoming> upcoming, boolean solemn) {

        Map<String, Object> asMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("date", date.toString());
            map.put("day", date.getDayOfMonth());
            map.put("month", month);
            map.put("weekday", weekday);
            map.put("term", term);
            map.put("termLabel", termLabel);
            map.put("todays", todays);
            map.put("upcoming", upcoming.stream().map(u -> Map.of("name", u.name(), "days", u.days())).toList());
            map.put("solemn", solemn);
            return map;
        }

        String describe() {
            var text = new StringBuilder("今天是 ").append(date).append("，").append(month).append(date.getDayOfMonth()).append("日，")
                    .append(weekday).append("；当前节气：").append(term);
            if (!todays.isEmpty()) text.append("；今天是：").append(String.join("、", todays));
            if (!upcoming.isEmpty()) {
                text.append("；接下来：").append(String.join("、", upcoming.stream().map(u -> u.name() + "（" + u.days() + " 天后）").toList()));
            }
            if (solemn) text.append("；今天是肃穆的日子，不宜喜庆促销");
            return text.toString();
        }
    }

    private CopyCalendar() {}

    static Facts of(LocalDate date) {
        Lunar lunar = solar(date).getLunar();
        String term = lunar.getPrevJieQi(true).getName();
        var todays = new ArrayList<>(names(date));
        var upcoming = new ArrayList<Upcoming>();
        for (int days = 1; days <= UPCOMING_DAYS; days++) {
            for (String name : names(date.plusDays(days))) upcoming.add(new Upcoming(name, days));
        }
        boolean solemn = todays.contains("清明") || SOLEMN_DATES.contains(key(date));
        if (SOLEMN_DATES.contains(key(date))) todays.add("国家公祭日");
        return new Facts(date, MONTHS[date.getMonthValue() - 1], WEEKDAYS[date.getDayOfWeek().getValue() - 1], term,
                term + SEASONS.getOrDefault(term, ""), List.copyOf(todays), List.copyOf(upcoming), solemn);
    }

    /** 这一天的节气和节日，去重保序。 */
    private static List<String> names(LocalDate date) {
        Solar solar = solar(date);
        Lunar lunar = solar.getLunar();
        var names = new LinkedHashSet<String>();
        if (!lunar.getJieQi().isEmpty()) names.add(lunar.getJieQi());
        names.addAll(lunar.getFestivals());
        names.addAll(solar.getFestivals());
        String marketing = MARKETING.get(key(date));
        if (marketing != null) names.add(marketing);
        return List.copyOf(names);
    }

    private static Solar solar(LocalDate date) { return Solar.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth()); }

    private static String key(LocalDate date) { return String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth()); }
}
