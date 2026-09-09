package com.kael.playtime;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeFormat {

    private TimeFormat() {
    }

    public static String formatMillis(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        long hourPart = hours % 24;
        long minutePart = minutes % 60;
        if (days > 0) {
            return days + " 天 " + hourPart + " 小时 " + minutePart + " 分";
        }
        if (hours > 0) {
            return hours + " 小时 " + minutePart + " 分";
        }
        if (minutes > 0) {
            return minutes + " 分";
        }
        return Math.max(0, seconds) + " 秒";
    }

    public static String formatInstant(long millis, ZoneId zoneId) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(zoneId)
                .format(Instant.ofEpochMilli(millis));
    }
}
