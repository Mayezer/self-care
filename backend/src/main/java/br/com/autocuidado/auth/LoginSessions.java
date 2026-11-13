package br.com.autocuidado.auth;

import java.time.Clock;
import java.time.Duration;

import br.com.autocuidado.push.WebPushSubscriptions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

final class LoginSessions {
    static final String EXPIRES_AT = LoginSessions.class.getName() + ".expiresAt";
    final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    final HttpSessionCsrfTokenRepository csrf = new HttpSessionCsrfTokenRepository();
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();
    private final CookieClearingLogoutHandler clearCookie = new CookieClearingLogoutHandler("JSESSIONID");
    private final AccountSessions sessions;
    private final ObjectProvider<WebPushSubscriptions> pushSubscriptions;
    private final Clock clock;
    private final Duration maxAge;

    LoginSessions(AccountSessions sessions, ObjectProvider<WebPushSubscriptions> pushSubscriptions, Clock clock, Duration maxAge) {
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("app.auth.session-max-age must be positive");
        }
        this.sessions = sessions;
        this.pushSubscriptions = pushSubscriptions;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    void prepare(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession();
        sessions.removeSessionInformation(session.getId());
        contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        SecurityContextHolder.clearContext();
    }

    void finish(AccountPrincipal account, HttpServletRequest request, HttpServletResponse response, boolean rotate) {
        var session = request.getSession();
        if (rotate) {
            request.changeSessionId();
            csrf.saveToken(null, request, response);
        }
        session.setAttribute(EXPIRES_AT, clock.instant().plus(maxAge));
        var evicted = sessions.start(account.accountId(), session.getId());
        if (evicted != null) {
            endPush(account.accountId(), evicted);
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    void revokeAll(long accountId, HttpServletRequest request, HttpServletResponse response) {
        invalidateAccount(accountId);
        var session = request.getSession(false);
        if (session != null) {
            sessions.removeSessionInformation(session.getId());
        }
        logout.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        clearCookie.logout(request, response, null);
    }

    void invalidateAccount(long accountId) {
        sessions.revoke(accountId, "all").forEach(id -> endPush(accountId, id));
    }

    private void endPush(long accountId, String id) {
        pushSubscriptions.ifAvailable(subscriptions -> subscriptions.endSession(accountId, id));
    }
}
