package br.com.autocuidado.auth;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

import br.com.autocuidado.auth.CredentialOperations.Enrollment;
import br.com.autocuidado.auth.CredentialOperations.Setup;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/auth/mfa")
class MfaLoginController {
    private static final String PENDING = MfaLoginController.class.getName() + ".pending";
    private final CredentialStore store;
    private final CredentialOperations operations;
    private final CredentialMail mail;
    private final LoginRateLimit rateLimit;
    private final LoginSessions sessions;
    private final Clock clock;

    MfaLoginController(CredentialStore store, CredentialOperations operations, CredentialMail mail,
            LoginRateLimit rateLimit, LoginSessions sessions, Clock clock) {
        this.store = store;
        this.operations = operations;
        this.mail = mail;
        this.rateLimit = rateLimit;
        this.sessions = sessions;
        this.clock = clock;
    }

    void begin(AccountPrincipal account, HttpServletRequest request, HttpServletResponse response) throws IOException {
        sessions.prepare(request, response);
        var row = store.account(account.accountId()).filter(value -> value.version() == account.credentialVersion()).orElse(null);
        if (row == null || row.email() == null) {
            response.setStatus(401);
            return;
        }
        var pending = new Pending(account, clock.instant().plusSeconds(600), row.enrolled());
        request.getSession().setAttribute(PENDING, pending);
        response.setStatus(202);
        response.setContentType("application/json");
        String step = row.enrolled() ? "verify" : "enroll-email";
        response.getWriter().write("""
                {"mfa":"%s","needsEmail":false}
                """.formatted(step));
    }

    @PostMapping("/email")
    ResponseEntity<Void> email(HttpServletRequest request) {
        var pending = pending(request);
        synchronized (pending) {
            requireCurrent(request, pending);
            if (pending.enrolled) {
                throw unauthorized();
            }
            String email = pending.account.email();
            checkLimit("mfa-email:" + pending.account.accountId());
            String code = AuthSecrets.token();
            try {
                mail.send(email, "Confirme a configuração do autenticador", code);
            } catch (MailException exception) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            pending.email = email;
            pending.emailDigest = AuthSecrets.digest(code);
            pending.enrollment = null;
            return ResponseEntity.accepted().build();
        }
    }

    @PostMapping("/email/confirm")
    Setup confirmEmail(@RequestBody Code input, HttpServletRequest request) {
        var pending = pending(request);
        synchronized (pending) {
            requireCurrent(request, pending);
            checkFactorLimit(pending);
            if (pending.enrolled || !AuthSecrets.matchesDigest(input.code(), pending.emailDigest)) {
                throw invalidCode();
            }
            pending.emailDigest = null;
            pending.enrollment = Enrollment.create();
            return pending.enrollment.setup();
        }
    }

    @PostMapping("/recover")
    Setup recovery(@RequestBody Code input, HttpServletRequest request) {
        var pending = pending(request);
        synchronized (pending) {
            requireCurrent(request, pending);
            checkFactorLimit(pending);
            var row = store.account(pending.account.accountId()).orElse(null);
            if (!pending.enrolled || row == null || row.version() != pending.account.credentialVersion()
                    || !AuthSecrets.matchesDigest(input.code(), row.recoveryDigest())) {
                throw invalidCode();
            }
            pending.email = row.email();
            pending.previousRecovery = input.code();
            pending.enrollment = Enrollment.create();
            return pending.enrollment.setup();
        }
    }

    @PostMapping("/activate")
    ResponseEntity<Void> activate(@RequestBody Code input, HttpServletRequest request, HttpServletResponse response) {
        var pending = pending(request);
        synchronized (pending) {
            requireCurrent(request, pending);
            checkFactorLimit(pending);
            try {
                if (pending.enrollment == null || !operations.enroll(pending.account, pending.email, pending.enrollment,
                        input.code(), pending.previousRecovery)) {
                    throw invalidCode();
                }
            } catch (DuplicateKeyException exception) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            sessions.invalidateAccount(pending.account.accountId());
            finish(pending.account.afterMfa(pending.email, pending.account.credentialVersion() + 1), request, response);
            return ResponseEntity.noContent().build();
        }
    }

    @PostMapping("/verify")
    ResponseEntity<Void> verify(@RequestBody Code input, HttpServletRequest request, HttpServletResponse response) {
        var pending = pending(request);
        synchronized (pending) {
            requireCurrent(request, pending);
            checkFactorLimit(pending);
            if (!pending.enrolled || !operations.verify(pending.account, input.code())) {
                throw invalidCode();
            }
            finish(pending.account.afterMfa(pending.account.email(), pending.account.credentialVersion()), request, response);
            return ResponseEntity.noContent().build();
        }
    }

    private void finish(AccountPrincipal account, HttpServletRequest request, HttpServletResponse response) {
        request.getSession(false).removeAttribute(PENDING);
        sessions.finish(account, request, response, true);
    }

    private Pending pending(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(PENDING) instanceof Pending pending)) {
            throw unauthorized();
        }
        return pending;
    }

    private void requireCurrent(HttpServletRequest request, Pending pending) {
        if (pending(request) != pending || !clock.instant().isBefore(pending.expiresAt)) {
            throw unauthorized();
        }
    }

    private void checkFactorLimit(Pending pending) {
        checkLimit("mfa:" + pending.account.accountId());
    }

    private void checkLimit(String key) {
        if (!rateLimit.tryAcquire(key)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private static ResponseStatusException invalidCode() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    record Code(String code) {}

    private static final class Pending {
        final AccountPrincipal account;
        final Instant expiresAt;
        final boolean enrolled;
        String email;
        String emailDigest;
        String previousRecovery;
        Enrollment enrollment;

        Pending(AccountPrincipal account, Instant expiresAt, boolean enrolled) {
            this.account = account;
            this.expiresAt = expiresAt;
            this.enrolled = enrolled;
        }
    }
}
