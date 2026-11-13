package br.com.autocuidado.auth;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class CredentialStore {
    private static final String SELECT = """
            SELECT id, email, password_hash, credential_version, totp_seed, totp_last_step,
                recovery_digest, reset_digest, reset_expires_at
            FROM accounts WHERE password_hash IS NOT NULL AND
            """;
    private static final RowMapper<Credential> ROW = (rs, index) -> {
        var expiry = rs.getObject("reset_expires_at", OffsetDateTime.class);
        return new Credential(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getLong("credential_version"), rs.getBytes("totp_seed"), rs.getLong("totp_last_step"),
                rs.getString("recovery_digest"), rs.getString("reset_digest"), expiry == null ? null : expiry.toInstant());
    };

    private final JdbcTemplate jdbc;

    CredentialStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Credential> account(long id) {
        return jdbc.query(SELECT + " id = ?", ROW, id).stream().findFirst();
    }

    Optional<Credential> lock(long id) {
        return jdbc.query(SELECT + " id = ? FOR UPDATE", ROW, id).stream().findFirst();
    }

    Optional<Credential> email(String email) {
        return jdbc.query(SELECT + " email = ?", ROW, email).stream().findFirst();
    }

    boolean issueReset(String email, String digest, Instant now) {
        return jdbc.update("""
                UPDATE accounts SET reset_digest = ?, reset_expires_at = ?, reset_requested_at = ?
                WHERE email = ? AND password_hash IS NOT NULL
                    AND (reset_requested_at IS NULL OR reset_requested_at <= ?)
                """, digest, utc(now.plusSeconds(600)), utc(now), email, utc(now.minusSeconds(60))) == 1;
    }

    void cancelReset(String email, String digest) {
        jdbc.update("UPDATE accounts SET reset_digest = NULL, reset_expires_at = NULL WHERE email = ? AND reset_digest = ?",
                email, digest);
    }

    void acceptStep(long id, long step) {
        jdbc.update("UPDATE accounts SET totp_last_step = ? WHERE id = ?", step, id);
    }

    void enroll(long id, String email, byte[] seed, long step, String recovery) {
        jdbc.update("""
                UPDATE accounts SET email = ?, totp_seed = ?, totp_last_step = ?, recovery_digest = ?,
                    credential_version = credential_version + 1, reset_digest = NULL, reset_expires_at = NULL
                WHERE id = ?
                """, email, seed, step, recovery, id);
    }

    void updatePassword(long id, String passwordHash) {
        jdbc.update("""
                UPDATE accounts SET password_hash = ?, credential_version = credential_version + 1,
                    reset_digest = NULL, reset_expires_at = NULL WHERE id = ?
                """, passwordHash, id);
    }

    void recover(long id, String passwordHash, byte[] seed, long step, String recovery) {
        jdbc.update("""
                UPDATE accounts SET password_hash = ?, totp_seed = ?, totp_last_step = ?, recovery_digest = ?,
                    credential_version = credential_version + 1, reset_digest = NULL, reset_expires_at = NULL
                WHERE id = ?
                """, passwordHash, seed, step, recovery, id);
    }

    private static OffsetDateTime utc(Instant time) {
        return time.atOffset(ZoneOffset.UTC);
    }

    record Credential(long id, String email, String passwordHash, long version, byte[] seed, long lastStep,
            String recoveryDigest, String resetDigest, Instant resetExpiresAt) {
        boolean enrolled() {
            return seed != null;
        }

        boolean validReset(String token, Instant now) {
            return resetExpiresAt != null && now.isBefore(resetExpiresAt) && AuthSecrets.matchesDigest(token, resetDigest);
        }
    }
}
