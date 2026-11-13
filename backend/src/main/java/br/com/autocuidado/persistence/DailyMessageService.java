package br.com.autocuidado.persistence;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import br.com.autocuidado.agenda.DailyMessageCatalog;
import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.auth.AccountTimeZones;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyMessageService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AccountTimeZones timeZones;

    DailyMessageService(JdbcTemplate jdbc, Clock clock, AccountTimeZones timeZones) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.timeZones = timeZones;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DailyMessageCatalog.Message load(AccountPrincipal account) {
        long accountId = account.accountId();
        LocalDate today = LocalDate.now(clock.withZone(timeZones.current(accountId)));
        var rotation = find(accountId);
        if (rotation == null) {
            jdbc.update("""
                    INSERT INTO daily_message_rotations (account_id, started_on, shuffle_seed, catalog_version)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (account_id) DO NOTHING
                    """, accountId, today, ThreadLocalRandom.current().nextLong(), DailyMessageCatalog.CURRENT_VERSION);
            // A competing first access may have already started this account's rotation.
            rotation = find(accountId);
        }
        return DailyMessageCatalog.forDate(rotation.version(), rotation.seed(), rotation.startedOn(), today);
    }

    private Rotation find(long accountId) {
        return jdbc.query("""
                SELECT started_on, shuffle_seed, catalog_version FROM daily_message_rotations WHERE account_id = ?
                """, (rows, index) -> new Rotation(rows.getObject(1, LocalDate.class), rows.getLong(2), rows.getInt(3)),
                accountId).stream().findFirst().orElse(null);
    }

    private record Rotation(LocalDate startedOn, long seed, int version) {}
}
