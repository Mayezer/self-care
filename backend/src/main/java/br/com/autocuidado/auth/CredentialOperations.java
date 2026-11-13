package br.com.autocuidado.auth;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CredentialOperations {
    private final CredentialStore store;
    private final AuthSecrets secrets;
    private final Clock clock;

    CredentialOperations(CredentialStore store, AuthSecrets secrets, Clock clock) {
        this.store = store;
        this.secrets = secrets;
        this.clock = clock;
    }

    @Transactional
    boolean verify(AccountPrincipal account, String code) {
        return store.lock(account.accountId()).filter(row -> row.version() == account.credentialVersion())
                .filter(CredentialStore.Credential::enrolled).map(row -> consumeTotp(row, code, clock.instant()))
                .orElse(false);
    }

    @Transactional
    boolean enroll(AccountPrincipal account, String email, Enrollment enrollment, String code, String previousRecovery) {
        var row = store.lock(account.accountId()).orElse(null);
        if (row == null || row.version() != account.credentialVersion()
                || (row.email() != null && !row.email().equals(email))
                || (row.enrolled() && !AuthSecrets.matchesDigest(previousRecovery, row.recoveryDigest()))) {
            return false;
        }
        long step = AuthSecrets.step(clock.instant());
        if (!AuthSecrets.matchesTotp(enrollment.seed(), step, code)) {
            return false;
        }
        store.enroll(row.id(), email, secrets.encrypt(row.id(), enrollment.seed()), step, AuthSecrets.digest(enrollment.recovery()));
        return true;
    }

    @Transactional
    boolean changePassword(AccountPrincipal account, String verifiedHash, String factor, String newHash) {
        var row = store.lock(account.accountId()).orElse(null);
        if (row == null || row.version() != account.credentialVersion() || !row.passwordHash().equals(verifiedHash)
                || (row.enrolled() && !consumeTotp(row, factor, clock.instant()))) {
            return false;
        }
        store.updatePassword(row.id(), newHash);
        return true;
    }

    @Transactional
    boolean reset(long id, String resetCode, String factor, String newHash) {
        var row = store.lock(id).orElse(null);
        Instant now = clock.instant();
        if (row == null || !row.validReset(resetCode, now) || (row.enrolled() && !consumeTotp(row, factor, now))) {
            return false;
        }
        store.updatePassword(row.id(), newHash);
        return true;
    }

    @Transactional
    boolean recover(ResetEnrollment pending, String totp, String newHash) {
        var row = store.lock(pending.accountId()).orElse(null);
        Instant now = clock.instant();
        if (row == null || row.version() != pending.version() || !row.validReset(pending.resetCode(), now)
                || !AuthSecrets.matchesDigest(pending.previousRecovery(), row.recoveryDigest())) {
            return false;
        }
        long step = AuthSecrets.step(now);
        var enrollment = pending.enrollment();
        if (!AuthSecrets.matchesTotp(enrollment.seed(), step, totp)) {
            return false;
        }
        store.recover(row.id(), newHash, secrets.encrypt(row.id(), enrollment.seed()), step, AuthSecrets.digest(enrollment.recovery()));
        return true;
    }

    private boolean consumeTotp(CredentialStore.Credential row, String code, Instant now) {
        long step = AuthSecrets.step(now);
        if (step <= row.lastStep() || !AuthSecrets.matchesTotp(secrets.decrypt(row.id(), row.seed()), step, code)) {
            return false;
        }
        store.acceptStep(row.id(), step);
        return true;
    }

    record Enrollment(byte[] seed, String recovery) {
        static Enrollment create() {
            return new Enrollment(AuthSecrets.randomBytes(20), AuthSecrets.token());
        }

        Setup setup() {
            return new Setup(AuthSecrets.base32(seed), recovery);
        }
    }

    record Setup(String secret, String recovery) {}

    record ResetEnrollment(long accountId, long version, String email, String resetCode, String previousRecovery,
            Enrollment enrollment) {}
}
