package br.com.autocuidado.recurrence;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

public final class RecurrenceDateGenerator {

    private static final int MAX_WEEKS = 12;
    private static final int DAYS_PER_WEEK = DayOfWeek.values().length;
    // The API uses a four-digit year for civil dates.
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    private final Clock clock;

    public RecurrenceDateGenerator(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public List<LocalDate> generate(LocalDate startDate, int weeks, Collection<DayOfWeek> weekdays, ZoneId zone) {
        if (zone == null) {
            throw new InvalidRecurrenceException(Problem.MISSING_ZONE, "A time zone is required");
        }
        if (startDate == null || startDate.isBefore(LocalDate.now(clock.withZone(zone)))) {
            throw new InvalidRecurrenceException(Problem.INVALID_START_DATE, "Start date must be today or later");
        }
        if (weeks < 1 || weeks > MAX_WEEKS) {
            throw new InvalidRecurrenceException(Problem.INVALID_DURATION, "Duration must be between 1 and 12 weeks");
        }
        if (weekdays == null || weekdays.isEmpty()) {
            throw new InvalidRecurrenceException(Problem.INVALID_WEEKDAYS, "At least one weekday is required");
        }

        var selectedDays = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek weekday : weekdays) {
            if (weekday == null) {
                throw new InvalidRecurrenceException(Problem.INVALID_WEEKDAYS, "Selected weekdays cannot contain null");
            }
            selectedDays.add(weekday);
        }

        int totalDays = weeks * DAYS_PER_WEEK;
        if (startDate.isAfter(MAX_DATE.minusDays(totalDays - 1L))) {
            throw new InvalidRecurrenceException(Problem.DATE_RANGE_OVERFLOW,
                    "The end of the recurrence is outside the supported date range");
        }

        var dates = new ArrayList<LocalDate>(weeks * selectedDays.size());
        for (int offset = 0; offset < totalDays; offset++) {
            LocalDate date = startDate.plusDays(offset);
            if (selectedDays.contains(date.getDayOfWeek())) {
                dates.add(date);
            }
        }
        return List.copyOf(dates);
    }

    public enum Problem {
        MISSING_ZONE, INVALID_START_DATE, INVALID_DURATION, INVALID_WEEKDAYS, DATE_RANGE_OVERFLOW
    }

    public static final class InvalidRecurrenceException extends IllegalArgumentException {

        private final Problem problem;

        private InvalidRecurrenceException(Problem problem, String message) {
            super(message);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }
}
