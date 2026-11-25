package br.com.autocuidado.auth;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy(false);

    @Test
    void acceptsEightCharactersWithAllRequiredTypes() {
        for (String value : List.of("Aa1!bcde", "Água7€xy", "Aa1🙂bcde", "Aa1! bcd")) {
            assertNull(policy.check(value, "pessoa@example.test"));
        }
    }

    @Test
    void rejectsMissingTypesAndDoesNotCountWhitespaceOrCombiningMarksAsSymbols() {
        assertFalse(PasswordPolicy.validFormat(null));
        for (String value : List.of("", "Ab1!abc", "abcdef1!", "ABCDEFG1!", "Abcdefg!", "Abcdefg1",
                "Abcd123 ", "Abcdefg1\u0301")) {
            assertEquals(PasswordPolicy.REQUIREMENTS, policy.check(value, "pessoa@example.test"));
        }
    }

    @Test
    void countsUnicodeCodePointsAndKeepsTheUpperLimit() {
        assertTrue(PasswordPolicy.validFormat("Aa1!" + "🙂".repeat(124)));
        assertFalse(PasswordPolicy.validFormat("Aa1!" + "🙂".repeat(125)));
        assertTrue(PasswordPolicy.validFormat("Aa1!" + "x".repeat(124)));
        assertFalse(PasswordPolicy.validFormat("Aa1!" + "x".repeat(125)));
    }

    @Test
    void retainsContextualPasswordRejections() {
        for (String value : List.of("Pessoa1!", "Autocuidado1!", "CronogramaCapilar1!")) {
            assertEquals("Escolha uma senha que não contenha o nome da conta ou da aplicação.",
                    policy.check(value, "pessoa@example.test"));
        }
    }
}
