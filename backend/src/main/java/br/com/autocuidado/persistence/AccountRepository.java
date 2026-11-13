package br.com.autocuidado.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AccountRepository extends Repository<Account, Long> {
    String LOGIN_CREDENTIAL = """
            SELECT id, username, email, password_hash AS "passwordHash", credential_version AS "credentialVersion"
            FROM accounts WHERE password_hash IS NOT NULL AND
            """;

    @Query(value = LOGIN_CREDENTIAL + " username = :username", nativeQuery = true)
    Optional<LoginCredential> loginByUsername(@Param("username") String username);

    @Query(value = LOGIN_CREDENTIAL + " email = :email", nativeQuery = true)
    Optional<LoginCredential> loginByEmail(@Param("email") String email);

    @Query("SELECT a.passwordHash FROM Account a WHERE a.id = :id")
    String findPasswordHash(@Param("id") long id);

    @Query(value = "SELECT credential_version FROM accounts WHERE id = :id", nativeQuery = true)
    Long findCredentialVersion(@Param("id") long id);

    interface LoginCredential {
        long getId();
        String getUsername();
        String getEmail();
        String getPasswordHash();
        long getCredentialVersion();
    }

    @Query("SELECT a.timeZone FROM Account a WHERE a.id = :id")
    String findTimeZone(@Param("id") long id);

    @Modifying
    @Transactional
    @Query("UPDATE Account a SET a.timeZone = :zone WHERE a.id = :id")
    void updateTimeZone(@Param("id") long id, @Param("zone") String zone);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO accounts (email, time_zone, confirmation_digest, confirmation_expires_at)
            VALUES (:email, :timeZone, :digest, :expiresAt)
            ON CONFLICT (email) DO UPDATE SET confirmation_digest = excluded.confirmation_digest,
                confirmation_expires_at = excluded.confirmation_expires_at
            WHERE accounts.password_hash IS NULL AND accounts.confirmation_expires_at <= :resendCutoff
            """, nativeQuery = true)
    int issueConfirmation(@Param("email") String email, @Param("timeZone") String timeZone,
            @Param("digest") String digest, @Param("expiresAt") OffsetDateTime expiresAt,
            @Param("resendCutoff") OffsetDateTime resendCutoff);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE accounts SET confirmation_expires_at = :now
            WHERE email = :email AND confirmation_digest = :digest
            """, nativeQuery = true)
    void expireConfirmation(@Param("email") String email, @Param("digest") String digest,
            @Param("now") OffsetDateTime now);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM accounts
                WHERE email = :email AND confirmation_digest = :digest AND confirmation_expires_at > :now)
            """, nativeQuery = true)
    boolean hasValidConfirmation(@Param("email") String email, @Param("digest") String digest,
            @Param("now") OffsetDateTime now);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE accounts SET password_hash = :passwordHash, time_zone = :timeZone,
                confirmation_digest = NULL, confirmation_expires_at = NULL
            WHERE email = :email AND confirmation_digest = :digest AND confirmation_expires_at > :now
            """, nativeQuery = true)
    int confirm(@Param("email") String email, @Param("digest") String digest, @Param("now") OffsetDateTime now,
            @Param("passwordHash") String passwordHash, @Param("timeZone") String timeZone);
}
