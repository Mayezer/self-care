package br.com.autocuidado.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthSecretsTest {
    @Test
    void producesRfc6238CodesAndUsesThirtySecondSteps() {
        var reference = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertEquals("287082", AuthSecrets.totp(reference, AuthSecrets.step(Instant.ofEpochSecond(59))));
        assertEquals("081804", AuthSecrets.totp(reference, AuthSecrets.step(Instant.ofEpochSecond(1_111_111_109))));
        assertFalse(AuthSecrets.matchesTotp(reference, AuthSecrets.step(Instant.ofEpochSecond(60)), "287082"));
    }

    @Test
    void encryptsTheSeedWithAccountBinding() {
        var secrets = new AuthSecrets(HexFormat.of().formatHex(AuthSecrets.randomBytes(32)));
        var seed = AuthSecrets.randomBytes(20);
        var encrypted = secrets.encrypt(1L, seed);
        assertArrayEquals(seed, secrets.decrypt(1L, encrypted));
        assertThrows(IllegalStateException.class, () -> secrets.decrypt(2L, encrypted));
    }

    @Test
    void ignoresPaddingButRejectsPositiveMatchesAndInvalidRangeResponses() {
        var suffix = "A".repeat(35);
        assertFalse(PasswordPolicy.contains(suffix + ":0\r\n", suffix));
        assertTrue(PasswordPolicy.contains(suffix + ":10\r\n", suffix));
        assertThrows(PasswordPolicy.VerificationUnavailable.class, () -> PasswordPolicy.contains("invalid", suffix));
    }
}
