// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.tools.date;

import java.text.DateFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.tools.CheckParameterUtil;
import org.openstreetmap.josm.tools.UncheckedParseException;

/**
 * A static utility class dealing with:
 * <ul>
 * <li>parsing XML date quickly and formatting a date to the XML UTC format regardless of current locale</li>
 * <li>providing a single entry point for formatting dates to be displayed in JOSM GUI, based on user preferences</li>
 * </ul>
 * @author nenik
 */
public final class DateUtils {

    /**
     * The UTC time zone.
     */
    public static final TimeZone UTC = TimeZone.getTimeZone(ZoneOffset.UTC);

    /**
     * Property to enable display of ISO dates globally.
     * @since 7299
     */
    public static final BooleanProperty PROP_ISO_DATES = new BooleanProperty("iso.dates", false);

    /**
     * Constructs a new {@code DateUtils}.
     */
    private DateUtils() {
        // Hide default constructor for utils classes
    }

    /**
     * Parses XML date quickly, regardless of current locale.
     * @param str The XML date as string
     * @return The date
     * @throws UncheckedParseException if the date does not match any of the supported date formats
     * @throws DateTimeException if the value of any field is out of range, or if the day-of-month is invalid for the month-year
     */
    public static Date fromString(String str) {
        return new Date(tsFromString(str));
    }

    /**
     * Parses XML date quickly, regardless of current locale.
     * @param str The XML date as string
     * @return The date in milliseconds since epoch
     * @throws UncheckedParseException if the date does not match any of the supported date formats
     * @throws DateTimeException if the value of any field is out of range, or if the day-of-month is invalid for the month-year
     */
    public static long tsFromString(String str) {
        return parseInstant(str).toEpochMilli();
    }

    /**
     * Parses the given date string quickly, regardless of current locale.
     * @param str the date string
     * @return the parsed instant
     * @throws UncheckedParseException if the date does not match any of the supported date formats
     */
    public static Instant parseInstant(String str) {
        // "2007-07-25T09:26:24{Z|{+|-}01[:00]}"
        if (checkLayout(str, "xxxx-xx-xx") ||
                checkLayout(str, "xxxx-xx") ||
                checkLayout(str, "xxxx")) {
            final ZonedDateTime local = ZonedDateTime.of(
                    parsePart4(str, 0),
                    str.length() > 5 ? parsePart2(str, 5) : 1,
                    str.length() > 8 ? parsePart2(str, 8) : 1,
                    0, 0, 0, 0, ZoneOffset.UTC);
            return local.toInstant();
        } else if (checkLayout(str, "xxxx-xx-xxTxx:xx:xxZ") ||
                checkLayout(str, "xxxx-xx-xxTxx:xx:xx") ||
                checkLayout(str, "xxxx:xx:xx xx:xx:xx") ||
                checkLayout(str, "xxxx/xx/xx xx:xx:xx") ||
                checkLayout(str, "xxxx-xx-xx xx:xx:xxZ") ||
                checkLayout(str, "xxxx-xx-xx xx:xx:xx UTC") ||
                checkLayout(str, "xxxx-xx-xxTxx:xx:xx+xx") ||
                checkLayout(str, "xxxx-xx-xxTxx:xx:xx-xx") ||
                checkLayout(str, "xxxx-xx-xxTxx:xx:xx+xx:00") ||
                checkLayout(str, "xxxx-xx-xxTxx:xx:xx-xx:00")) {
            final ZonedDateTime local = ZonedDateTime.of(
                parsePart4(str, 0),
                parsePart2(str, 5),
                parsePart2(str, 8),
                parsePart2(str, 11),
                parsePart2(str, 14),
                parsePart2(str, 17),
                0,
                ZoneOffset.UTC
            );
            if (str.length() == 22 || str.length() == 25) {
                final int plusHr = parsePart2(str, 20);
                return local.plusHours(str.charAt(19) == '+' ? -plusHr : plusHr).toInstant();
            }
            return local.toInstant();
        } else if (checkLayout(str, "xxxx-xx-xxTxx:xx:xx.xxxZ") ||
                checkLayout(str, "xxxx-xx-xxTxx:xx:xx.xxx") ||
                checkLayout(str, "xxxx:xx:xx xx:xx:xx.xxx") ||
                checkLayout(str, "xxxx/xx/xx xx:xx:xx.xxx") ||
                checkLayout(str, "xxxx-xx-xxTxx:xx:xx.xxx+xx:00") ||
                checkLayout(str, "xxxx-xx-xxTxx:xx:xx.xxx-xx:00")) {
            final ZonedDateTime local = ZonedDateTime.of(
                parsePart4(str, 0),
                parsePart2(str, 5),
                parsePart2(str, 8),
                parsePart2(str, 11),
                parsePart2(str, 14),
                parsePart2(str, 17),
                parsePart3(str, 20) * 1_000_000,
                ZoneOffset.UTC
            );
            if (str.length() == 29) {
                final int plusHr = parsePart2(str, 24);
                return local.plusHours(str.charAt(23) == '+' ? -plusHr : plusHr).toInstant();
            }
            return local.toInstant();
        } else if (checkLayout(str, "xxxx/xx/xx xx:xx:xx.xxxxxx")) {
            return ZonedDateTime.of(
                parsePart4(str, 0),
                parsePart2(str, 5),
                parsePart2(str, 8),
                parsePart2(str, 11),
                parsePart2(str, 14),
                parsePart2(str, 17),
                parsePart6(str, 20) * 1_000,
                ZoneOffset.UTC
            ).toInstant();
        } else {
            // example date format "18-AUG-08 13:33:03"
            SimpleDateFormat f = new SimpleDateFormat("dd-MMM-yy HH:mm:ss");
            Date d = f.parse(str, new ParsePosition(0));
            if (d != null)
                return d.toInstant();
        }

        try {
            // slow path for fractional seconds different from millisecond precision
            return ZonedDateTime.parse(str).toInstant();
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new UncheckedParseException("The date string (" + str + ") could not be parsed.", ex);
        }
    }

    /**
     * Formats a date to the XML UTC format regardless of current locale.
     * @param timestamp number of seconds since the epoch
     * @return The formatted date
     * @since 14055
     */
    public static String fromTimestamp(long timestamp) {
        return fromTimestampInMillis(TimeUnit.SECONDS.toMillis(timestamp));
    }

    /**
     * Formats a date to the XML UTC format regardless of current locale.
     * @param timestamp number of milliseconds since the epoch
     * @return The formatted date
     * @since 14434
     */
    public static String fromTimestampInMillis(long timestamp) {
        final ZonedDateTime temporal = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(temporal);
    }

    /**
     * Formats a date to the XML UTC format regardless of current locale.
     * @param timestamp number of seconds since the epoch
     * @return The formatted date
     */
    public static String fromTimestamp(int timestamp) {
        return fromTimestamp(Integer.toUnsignedLong(timestamp));
    }

    /**
     * Formats a date to the XML UTC format regardless of current locale.
     * @param date The date to format
     * @return The formatted date
     */
    public static String fromDate(Date date) {
        final ZonedDateTime temporal = date.toInstant().atZone(ZoneOffset.UTC);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(temporal);
    }

    /**
     * Null-safe date cloning method.
     * @param d date to clone, or null
     * @return cloned date, or null
     * @since 11878
     */
    public static Date cloneDate(Date d) {
        return d != null ? (Date) d.clone() : null;
    }

    private static boolean checkLayout(String text, String pattern) {
        if (text.length() != pattern.length())
            return false;
        for (int i = 0; i < pattern.length(); i++) {
            char pc = pattern.charAt(i);
            char tc = text.charAt(i);
            if (pc == 'x' && Character.isDigit(tc))
                continue;
            else if (pc == 'x' || pc != tc)
                return false;
        }
        return true;
    }

    private static int num(char c) {
        return c - '0';
    }

    private static int parsePart2(String str, int off) {
        return 10 * num(str.charAt(off)) + num(str.charAt(off + 1));
    }

    private static int parsePart3(String str, int off) {
        return 100 * num(str.charAt(off)) + 10 * num(str.charAt(off + 1)) + num(str.charAt(off + 2));
    }

    private static int parsePart4(String str, int off) {
        return 1000 * num(str.charAt(off)) + 100 * num(str.charAt(off + 1)) + 10 * num(str.charAt(off + 2)) + num(str.charAt(off + 3));
    }

    private static int parsePart6(String str, int off) {
        return 100000 * num(str.charAt(off))
              + 10000 * num(str.charAt(off + 1))
               + 1000 * num(str.charAt(off + 2))
                + 100 * num(str.charAt(off + 3))
                 + 10 * num(str.charAt(off + 4))
                      + num(str.charAt(off + 5));
    }

    /**
     * Returns a new {@code SimpleDateFormat} for date only, according to <a href="https://en.wikipedia.org/wiki/ISO_8601">ISO 8601</a>.
     * @return a new ISO 8601 date format, for date only.
     * @since 7299
     */
    public static SimpleDateFormat newIsoDateFormat() {
        return new SimpleDateFormat("yyyy-MM-dd");
    }

    /**
     * Returns the date format to be used for current user, based on user preferences.
     * @param dateStyle The date style as described in {@link DateFormat#getDateInstance}. Ignored if "ISO dates" option is set
     * @return The date format
     * @since 7299
     */
    public static DateFormat getDateFormat(int dateStyle) {
        if (PROP_ISO_DATES.get()) {
            return newIsoDateFormat();
        } else {
            return DateFormat.getDateInstance(dateStyle, Locale.getDefault());
        }
    }

    /**
     * Returns the date formatter to be used for current user, based on user preferences.
     * @param dateStyle The date style. Ignored if "ISO dates" option is set.
     * @return The date format
     */
    public static DateTimeFormatter getDateFormatter(FormatStyle dateStyle) {
        DateTimeFormatter formatter = PROP_ISO_DATES.get()
                ? DateTimeFormatter.ISO_LOCAL_DATE
                : DateTimeFormatter.ofLocalizedDate(dateStyle);
        return formatter.withZone(ZoneId.systemDefault());
    }

    /**
     * Formats a date to be displayed to current user, based on user preferences.
     * @param date The date to display. Must not be {@code null}
     * @param dateStyle The date style as described in {@link DateFormat#getDateInstance}. Ignored if "ISO dates" option is set
     * @return The formatted date
     * @since 7299
     */
    public static String formatDate(Date date, int dateStyle) {
        CheckParameterUtil.ensureParameterNotNull(date, "date");
        return getDateFormat(dateStyle).format(date);
    }

    /**
     * Returns the time format to be used for current user, based on user preferences.
     * @param timeStyle The time style as described in {@link DateFormat#getTimeInstance}. Ignored if "ISO dates" option is set
     * @return The time format
     * @since 7299
     */
    public static DateFormat getTimeFormat(int timeStyle) {
        if (PROP_ISO_DATES.get()) {
            // This is not strictly conform to ISO 8601. We just want to avoid US-style times such as 3.30pm
            return new SimpleDateFormat("HH:mm:ss");
        } else {
            return DateFormat.getTimeInstance(timeStyle, Locale.getDefault());
        }
    }

    /**
     * Returns the time formatter to be used for current user, based on user preferences.
     * @param timeStyle The time style. Ignored if "ISO dates" option is set.
     * @return The time format
     */
    public static DateTimeFormatter getTimeFormatter(FormatStyle timeStyle) {
        DateTimeFormatter formatter = PROP_ISO_DATES.get()
                ? DateTimeFormatter.ISO_LOCAL_TIME
                : DateTimeFormatter.ofLocalizedTime(timeStyle);
        return formatter.withZone(ZoneId.systemDefault());
    }

    /**
     * Formats a time to be displayed to current user, based on user preferences.
     * @param time The time to display. Must not be {@code null}
     * @param timeStyle The time style as described in {@link DateFormat#getTimeInstance}. Ignored if "ISO dates" option is set
     * @return The formatted time
     * @since 7299
     */
    public static String formatTime(Date time, int timeStyle) {
        CheckParameterUtil.ensureParameterNotNull(time, "time");
        return getTimeFormat(timeStyle).format(time);
    }

    /**
     * Returns the date/time format to be used for current user, based on user preferences.
     * @param dateStyle The date style as described in {@link DateFormat#getDateTimeInstance}. Ignored if "ISO dates" option is set
     * @param timeStyle The time style as described in {@code DateFormat.getDateTimeInstance}. Ignored if "ISO dates" option is set
     * @return The date/time format
     * @since 7299
     */
    public static DateFormat getDateTimeFormat(int dateStyle, int timeStyle) {
        if (PROP_ISO_DATES.get()) {
            // This is not strictly conform to ISO 8601. We just want to avoid US-style times such as 3.30pm
            // and we don't want to use the 'T' separator as a space character is much more readable
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        } else {
            return DateFormat.getDateTimeInstance(dateStyle, timeStyle, Locale.getDefault());
        }
    }

    /**
     * Differs from {@link DateTimeFormatter#ISO_LOCAL_DATE_TIME} by using ' ' instead of 'T' to separate date/time.
     */
    private static final DateTimeFormatter ISO_LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .append(DateTimeFormatter.ISO_LOCAL_DATE)
                .appendLiteral(' ')
                .append(DateTimeFormatter.ISO_LOCAL_TIME)
                .toFormatter();

    /**
     * Returns the date/time formatter to be used for current user, based on user preferences.
     * @param dateStyle The date style. Ignored if "ISO dates" option is set.
     * @param timeStyle The time style. Ignored if "ISO dates" option is set.
     * @return The date/time format
     */
    public static DateTimeFormatter getDateTimeFormatter(FormatStyle dateStyle, FormatStyle timeStyle) {
        DateTimeFormatter formatter = PROP_ISO_DATES.get()
                ? ISO_LOCAL_DATE_TIME
                : DateTimeFormatter.ofLocalizedDateTime(dateStyle, timeStyle);
        return formatter.withZone(ZoneId.systemDefault());
    }

    /**
     * Formats a date/time to be displayed to current user, based on user preferences.
     * @param datetime The date/time to display. Must not be {@code null}
     * @param dateStyle The date style as described in {@link DateFormat#getDateTimeInstance}. Ignored if "ISO dates" option is set
     * @param timeStyle The time style as described in {@code DateFormat.getDateTimeInstance}. Ignored if "ISO dates" option is set
     * @return The formatted date/time
     * @since 7299
     */
    public static String formatDateTime(Date datetime, int dateStyle, int timeStyle) {
        CheckParameterUtil.ensureParameterNotNull(datetime, "datetime");
        return getDateTimeFormat(dateStyle, timeStyle).format(datetime);
    }
}
