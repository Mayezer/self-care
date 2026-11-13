package br.com.autocuidado.hair;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class DiaryRetention {
    private static final Duration RETENTION = Duration.ofDays(30);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    DiaryRetention(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // The billing transaction already locks the account before restoring access.
    public void purgeBeforeRenewal(long accountId, Instant now) {
        jdbc.update("""
                DELETE FROM hair_diary_photos p USING billing_accounts b
                WHERE p.account_id = b.account_id AND b.account_id = ?
                  AND b.access_lost_at <= ?
                """, accountId, cutoff(now));
    }

    public List<Long> expiredAccountsAwaitingConfirmation() {
        return jdbc.queryForList("""
                SELECT b.account_id FROM billing_accounts b
                WHERE b.access_until <= ? AND b.access_lost_at IS NULL AND b.stripe_customer_id IS NOT NULL
                  AND EXISTS (SELECT 1 FROM hair_diary_photos p WHERE p.account_id = b.account_id)
                """, Long.class, clock.instant().atOffset(ZoneOffset.UTC));
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void purgeExpired() {
        jdbc.update("""
                WITH expired AS (
                    SELECT b.account_id FROM billing_accounts b
                    WHERE b.access_lost_at <= ?
                      AND EXISTS (SELECT 1 FROM hair_diary_photos p WHERE p.account_id = b.account_id)
                    FOR UPDATE OF b SKIP LOCKED
                )
                DELETE FROM hair_diary_photos p USING expired WHERE p.account_id = expired.account_id
                """, cutoff(clock.instant()));
    }

    private static OffsetDateTime cutoff(Instant now) {
        return now.minus(RETENTION).atOffset(ZoneOffset.UTC);
    }
}
