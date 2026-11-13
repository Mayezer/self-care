package br.com.autocuidado.auth;

import java.time.Clock;

import br.com.autocuidado.auth.CredentialOperations.Enrollment;
import br.com.autocuidado.auth.CredentialOperations.ResetEnrollment;
import br.com.autocuidado.auth.CredentialOperations.Setup;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/auth/password")
class PasswordController {
    private static final String RESET_ENROLLMENT = PasswordController.class.getName() + ".resetEnrollment";
    private final CredentialStore store;
    private final CredentialOperations operations;
    private final CredentialMail mail;
    private final PasswordPolicy policy;
    private final PasswordEncoder passwords;
    private final LoginRateLimit rateLimit;
    private final LoginSessions sessions;
    private final Clock clock;
    private final TaskExecutor mailTasks;

    PasswordController(CredentialStore store, CredentialOperations operations, CredentialMail mail, PasswordPolicy policy,
            PasswordEncoder passwords, LoginRateLimit rateLimit, LoginSessions sessions, Clock clock,
            @Qualifier("credentialMailTasks") TaskExecutor mailTasks) {
        this.store = store;
        this.operations = operations;
        this.mail = mail;
        this.policy = policy;
        this.passwords = passwords;
        this.rateLimit = rateLimit;
        this.sessions = sessions;
        this.clock = clock;
        this.mailTasks = mailTasks;
    }

    @PostMapping("/change")
    ResponseEntity<Void> change(@AuthenticationPrincipal AccountPrincipal account, @RequestBody Change input,
            HttpServletRequest request, HttpServletResponse response) {
        requireRate("credential:" + account.accountId());
        var row = store.account(account.accountId()).orElseThrow(PasswordController::invalid);
        if (row.version() != account.credentialVersion() || input.currentPassword() == null
                || input.currentPassword().length() > 1024 || !passwords.matches(input.currentPassword(), row.passwordHash())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        checkNewPassword(input.password(), account.getUsername(), row.passwordHash());
        if (!operations.changePassword(account, row.passwordHash(), input.factor(), passwords.encode(input.password()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        sessions.revokeAll(account.accountId(), request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset/request")
    ResponseEntity<Void> requestReset(@RequestBody Address input) {
        String email = AccountController.readEmail(input.email());
        if (email == null) {
            return ResponseEntity.badRequest().build();
        }
        requireRate("reset-mail:" + email);
        try {
            mailTasks.execute(() -> sendReset(email));
        } catch (TaskRejectedException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset/complete")
    ResponseEntity<Void> complete(@RequestBody Reset input, HttpServletRequest request, HttpServletResponse response) {
        var row = verifyReset(input.email(), input.code());
        requireRate("credential:" + row.id());
        checkNewPassword(input.password(), row.email(), row.passwordHash());
        if (!operations.reset(row.id(), input.code(), input.factor(), passwords.encode(input.password()))) {
            throw invalid();
        }
        sessions.revokeAll(row.id(), request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset/recovery")
    Setup startRecovery(@RequestBody Recovery input, HttpServletRequest request) {
        var row = verifyReset(input.email(), input.code());
        requireRate("credential:" + row.id());
        if (!row.enrolled() || !AuthSecrets.matchesDigest(input.recovery(), row.recoveryDigest())) {
            throw invalid();
        }
        var enrollment = Enrollment.create();
        var pending = new ResetEnrollment(row.id(), row.version(), row.email(), input.code(), input.recovery(), enrollment);
        request.getSession().setAttribute(RESET_ENROLLMENT, pending);
        return enrollment.setup();
    }

    @PostMapping("/reset/recovery/complete")
    ResponseEntity<Void> completeRecovery(@RequestBody RecoveryComplete input, HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(RESET_ENROLLMENT) instanceof ResetEnrollment pending)) {
            throw invalid();
        }
        requireRate("credential:" + pending.accountId());
        var row = store.account(pending.accountId()).orElseThrow(PasswordController::invalid);
        if (row.version() != pending.version() || !row.validReset(pending.resetCode(), clock.instant())) {
            throw invalid();
        }
        checkNewPassword(input.password(), pending.email(), row.passwordHash());
        if (!operations.recover(pending, input.factor(), passwords.encode(input.password()))) {
            throw invalid();
        }
        sessions.revokeAll(pending.accountId(), request, response);
        return ResponseEntity.noContent().build();
    }

    private void sendReset(String email) {
        String code = AuthSecrets.token();
        String digest = AuthSecrets.digest(code);
        if (store.issueReset(email, digest, clock.instant())) {
            try {
                mail.send(email, "Recupere sua senha na Agenda de autocuidado", code);
            } catch (MailException exception) {
                store.cancelReset(email, digest);
            }
        }
    }

    private CredentialStore.Credential verifyReset(String input, String code) {
        String email = AccountController.readEmail(input);
        if (email == null || !AuthSecrets.validToken(code)) {
            throw invalid();
        }
        requireRate("reset:" + email);
        return store.email(email).filter(row -> row.validReset(code, clock.instant())).orElseThrow(PasswordController::invalid);
    }

    private void checkNewPassword(String value, String identifier, String currentHash) {
        policy.require(value, identifier);
        if (passwords.matches(value, currentHash)) {
            throw new PasswordPolicy.Rejected("Escolha uma senha diferente da atual.");
        }
    }

    private void requireRate(String key) {
        if (!rateLimit.tryAcquire(key)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    record Address(String email) {}
    record Change(String currentPassword, String factor, String password) {}
    record Reset(String email, String code, String factor, String password) {}
    record Recovery(String email, String code, String recovery) {}
    record RecoveryComplete(String factor, String password) {}
}
