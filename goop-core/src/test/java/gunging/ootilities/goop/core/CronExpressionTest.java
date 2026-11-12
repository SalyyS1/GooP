package gunging.ootilities.goop.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CronExpressionTest {

    // 2026-11-13 is a Friday
    private static LocalDateTime at(int day, int hour, int minute) { return LocalDateTime.of(2026, 11, day, hour, minute); }

    @Test
    void fixedTime() {
        CronExpression c = CronExpression.parse("0 20 * * *");
        assertTrue(c.matches(at(13, 20, 0)));
        assertFalse(c.matches(at(13, 20, 1)));
        assertFalse(c.matches(at(13, 19, 0)));
    }

    @Test
    void stepsRangesAndLists() {
        CronExpression c = CronExpression.parse("*/15 8-10,18 * * *");
        assertTrue(c.matches(at(13, 8, 45)));
        assertTrue(c.matches(at(13, 18, 0)));
        assertFalse(c.matches(at(13, 11, 0)));
        assertFalse(c.matches(at(13, 9, 10)));
        assertTrue(CronExpression.parse("5/20 * * * *").matches(at(13, 1, 25)));
    }

    @Test
    void weekdaysAndNames() {
        CronExpression weekend = CronExpression.parse("0 12 * * SAT,SUN");
        assertFalse(weekend.matches(at(13, 12, 0)));     // Friday
        assertTrue(weekend.matches(at(14, 12, 0)));      // Saturday
        assertTrue(weekend.matches(at(15, 12, 0)));      // Sunday
        assertTrue(CronExpression.parse("0 12 * * 7").matches(at(15, 12, 0)));
        assertTrue(CronExpression.parse("0 0 1 NOV *").matches(at(1, 0, 0)));
    }

    @Test
    void dayOfMonthOrWeekdayWhenBothRestricted() {
        CronExpression c = CronExpression.parse("0 0 1 * MON");
        assertTrue(c.matches(at(1, 0, 0)));   // the 1st
        assertTrue(c.matches(at(16, 0, 0)));  // a Monday
        assertFalse(c.matches(at(17, 0, 0)));
    }

    @Test
    void rejectsBadExpressions() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("0 20 * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("61 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("*/0 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("x * * * *"));
    }
}
