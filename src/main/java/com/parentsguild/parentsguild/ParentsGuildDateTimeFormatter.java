package com.parentsguild.parentsguild;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.Locale;

final class ParentsGuildDateTimeFormatter
{
    private ParentsGuildDateTimeFormatter()
    {
    }

    static String formatDateTime(Instant instant, boolean dayFirstDates, boolean twentyFourHourTime)
    {
        return dateTimeFormatter(dayFirstDates, twentyFourHourTime).format(instant);
    }

    static DateTimeFormatter dateTimeFormatter(boolean dayFirstDates, boolean twentyFourHourTime)
    {
        final Locale locale = Locale.getDefault();
        final DateTimeFormatter date = dayFirstDates
            ? DateTimeFormatter.ofPattern("dd/MM/yy", locale)
            : DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale);
        final DateTimeFormatter time = twentyFourHourTime
            ? DateTimeFormatter.ofPattern("HH:mm", locale)
            : DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale);
        return new DateTimeFormatterBuilder()
            .append(date)
            .appendLiteral(' ')
            .append(time)
            .toFormatter(locale)
            .withZone(ZoneId.systemDefault());
    }

    static DateTimeFormatter calendarDayFormatter(boolean dayFirstDates)
    {
        final Locale locale = Locale.getDefault();
        if (dayFirstDates)
        {
            return DateTimeFormatter.ofPattern("dd/MM", locale).withZone(ZoneId.systemDefault());
        }

        final String sample = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            .withLocale(locale)
            .format(LocalDate.of(2026, 11, 22));
        final int monthIndex = sample.indexOf("11");
        final int dayIndex = sample.indexOf("22");
        final String pattern = dayIndex >= 0 && monthIndex >= 0 && dayIndex < monthIndex ? "d/M" : "M/d";
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault());
    }
}
