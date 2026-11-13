package br.com.autocuidado.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimit {

    static final int WINDOW_SECONDS = 300;
    private static final int MAX_ATTEMPTS = 10;

    private final JdbcTemplate jdbc;

    LoginRateLimit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean tryAcquire(String identifier) {
        return jdbc.update("""
                INSERT INTO login_rate_limits AS limits (identifier_hash, expires_at, attempts)
                VALUES (?, statement_timestamp() + make_interval(secs => ?), 1)
                ON CONFLICT (identifier_hash) DO UPDATE
                SET attempts = CASE WHEN limits.expires_at <= statement_timestamp() THEN 1 ELSE limits.attempts + 1 END,
                    expires_at = CASE WHEN limits.expires_at <= statement_timestamp()
                        THEN excluded.expires_at ELSE limits.expires_at END
                WHERE limits.expires_at <= statement_timestamp() OR limits.attempts < ?
                """, identifierHash(identifier), WINDOW_SECONDS, MAX_ATTEMPTS) == 1;
    }

    @Scheduled(fixedDelay = 60_000)
    void removeExpired() {
        jdbc.update("DELETE FROM login_rate_limits WHERE expires_at <= statement_timestamp()");
    }

    private static byte[] identifierHash(String identifier) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(identifier.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
