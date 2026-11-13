package br.com.autocuidado.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import br.com.autocuidado.persistence.AccountRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
class EmailRegistration {

    static final String DEFAULT_TIME_ZONE = "America/Sao_Paulo";
    private static final int VALID_MINUTES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accounts;
    private final ObjectProvider<MailSender> mail;
    private final String from;
    private final PasswordEncoder passwords;
    private final PasswordPolicy policy;
    private final Clock clock;

    EmailRegistration(AccountRepository accounts, ObjectProvider<MailSender> mail,
            @Value("${app.auth.email.from:}") String from, PasswordEncoder passwords, PasswordPolicy policy, Clock clock) {
        this.accounts = accounts;
        this.mail = mail;
        this.from = from;
        this.passwords = passwords;
        this.policy = policy;
        this.clock = clock;
    }

    static String normalizeEmail(String email) {
        String address = email.strip();
        int domain = address.lastIndexOf('@') + 1;
        return address.substring(0, domain) + address.substring(domain).toLowerCase(Locale.ROOT);
    }

    void sendCode(String email) {
        MailSender sender = mail.getIfAvailable();
        if (sender == null || from.isBlank()) {
            throw new MailSendException("Envio de e-mail indisponível.");
        }

        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String digest = digest(code);
        var expiresAt = now().plusMinutes(VALID_MINUTES);
        if (accounts.issueConfirmation(email, DEFAULT_TIME_ZONE, digest, expiresAt, expiresAt.minusMinutes(1)) == 0) {
            return;
        }

        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Confirme seu e-mail na Agenda de autocuidado");
        message.setText("""
                Copie este código para confirmar seu e-mail e criar sua senha na Agenda de autocuidado.

                %s

                O código é válido por %d minutos e pode ser usado uma vez.
                Se você não solicitou o cadastro, pode ignorar este e-mail.
                """.formatted(code, VALID_MINUTES));
        try {
            sender.send(message);
        } catch (MailException exception) {
            accounts.expireConfirmation(email, digest, now());
            throw exception;
        }
    }

    boolean confirm(String email, String code, String password, String timeZone) {
        String digest = digest(code);
        if (!accounts.hasValidConfirmation(email, digest, now())) {
            return false;
        }
        policy.require(password, email);
        String hash = passwords.encode(password);
        return accounts.confirm(email, digest, now(), hash, timeZone) == 1;
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private static String digest(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
