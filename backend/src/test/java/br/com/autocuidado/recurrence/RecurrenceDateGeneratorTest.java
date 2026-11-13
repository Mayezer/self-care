package br.com.autocuidado.recurrence;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecurrenceDateGeneratorTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-09");

    private final RecurrenceDateGenerator generator = new RecurrenceDateGenerator(
            Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"), ZoneOffset.UTC));

    @Test
    void includesSelectedStartAndLastDayButExcludesNextWeek() {
        assertEquals(dates("2026-11-11", "2026-11-17"), generator.generate(
                LocalDate.parse("2026-11-11"), 1, List.of(DayOfWeek.TUESDAY, WEDNESDAY), SAO_PAULO));
    }

    @Test
    void matchesSpecificationForUnselectedStartInTheMiddleOfTheWeek() {
        assertEquals(dates("2026-11-12", "2026-11-16", "2026-11-19", "2026-11-23"), generator.generate(
                LocalDate.parse("2026-11-11"), 2, List.of(MONDAY, THURSDAY), SAO_PAULO));
    }

    @Test
    void crossesMonthAndYearInOrderWithoutDuplicatingDays() {
        assertEquals(dates("2026-12-31", "2027-01-02", "2027-01-07", "2027-01-09"), generator.generate(
                LocalDate.parse("2026-12-30"), 2, List.of(SATURDAY, THURSDAY, SATURDAY), SAO_PAULO));
    }

    @Test
    void includesLeapDayAndCrossesIntoMarch() {
        assertEquals(dates("2028-02-29", "2028-03-02"), generator.generate(
                LocalDate.parse("2028-02-27"), 1, List.of(DayOfWeek.TUESDAY, THURSDAY), SAO_PAULO));
    }

    @Test
    void supportsEightyFourOccurrencesAtTheDurationLimit() {
        List<LocalDate> result = generator.generate(TODAY, 12, EnumSet.allOf(DayOfWeek.class), SAO_PAULO);

        assertEquals(84, result.size());
        assertEquals(TODAY, result.getFirst());
        assertEquals(LocalDate.parse("2027-01-31"), result.getLast());
    }

    @Test
    void determinesTodayInTheSuppliedZone() {
        assertEquals(List.of(TODAY), generator.generate(TODAY, 1, List.of(MONDAY), SAO_PAULO));
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(TODAY, 1, List.of(MONDAY), ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 13})
    void rejectsWeeksOutsideTheAllowedRange(int weeks) {
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(TODAY, weeks, List.of(MONDAY), SAO_PAULO));
    }

    @Test
    void rejectsMissingStartDate() {
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(null, 1, List.of(MONDAY), SAO_PAULO));
    }

    @Test
    void rejectsMissingOrInvalidWeekdays() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(TODAY, 1, null, SAO_PAULO));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(TODAY, 1, List.of(), SAO_PAULO));
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(TODAY, 1, Arrays.asList(MONDAY, null), SAO_PAULO));
    }

    @Test
    void rejectsMissingTimeZone() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(TODAY, 1, List.of(MONDAY), null));
    }

    @Test
    void rejectsIntervalsBeyondTheSupportedDateRange() {
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(LocalDate.MAX, 1, List.of(MONDAY), SAO_PAULO));
    }

    private static List<LocalDate> dates(String... values) {
        return Arrays.stream(values).map(LocalDate::parse).toList();
    }
}
