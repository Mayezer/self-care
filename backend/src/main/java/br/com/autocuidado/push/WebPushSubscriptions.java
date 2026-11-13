package br.com.autocuidado.push;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebPushSubscriptions {
    private static final Set<String> PROVIDERS = Set.of(
            "fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com");
    private final JdbcTemplate jdbc;

    WebPushSubscriptions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static URI endpoint(String value) {
        if (value == null || value.length() > 2048) throw new IllegalArgumentException("Invalid push endpoint");
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() != -1
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getRawPath() == null || uri.getRawPath().length() <= 1
                || !isKnownProvider(uri)) {
            throw new IllegalArgumentException("Invalid push endpoint");
        }
        return uri;
    }

    private static boolean isKnownProvider(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return PROVIDERS.contains(host) || ("jmt17.google.com".equals(host)
                && uri.getRawQuery() == null && uri.getRawPath().matches("/fcm/send/[A-Za-z0-9_:-]+"));
    }

    boolean active(long accountId, String sessionId, String endpoint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM web_push_subscriptions
                    WHERE account_id = ? AND session_hash = ? AND endpoint = ? AND active
                )
                """, Boolean.class, accountId, sessionHash(sessionId), endpoint));
    }

    boolean canDisplay(String endpoint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM web_push_subscriptions s
                    JOIN agenda_reminder_preferences p ON p.account_id = s.account_id AND p.enabled
                    WHERE s.endpoint = ? AND s.active
                )
                """, Boolean.class, endpoint));
    }

    boolean enable(long accountId, String sessionId, String endpoint) {
        return jdbc.update("""
                INSERT INTO web_push_subscriptions AS s (account_id, session_hash, endpoint, active)
                VALUES (?, ?, ?, true)
                ON CONFLICT (endpoint) DO UPDATE
                SET account_id = EXCLUDED.account_id, session_hash = EXCLUDED.session_hash, active = true,
                    enabled_at = CASE WHEN s.active AND s.session_hash = EXCLUDED.session_hash THEN s.enabled_at
                                      ELSE clock_timestamp() END
                WHERE s.account_id = EXCLUDED.account_id
                """, accountId, sessionHash(sessionId), endpoint) != 0;
    }

    void disable(long accountId, String sessionId, String endpoint) {
        jdbc.update("""
                UPDATE web_push_subscriptions SET active = false
                WHERE account_id = ? AND session_hash = ? AND endpoint = ? AND active
                """, accountId, sessionHash(sessionId), endpoint);
    }

    public void endSession(long accountId, String sessionId) {
        jdbc.update("""
                UPDATE web_push_subscriptions SET active = false
                WHERE account_id = ? AND session_hash = ? AND active
                """, accountId, sessionHash(sessionId));
    }

    private static String sessionHash(String sessionId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
