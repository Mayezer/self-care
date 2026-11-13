package br.com.autocuidado.auth;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

import br.com.autocuidado.persistence.AccountRepository;
import br.com.autocuidado.push.WebPushSubscriptions;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

final class SessionLifetimeFilter extends OncePerRequestFilter {

    private final Clock clock;
    private final ObjectProvider<AccountRepository> accounts;
    private final AccountSessions sessions;
    private final ObjectProvider<WebPushSubscriptions> pushSubscriptions;
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();

    SessionLifetimeFilter(Clock clock, ObjectProvider<AccountRepository> accounts, AccountSessions sessions,
            ObjectProvider<WebPushSubscriptions> pushSubscriptions) {
        this.clock = clock;
        this.accounts = accounts;
        this.sessions = sessions;
        this.pushSubscriptions = pushSubscriptions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal account) {
            var session = request.getSession(false);
            if (expired(session) || !sessions.touch(account.accountId(), session.getId())
                    || !Long.valueOf(account.credentialVersion()).equals(accounts.getObject().findCredentialVersion(account.accountId()))) {
                logout.logout(request, response, authentication);
                if (session != null) {
                    sessions.removeSessionInformation(session.getId());
                    pushSubscriptions.ifAvailable(subscriptions ->
                            subscriptions.endSession(account.accountId(), session.getId()));
                }
            }
        }
        chain.doFilter(request, response);
    }

    private boolean expired(HttpSession session) {
        try {
            return session == null || !(session.getAttribute(LoginSessions.EXPIRES_AT) instanceof Instant expiresAt)
                    || !clock.instant().isBefore(expiresAt);
        } catch (IllegalStateException invalidated) {
            return true;
        }
    }
}
