package br.com.autocuidado.push;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static br.com.autocuidado.push.WebPushTransport.State.ACCEPTED;
import static br.com.autocuidado.push.WebPushTransport.State.RETRY;
import static br.com.autocuidado.push.WebPushTransport.State.UNCERTAIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("postgresql")
class WebPushPostgresqlTest {
    private static final String FIRST = "https://fcm.googleapis.com/fcm/send/fictional-first";
    private static final String SECOND = "https://fcm.googleapis.com/fcm/send/fictional-second";
    private static final Instant DUE = Instant.parse("2026-11-11T02:58:00Z");

    @Test
    void migratesFromV7AndPreservesDeliveryStateAcrossWorkersAndEdits() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        var source = new DriverManagerDataSource(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD"));
        var admin = new JdbcTemplate(source);
        String schema = "e210_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        source.setSchema(schema);
        var jdbc = new JdbcTemplate(source);
        try {
            Flyway.configure().dataSource(source).schemas(schema).target("7").load().migrate();
            long account = account(jdbc, "push_owner");
            long other = account(jdbc, "push_other");
            jdbc.update("""
                    INSERT INTO agenda_reminder_preferences (account_id, enabled, categories, reminder_time)
                    VALUES (?, true, ARRAY['SKIN'], '23:58')
                    """, account);
            long planned = occurrence(jdbc, account, "first");
            long late = occurrence(jdbc, account, "late");
            Flyway.configure().dataSource(source).schemas(schema).load().migrate();
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM web_push_reminders", Integer.class));

            var subscriptions = new WebPushSubscriptions(jdbc);
            assertTrue(subscriptions.enable(account, "session-1", FIRST));
            assertTrue(subscriptions.enable(account, "session-2", SECOND));
            assertFalse(subscriptions.enable(other, "unrelated", FIRST));
            assertFalse(subscriptions.active(other, "session-1", FIRST));
            assertFalse(subscriptions.active(account, "session-2", FIRST));
            subscriptions.disable(account, "session-2", FIRST);
            assertTrue(subscriptions.active(account, "session-1", FIRST));
            jdbc.update("UPDATE web_push_subscriptions SET enabled_at = '2026-11-10T10:00:00Z'");
            jdbc.update("UPDATE accounts SET push_zone_since = '2026-11-10T10:00:00Z'");
            jdbc.update("UPDATE agenda_reminder_preferences SET push_preferences_since = '2026-11-10T10:00:00Z'");
            jdbc.update("UPDATE care_occurrences SET push_planned_since = '2026-11-10T10:00:00Z' WHERE id = ?", planned);
            jdbc.update("UPDATE care_occurrences SET push_planned_since = '2026-11-11T02:58:05Z' WHERE id = ?", late);

            var transport = mock(WebPushTransport.class);
            when(transport.available()).thenReturn(true);
            var sending = new CountDownLatch(1);
            var finish = new CountDownLatch(1);
            when(transport.send(eq(FIRST), any(), any())).thenAnswer(call -> {
                sending.countDown();
                assertTrue(finish.await(10, TimeUnit.SECONDS));
                return new WebPushTransport.Result(ACCEPTED, 201, null);
            });
            when(transport.send(eq(SECOND), any(), any())).thenReturn(
                    new WebPushTransport.Result(RETRY, 429, DUE.plusSeconds(65)),
                    new WebPushTransport.Result(UNCERTAIN, null, null));
            var transactions = new DataSourceTransactionManager(source);
            var now = Clock.fixed(DUE.plusSeconds(10), ZoneOffset.UTC);
            var worker = new WebPushDispatcher(jdbc, now, transport, transactions);
            var competingWorker = new WebPushDispatcher(jdbc, now, transport, transactions);
            try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = threads.submit(worker::dispatch);
                try {
                    assertTrue(sending.await(10, TimeUnit.SECONDS));
                    threads.submit(competingWorker::dispatch).get(10, TimeUnit.SECONDS);
                } finally {
                    finish.countDown();
                }
                first.get(10, TimeUnit.SECONDS);
            }
            assertEquals(List.of("ACCEPTED", "RETRY"), states(jdbc, planned));
            assertTrue(states(jdbc, late).isEmpty());

            worker.dispatch();
            verify(transport, times(1)).send(eq(SECOND), any(), any());
            var retryWorker = new WebPushDispatcher(jdbc, Clock.fixed(DUE.plusSeconds(70), ZoneOffset.UTC),
                    transport, transactions);
            retryWorker.dispatch();
            retryWorker.dispatch();
            assertEquals(List.of("ACCEPTED", "RETRY", "UNCERTAIN"), states(jdbc, planned));
            verify(transport, times(1)).send(FIRST, DUE.plusSeconds(10), DUE.plusSeconds(120));
            verify(transport, times(2)).send(eq(SECOND), any(), any());

            jdbc.update("UPDATE care_occurrences SET planned_date = '2026-11-11' WHERE id = ?", planned);
            jdbc.update("UPDATE care_occurrences SET push_planned_since = '2026-11-11T03:00:00Z' WHERE id = ?", planned);
            var tomorrow = Clock.fixed(DUE.plusSeconds(86410), ZoneOffset.UTC);
            new WebPushDispatcher(jdbc, tomorrow, transport, transactions).dispatch();
            assertEquals(List.of("ACCEPTED", "RETRY", "UNCERTAIN"), states(jdbc, planned));

            subscriptions.endSession(account, "session-1");
            assertFalse(subscriptions.active(account, "session-1", FIRST));
            assertTrue(subscriptions.active(account, "session-2", SECOND));
            subscriptions.disable(account, "session-2", SECOND);
            assertFalse(subscriptions.active(account, "session-2", SECOND));
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static long account(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (username, password_hash, time_zone)
                VALUES (?, 'fictional-hash', 'America/Sao_Paulo') RETURNING id
                """, Long.class, username);
    }

    private static long occurrence(JdbcTemplate jdbc, long account, String title) {
        return jdbc.queryForObject("""
                INSERT INTO care_occurrences (account_id, category, title, planned_date, status)
                VALUES (?, 'SKIN', ?, '2026-11-10', 'PLANNED') RETURNING id
                """, Long.class, account, title);
    }

    private static List<String> states(JdbcTemplate jdbc, long occurrence) {
        return jdbc.queryForList("""
                SELECT state FROM web_push_attempts WHERE occurrence_id = ? ORDER BY id
                """, String.class, occurrence);
    }
}
