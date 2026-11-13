package br.com.autocuidado.auth;

import java.util.List;

import br.com.autocuidado.persistence.AccountRepository;
import br.com.autocuidado.push.WebPushSubscriptions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/sessions")
class AccountSessionsController {

    private final AccountSessions sessions;
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final LoginRateLimit rateLimit;
    private final ObjectProvider<WebPushSubscriptions> pushSubscriptions;
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();
    private final CookieClearingLogoutHandler clearCookie = new CookieClearingLogoutHandler("JSESSIONID");

    AccountSessionsController(AccountSessions sessions, AccountRepository accounts, PasswordEncoder passwords,
            LoginRateLimit rateLimit, ObjectProvider<WebPushSubscriptions> pushSubscriptions) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.passwords = passwords;
        this.rateLimit = rateLimit;
        this.pushSubscriptions = pushSubscriptions;
    }

    @GetMapping
    ResponseEntity<List<AccountSessions.ActiveSession>> list(@AuthenticationPrincipal AccountPrincipal account,
            HttpServletRequest request) {
        var session = request.getSession(false);
        return session == null ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
                : ResponseEntity.ok(sessions.list(account.accountId(), session.getId()));
    }

    @PostMapping("/revoke")
    ResponseEntity<Void> revoke(@AuthenticationPrincipal AccountPrincipal account, @RequestBody Revocation input,
            HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (input.password() == null || input.password().isEmpty() || input.password().length() > 1024
                || input.session() == null || !input.session().matches("all|[0-9a-f]{64}")) {
            return ResponseEntity.badRequest().build();
        }
        if (!rateLimit.tryAcquire(account.getUsername())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", Integer.toString(LoginRateLimit.WINDOW_SECONDS)).build();
        }
        String hash = accounts.findPasswordHash(account.accountId());
        if (hash == null || !passwords.matches(input.password(), hash)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var revoked = sessions.revoke(account.accountId(), input.session());
        if (revoked.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String current = session.getId();
        if (revoked.contains(current)) {
            logout.logout(request, response, null);
            clearCookie.logout(request, response, null);
            sessions.removeSessionInformation(current);
        }
        pushSubscriptions.ifAvailable(subscriptions ->
                revoked.forEach(id -> subscriptions.endSession(account.accountId(), id)));
        return ResponseEntity.noContent().build();
    }

    record Revocation(String session, String password) {}
}
