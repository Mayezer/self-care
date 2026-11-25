package br.com.autocuidado.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class PasswordPolicy {
    static final String REQUIREMENTS = "Use de 8 a 128 caracteres, com maiúscula, minúscula, número e símbolo.";
    private static final Pattern COMPOSITION = Pattern.compile(
            "(?=.*\\p{Lu})(?=.*\\p{Ll})(?=.*\\p{Nd})(?=.*[\\p{P}\\p{S}]).*", Pattern.DOTALL);
    private static final URI RANGE = URI.create("https://api.pwnedpasswords.com/range/");
    private static final Pattern RANGE_LINE = Pattern.compile("[0-9A-F]{35}:[0-9]+");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final boolean checkExposure;

    PasswordPolicy(@Value("${app.auth.password-exposure-check:true}") boolean checkExposure) {
        this.checkExposure = checkExposure;
    }

    String check(String password, String identifier) {
        if (!validFormat(password)) {
            return REQUIREMENTS;
        }
        String lower = password.toLowerCase(Locale.ROOT);
        String accountName = identifier.split("@", 2)[0].toLowerCase(Locale.ROOT);
        if (lower.contains("autocuidado") || lower.contains("cronogramacapilar")
                || (accountName.length() >= 4 && lower.contains(accountName))) {
            return "Escolha uma senha que não contenha o nome da conta ou da aplicação.";
        }
        return checkExposure && exposed(password) ? "Essa senha apareceu em vazamentos. Escolha outra." : null;
    }

    static boolean validFormat(String password) {
        if (password == null || password.length() > 256) {
            return false;
        }
        int length = password.codePointCount(0, password.length());
        return length >= 8 && length <= 128 && COMPOSITION.matcher(password).matches();
    }

    void require(String password, String identifier) {
        String issue = check(password, identifier);
        if (issue != null) {
            throw new Rejected(issue);
        }
    }

    private boolean exposed(String password) {
        String hash = HexFormat.of().withUpperCase().formatHex(AuthSecrets.hash("SHA-1", password));
        var request = HttpRequest.newBuilder(RANGE.resolve(hash.substring(0, 5)))
                .timeout(Duration.ofSeconds(4))
                .header("Add-Padding", "true")
                .header("User-Agent", "Agenda-de-autocuidado")
                .GET().build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), 100_000));
            if (response.statusCode() != 200) {
                throw new VerificationUnavailable();
            }
            return contains(response.body(), hash.substring(5));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VerificationUnavailable();
        } catch (IOException exception) {
            throw new VerificationUnavailable();
        }
    }

    static boolean contains(String range, String suffix) {
        var lines = range.lines().toList();
        if (lines.isEmpty() || lines.stream().anyMatch(line -> !RANGE_LINE.matcher(line).matches())) {
            throw new VerificationUnavailable();
        }
        return lines.stream().anyMatch(line -> line.startsWith(suffix)
                && line.substring(36).chars().anyMatch(digit -> digit != '0'));
    }

    static final class VerificationUnavailable extends RuntimeException {}

    static final class Rejected extends RuntimeException {
        Rejected(String message) {
            super(message);
        }
    }
}
