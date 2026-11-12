package gunging.ootilities.goop.core;

import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.util.BitSet;
import java.util.Locale;

/**
 * A standard five-field cron expression: {@code minute hour day-of-month month day-of-week}.
 * <p>
 * Each field accepts {@code *}, a number, a range {@code a-b}, a step {@code *}{@code /n} or
 * {@code a-b/n}, and comma lists of those. Day of week is 0-7 (0 and 7 are Sunday) and also
 * accepts {@code SUN..SAT}; months accept {@code JAN..DEC}. As in classic cron, when both
 * day-of-month and day-of-week are restricted, a day matching either one matches.
 */
public final class CronExpression {

    private static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String[] DAYS = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    @NotNull private final String source;
    @NotNull private final BitSet minutes, hours, daysOfMonth, months, daysOfWeek;
    private final boolean domRestricted, dowRestricted;

    private CronExpression(@NotNull String source, @NotNull String[] f) {
        this.source = source;
        minutes = field(f[0], 0, 59, null, 0);
        hours = field(f[1], 0, 23, null, 0);
        daysOfMonth = field(f[2], 1, 31, null, 0);
        months = field(f[3], 1, 12, MONTHS, 1);
        daysOfWeek = field(f[4], 0, 7, DAYS, 0);
        if (daysOfWeek.get(7)) { daysOfWeek.set(0); }
        domRestricted = !f[2].equals("*");
        dowRestricted = !f[4].equals("*");
    }

    /**
     * @throws IllegalArgumentException with a readable reason when the expression is invalid
     */
    @NotNull public static CronExpression parse(@NotNull String expression) {
        String[] f = expression.trim().split("\\s+");
        if (f.length != 5) { throw new IllegalArgumentException("expected 5 fields (minute hour day month weekday), got " + f.length); }
        return new CronExpression(expression.trim(), f);
    }

    /** @return If this expression fires at the minute of this time */
    public boolean matches(@NotNull LocalDateTime t) {
        if (!minutes.get(t.getMinute()) || !hours.get(t.getHour()) || !months.get(t.getMonthValue())) { return false; }
        boolean dom = daysOfMonth.get(t.getDayOfMonth());
        boolean dow = daysOfWeek.get(t.getDayOfWeek().getValue() % 7);
        if (domRestricted && dowRestricted) { return dom || dow; }
        return dom && dow;
    }

    @NotNull private static BitSet field(@NotNull String text, int min, int max, String[] names, int nameOffset) {
        BitSet set = new BitSet(max + 1);
        for (String part : text.split(",")) {
            int step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                step = number(part.substring(slash + 1), null, 0, part);
                if (step <= 0) { throw new IllegalArgumentException("step must be positive in '" + part + "'"); }
                range = part.substring(0, slash);
            }
            int from, to;
            if (range.equals("*")) {
                from = min; to = max;
            } else if (range.indexOf('-') > 0) {
                from = number(range.substring(0, range.indexOf('-')), names, nameOffset, part);
                to = number(range.substring(range.indexOf('-') + 1), names, nameOffset, part);
            } else {
                from = number(range, names, nameOffset, part);
                to = slash >= 0 ? max : from;
            }
            if (from < min || to > max || from > to) {
                throw new IllegalArgumentException("'" + part + "' is outside " + min + "-" + max);
            }
            for (int v = from; v <= to; v += step) { set.set(v); }
        }
        return set;
    }

    private static int number(@NotNull String text, String[] names, int nameOffset, @NotNull String part) {
        if (names != null) {
            String upper = text.toUpperCase(Locale.ROOT);
            for (int i = 0; i < names.length; i++) { if (names[i].equals(upper)) { return i + nameOffset; } }
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a number in '" + part + "'");
        }
    }

    @Override public String toString() { return source; }
}
