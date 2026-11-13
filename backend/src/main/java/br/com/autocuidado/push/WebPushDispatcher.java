package br.com.autocuidado.push;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static br.com.autocuidado.push.WebPushTransport.State.CANCELED;

@Component
class WebPushDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(WebPushDispatcher.class);
    private static final Duration DELIVERY_WINDOW = Duration.ofMinutes(10);
    private static final int BATCH_SIZE = 25;
    private static final String CANDIDATE = """
            SELECT r.occurrence_id, s.id AS subscription_id, s.endpoint, r.planned_date, r.reminder_time,
                   r.time_zone, GREATEST(r.eligible_since, s.enabled_at) AS eligible_since
            FROM web_push_reminders r
            JOIN web_push_subscriptions s ON s.account_id = r.account_id AND s.active
            """;
    private static final RowMapper<Reminder> REMINDER = (row, index) -> new Reminder(
            row.getLong("occurrence_id"), row.getLong("subscription_id"), row.getString("endpoint"),
            row.getObject("planned_date", LocalDate.class), row.getObject("reminder_time", LocalTime.class),
            ZoneId.of(row.getString("time_zone")), row.getObject("eligible_since", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final WebPushTransport transport;
    private final TransactionTemplate transaction;

    WebPushDispatcher(JdbcTemplate jdbc, Clock clock, WebPushTransport transport, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transport = transport;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${app.agenda.web-push-poll-ms:5000}")
    void dispatch() {
        if (!transport.available()) return;
        jdbc.update("""
                UPDATE web_push_attempts SET state = 'UNCERTAIN', finished_at = ?
                WHERE id IN (
                    SELECT id FROM web_push_attempts WHERE state = 'SENDING' AND started_at < ?
                    FOR UPDATE SKIP LOCKED
                )
                """, timestamp(clock.instant()), timestamp(clock.instant().minusSeconds(120)));

        var utcDate = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<Reminder> candidates = jdbc.query(CANDIDATE + """
                WHERE r.planned_date BETWEEN ? AND ?
                  AND NOT EXISTS (
                      SELECT 1 FROM web_push_attempts t
                      WHERE t.occurrence_id = r.occurrence_id AND t.subscription_id = s.id
                        AND t.state IN ('SENDING', 'ACCEPTED', 'UNCERTAIN')
                  )
                ORDER BY r.planned_date, r.occurrence_id, s.id
                """, REMINDER, utcDate.minusDays(1), utcDate.plusDays(1));

        int dispatched = 0;
        for (Reminder candidate : candidates) {
            Instant due = due(candidate, clock.instant());
            if (due == null) continue;
            Long claim = transaction.execute(status -> claim(candidate, due));
            if (claim == null) continue;
            transaction.executeWithoutResult(status -> deliver(claim, candidate, due));
            if (++dispatched == BATCH_SIZE) break;
        }
    }

    private Long claim(Reminder candidate, Instant scheduled) {
        var locked = jdbc.queryForList("""
                SELECT id FROM web_push_subscriptions WHERE id = ? AND active FOR UPDATE SKIP LOCKED
                """, Long.class, candidate.subscriptionId());
        if (locked.isEmpty()) return null;
        Instant now = clock.instant();
        return jdbc.query("""
                INSERT INTO web_push_attempts (subscription_id, occurrence_id, scheduled_at, started_at, state)
                SELECT ?, ?, ?, ?, 'SENDING'
                WHERE EXISTS (SELECT 1 FROM care_occurrences WHERE id = ?)
                  AND NOT EXISTS (
                      SELECT 1 FROM web_push_attempts WHERE subscription_id = ? AND occurrence_id = ?
                      AND (state IN ('SENDING', 'ACCEPTED', 'UNCERTAIN')
                        OR (scheduled_at = ? AND (state = 'REJECTED' OR retry_at > ?)))
                  )
                ON CONFLICT DO NOTHING RETURNING id
                """, (row, index) -> row.getLong("id"),
                candidate.subscriptionId(), candidate.occurrenceId(), timestamp(scheduled), timestamp(now),
                candidate.occurrenceId(), candidate.subscriptionId(), candidate.occurrenceId(),
                timestamp(scheduled), timestamp(now)).stream().findFirst().orElse(null);
    }

    private void deliver(long claim, Reminder candidate, Instant scheduled) {
        if (jdbc.queryForList("SELECT id FROM web_push_attempts WHERE id = ? AND state = 'SENDING' FOR UPDATE",
                Long.class, claim).isEmpty()) return;
        // Hold the source rows through the bounded provider request so edits and logout serialize with sending.
        jdbc.queryForList("SELECT id FROM web_push_subscriptions WHERE id = ? FOR UPDATE",
                Long.class, candidate.subscriptionId());
        jdbc.queryForList("SELECT id FROM accounts WHERE id = (SELECT account_id FROM care_occurrences WHERE id = ?) FOR SHARE",
                Long.class, candidate.occurrenceId());
        jdbc.queryForList("SELECT id FROM recurring_plans WHERE id = (SELECT plan_id FROM care_occurrences WHERE id = ?) FOR SHARE",
                Long.class, candidate.occurrenceId());
        jdbc.queryForList("SELECT id FROM care_occurrences WHERE id = ? FOR SHARE", Long.class, candidate.occurrenceId());
        var current = jdbc.query(CANDIDATE + " WHERE r.occurrence_id = ? AND s.id = ?", REMINDER,
                candidate.occurrenceId(), candidate.subscriptionId()).stream().findFirst().orElse(null);

        Instant now = clock.instant();
        WebPushTransport.Result result = current != null && scheduled.equals(due(current, now))
                ? transport.send(current.endpoint(), now, expiresAt(current, scheduled))
                : new WebPushTransport.Result(CANCELED, null, null);
        jdbc.update("""
                UPDATE web_push_attempts SET state = ?, finished_at = ?, provider_status = ?, retry_at = ? WHERE id = ?
                """, result.state().name(), timestamp(clock.instant()), result.providerStatus(), timestamp(result.retryAt()), claim);
        if (Integer.valueOf(404).equals(result.providerStatus()) || Integer.valueOf(410).equals(result.providerStatus())) {
            jdbc.update("UPDATE web_push_subscriptions SET active = false WHERE id = ?", candidate.subscriptionId());
        }
        if (result.state() == WebPushTransport.State.UNCERTAIN) {
            LOG.warn("Web Push attempt {} requires reconciliation", claim);
        }
    }

    private static Instant due(Reminder reminder, Instant now) {
        if (!reminder.date().equals(now.atZone(reminder.zone()).toLocalDate())) return null;
        return WebPushSchedule.resolve(reminder.date(), reminder.time(), reminder.zone())
                .filter(time -> time.isAfter(reminder.eligibleSince()) && !time.isAfter(now)
                        && now.isBefore(expiresAt(reminder, time)))
                .orElse(null);
    }

    private static Instant expiresAt(Reminder reminder, Instant scheduled) {
        Instant endOfDay = reminder.date().plusDays(1).atStartOfDay(reminder.zone()).toInstant();
        Instant cutoff = scheduled.plus(DELIVERY_WINDOW);
        return endOfDay.isBefore(cutoff) ? endOfDay : cutoff;
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private record Reminder(long occurrenceId, long subscriptionId, String endpoint, LocalDate date,
                            LocalTime time, ZoneId zone, Instant eligibleSince) {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.agenda.web-push-enabled", havingValue = "true")
    @EnableScheduling
    static class Scheduling {}
}
