package br.com.autocuidado.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubscriptionAccess {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    SubscriptionAccess(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean active(long accountId) {
        var until = accessUntil(accountId, false);
        return active(until, clock.instant());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Instant requireActive(long accountId, String message) {
        // Provider refresh and webhooks update the same row under lock.
        var until = accessUntil(accountId, true);
        Instant now = clock.instant();
        if (!active(until, now)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        return now;
    }

    static boolean active(Instant until, Instant now) {
        return until != null && until.isAfter(now);
    }

    private Instant accessUntil(long accountId, boolean lock) {
        List<OffsetDateTime> dates = jdbc.queryForList(
                "SELECT access_until FROM billing_accounts WHERE account_id = ?" + (lock ? " FOR UPDATE" : ""),
                OffsetDateTime.class, accountId);
        return dates.isEmpty() || dates.getFirst() == null ? null : dates.getFirst().toInstant();
    }
}
