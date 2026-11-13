package br.com.autocuidado.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistryImpl;

final class AccountSessions extends SessionRegistryImpl {

    private static final int MAX_SESSIONS = 5;
    private static final Comparator<SessionInformation> BY_ACTIVITY =
            Comparator.comparing(SessionInformation::getLastRequest).thenComparing(SessionInformation::getSessionId);

    synchronized String start(long accountId, String servletId) {
        removeSessionInformation(servletId);
        var existing = getAllSessions(accountId, false);
        String evicted = null;
        if (existing.size() >= MAX_SESSIONS) {
            var oldest = existing.stream().min(BY_ACTIVITY).orElseThrow();
            oldest.expireNow();
            evicted = oldest.getSessionId();
        }
        registerNewSession(servletId, accountId);
        return evicted;
    }

    synchronized boolean touch(long accountId, String servletId) {
        var session = getSessionInformation(servletId);
        if (session == null || session.isExpired() || !session.getPrincipal().equals(accountId)) {
            return false;
        }
        refreshLastRequest(servletId);
        return true;
    }

    synchronized List<ActiveSession> list(long accountId, String currentServletId) {
        return getAllSessions(accountId, false).stream().sorted(BY_ACTIVITY.reversed())
                .map(session -> new ActiveSession(reference(session.getSessionId()),
                        session.getSessionId().equals(currentServletId), session.getLastRequest().toInstant()))
                .toList();
    }

    synchronized List<String> revoke(long accountId, String reference) {
        var revoked = getAllSessions(accountId, false).stream()
                .filter(session -> "all".equals(reference) || reference(session.getSessionId()).equals(reference))
                .toList();
        revoked.forEach(SessionInformation::expireNow);
        return revoked.stream().map(SessionInformation::getSessionId).toList();
    }

    private static String reference(String servletId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(servletId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record ActiveSession(String id, boolean current, Instant lastActivity) {}
}
