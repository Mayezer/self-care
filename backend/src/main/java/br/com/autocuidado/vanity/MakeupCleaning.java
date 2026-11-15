package br.com.autocuidado.vanity;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import br.com.autocuidado.auth.AccountTimeZones;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MakeupCleaning {
    static final int DEFAULT_INTERVAL = 14;
    static final int MAX_INTERVAL = 365;
    private static final int LOOKAHEAD_DAYS = 84;
    private static final LocalDate LAST_DATE = LocalDate.of(9999, 12, 31);
    private static final String SELECT_SERIES = """
            SELECT s.*, (SELECT min(o.planned_date) FROM care_occurrences o
                         WHERE o.cleaning_series_id = s.id AND NOT o.cleaning_canceled
                           AND o.status = 'PLANNED' AND o.planned_date >= ?) AS next_date
            FROM makeup_cleaning_series s WHERE s.account_id = ?
            """;
    private static final RowMapper<Series> SERIES = (row, index) -> new Series(
            row.getLong("id"), Accessory.valueOf(row.getString("accessory")), row.getInt("interval_days"),
            row.getObject("start_date", LocalDate.class), row.getBoolean("active"), row.getInt("revision"),
            row.getObject("generated_through", LocalDate.class), row.getObject("next_date", LocalDate.class));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AccountTimeZones timeZones;
    private final TransactionTemplate refreshTransaction;

    MakeupCleaning(JdbcTemplate jdbc, Clock clock, AccountTimeZones timeZones, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.timeZones = timeZones;
        this.refreshTransaction = new TransactionTemplate(manager);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Settings settings(long account) {
        return settings(account, today(account));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Settings save(long account, Accessory accessory, int revision, int interval, LocalDate start) {
        lockAccount(account);
        LocalDate today = today(account);
        var current = series(account, accessory, today);
        if (current != null && current.active && current.intervalDays == interval && current.startDate.equals(start)
                && (revision == current.revision || revision == current.revision - 1)) {
            return settings(account, today);
        }
        verifyRevision(current, revision);
        if (start.isBefore(today) || start.isAfter(LAST_DATE.minusDays(interval))) {
            throw new InvalidDate();
        }

        long id;
        if (current == null) {
            id = jdbc.queryForObject("""
                    INSERT INTO makeup_cleaning_series (account_id, accessory, interval_days, start_date, active, revision)
                    VALUES (?, ?, ?, ?, true, 1) RETURNING id
                    """, Long.class, account, accessory.name(), interval, start);
        } else {
            id = current.id;
            jdbc.update("""
                    UPDATE makeup_cleaning_series SET active = true, interval_days = ?, start_date = ?,
                        revision = revision + 1, generated_through = NULL WHERE id = ?
                    """, interval, start, id);
            // Retain the occurrence identity and its delivery history even when it leaves the schedule.
            jdbc.update("""
                    UPDATE care_occurrences SET cleaning_canceled = true
                    WHERE cleaning_series_id = ? AND NOT cleaning_canceled
                      AND (planned_date < ? OR (planned_date - CAST(? AS date)) % ? <> 0)
                    """, id, start, start, interval);
        }
        extend(account, id, accessory, interval, start, null, today);
        return settings(account, today);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Settings pause(long account, Accessory accessory, int revision) {
        lockAccount(account);
        LocalDate today = today(account);
        var current = series(account, accessory, today);
        if (current == null) throw new RevisionConflict();
        if (!current.active && (revision == current.revision || revision == current.revision - 1)) {
            return settings(account, today);
        }
        verifyRevision(current, revision);
        jdbc.update("UPDATE makeup_cleaning_series SET active = false, revision = revision + 1 WHERE id = ?", current.id);
        jdbc.update("""
                UPDATE care_occurrences SET cleaning_canceled = true
                WHERE cleaning_series_id = ? AND NOT cleaning_canceled
                """, current.id);
        return settings(account, today);
    }

    // Keep upcoming occurrences available for Agenda and the existing reminder dispatcher even without a visit.
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void refresh() {
        LocalDate threshold = LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(LOOKAHEAD_DAYS - 2);
        var accounts = jdbc.queryForList("""
                SELECT DISTINCT account_id FROM makeup_cleaning_series
                WHERE active AND generated_through < ? AND generated_through < ?
                """, Long.class, threshold, LAST_DATE);
        for (long account : accounts) {
            refreshTransaction.executeWithoutResult(tx -> {
                lockAccount(account);
                LocalDate today = today(account);
                for (Series series : allSeries(account, today)) {
                    if (series.active) {
                        extend(account, series.id, series.accessory, series.intervalDays,
                                series.startDate, series.generatedThrough, today);
                    }
                }
            });
        }
    }

    private void extend(long account, long id, Accessory accessory, int interval, LocalDate start,
                        LocalDate generatedThrough, LocalDate today) {
        LocalDate horizon = today.plusDays(LOOKAHEAD_DAYS);
        long daysToHorizon = Math.max(0, ChronoUnit.DAYS.between(start, horizon));
        long through = Math.min(ChronoUnit.DAYS.between(start, LAST_DATE),
                (daysToHorizon / interval + 1) * interval);
        long first = generatedThrough == null ? 0
                : (ChronoUnit.DAYS.between(start, generatedThrough) / interval + 1) * interval;
        if (first <= through) {
            jdbc.update("""
                    INSERT INTO care_occurrences (account_id, cleaning_series_id, category, title, planned_date, status)
                    SELECT ?, ?, 'MAKEUP', ?, CAST(? AS date) + step.days, 'PLANNED'
                    FROM generate_series(?, ?, ?) AS step(days)
                    ON CONFLICT (cleaning_series_id, planned_date) DO UPDATE
                        SET cleaning_canceled = false, push_planned_since = clock_timestamp()
                        WHERE care_occurrences.cleaning_canceled
                    """, account, id, accessory.title, start, Math.toIntExact(first), Math.toIntExact(through), interval);
            jdbc.update("UPDATE makeup_cleaning_series SET generated_through = ? WHERE id = ?", start.plusDays(through), id);
        }
    }

    private void lockAccount(long account) {
        jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", Long.class, account);
    }

    private LocalDate today(long account) {
        return LocalDate.now(clock.withZone(timeZones.current(account)));
    }

    private List<Series> allSeries(long account, LocalDate today) {
        return jdbc.query(SELECT_SERIES + " ORDER BY s.accessory", SERIES, today, account);
    }

    private Series series(long account, Accessory accessory, LocalDate today) {
        return jdbc.query(SELECT_SERIES + " AND s.accessory = ?", SERIES, today, account, accessory.name())
                .stream().findFirst().orElse(null);
    }

    private Settings settings(long account, LocalDate today) {
        return new Settings(today, DEFAULT_INTERVAL, MAX_INTERVAL,
                allSeries(account, today).stream().map(s -> new Schedule(s.accessory, s.intervalDays,
                        s.active, s.revision, s.nextDate)).toList());
    }

    private static void verifyRevision(Series current, int revision) {
        if (revision != (current == null ? 0 : current.revision)) throw new RevisionConflict();
    }

    enum Accessory {
        BRUSHES("Limpeza de pincéis"), SPONGES("Limpeza de esponjas");

        private final String title;

        Accessory(String title) {
            this.title = title;
        }
    }

    record Settings(LocalDate today, int defaultInterval, int maxInterval, List<Schedule> schedules) {}
    record Schedule(Accessory accessory, int intervalDays, boolean active, int revision, LocalDate nextDate) {}
    private record Series(long id, Accessory accessory, int intervalDays, LocalDate startDate, boolean active,
                          int revision, LocalDate generatedThrough, LocalDate nextDate) {}

    static class RevisionConflict extends RuntimeException {}
    static class InvalidDate extends RuntimeException {}
}
