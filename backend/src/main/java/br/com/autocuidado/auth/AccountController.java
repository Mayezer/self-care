package br.com.autocuidado.auth;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import br.com.autocuidado.persistence.AgendaReminderService;
import br.com.autocuidado.persistence.AccountProfileStore;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mail.MailException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class AccountController {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{22}");
    private final EmailRegistration registration;
    private final Clock clock;
    private final AccountTimeZones timeZones;
    private final AgendaReminderService reminders;
    private final AccountProfileStore profiles;
    private final boolean hairProfileAvailable;
    private final boolean hairProductsAvailable;
    private final boolean guidesAvailable;
    private final boolean forumAvailable;
    private final boolean forumPublicReadingAvailable;
    private final boolean billingAvailable;
    private final boolean hairDiaryAvailable;
    private final boolean registrationEnabled;

    AccountController(EmailRegistration registration, Clock clock, AccountTimeZones timeZones, AgendaReminderService reminders,
                      AccountProfileStore profiles,
                      @Value("${app.hair.profile-enabled:false}") boolean hairProfileAvailable,
                      @Value("${app.hair.products-enabled:false}") boolean hairProductsEnabled,
                      @Value("${app.help.guides-enabled:false}") boolean guidesAvailable,
                      @Value("${app.forum.enabled:false}") boolean forumAvailable,
                      @Value("${app.forum.public-reading-enabled:false}") boolean forumPublicReadingAvailable,
                      @Value("${app.billing.enabled:false}") boolean billingAvailable,
                      @Value("${app.hair.diary-enabled:false}") boolean hairDiaryEnabled,
                      @Value("${app.auth.registration-enabled:true}") boolean registrationEnabled) {
        this.registration = registration;
        this.clock = clock;
        this.timeZones = timeZones;
        this.reminders = reminders;
        this.profiles = profiles;
        this.hairProfileAvailable = hairProfileAvailable;
        this.hairProductsAvailable = hairProfileAvailable && hairProductsEnabled;
        this.guidesAvailable = guidesAvailable;
        this.forumAvailable = forumAvailable && forumPublicReadingAvailable;
        this.forumPublicReadingAvailable = forumPublicReadingAvailable;
        this.billingAvailable = billingAvailable;
        this.hairDiaryAvailable = billingAvailable && hairDiaryEnabled;
        this.registrationEnabled = registrationEnabled;
    }

    @GetMapping("/api/auth/csrf")
    CsrfHeader csrf(CsrfToken token) {
        return new CsrfHeader(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/api/auth/register")
    ResponseEntity<FieldErrors> register(@RequestBody JsonNode request) {
        if (!registrationEnabled) {
            return registrationUnavailable();
        }
        if (!request.isObject()) {
            return invalidBody();
        }
        String email = emailField(request.path("email"));
        if (email == null) {
            return fieldError(HttpStatus.BAD_REQUEST, "email", "Informe um endereço de e-mail válido.");
        }
        return registration.requestCode(email) ? ResponseEntity.accepted().build()
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @PostMapping("/api/auth/confirm")
    ResponseEntity<FieldErrors> confirm(@RequestBody JsonNode request) {
        if (!registrationEnabled) {
            return registrationUnavailable();
        }
        if (!request.isObject()) {
            return invalidBody();
        }
        var errors = new LinkedHashMap<String, String>();
        String email = emailField(request.path("email"));
        if (email == null) {
            errors.put("email", "Informe um endereço de e-mail válido.");
        }
        String code = request.path("code").asString("").strip();
        if (!CODE.matcher(code).matches()) {
            errors.put("code", "Cole o código recebido por e-mail.");
        }
        JsonNode passwordField = request.path("password");
        String password = passwordField.asString("");
        if (!passwordField.isString() || !PasswordPolicy.validFormat(password)) {
            errors.put("password", PasswordPolicy.REQUIREMENTS);
        }
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new FieldErrors(errors));
        }
        String zone = request.path("timeZone").asString("");
        String timeZone = AccountTimeZones.isValid(zone) ? zone : EmailRegistration.DEFAULT_TIME_ZONE;
        if (!registration.confirm(email, code, password, timeZone)) {
            return fieldError(HttpStatus.BAD_REQUEST, "code", "Código inválido ou expirado. Solicite um novo código.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/api/agenda")
    AgendaAccount agenda(@AuthenticationPrincipal AccountPrincipal account) {
        var zone = timeZones.current(account.accountId());
        return new AgendaAccount(account.email() == null ? account.getUsername() : null,
                account.email(), zone.getId(), LocalDate.now(clock.withZone(zone)), reminders.available(),
                hairProfileAvailable, hairProductsAvailable, guidesAvailable, forumAvailable, billingAvailable, hairDiaryAvailable,
                registrationEnabled, forumPublicReadingAvailable, profiles.find(account.accountId()));
    }

    @GetMapping("/api/help/features")
    ResponseEntity<HelpFeatures> helpFeatures() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new HelpFeatures(guidesAvailable, forumPublicReadingAvailable));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<FieldErrors> invalidBody() {
        return fieldError(HttpStatus.BAD_REQUEST, "body", "Envie os dados da conta em um objeto JSON válido.");
    }

    @ExceptionHandler(MailException.class)
    ResponseEntity<FieldErrors> mailUnavailable() {
        return fieldError(HttpStatus.SERVICE_UNAVAILABLE, "body", "Não foi possível enviar o código. Tente novamente.");
    }

    private static ResponseEntity<FieldErrors> fieldError(HttpStatus status, String field, String message) {
        return ResponseEntity.status(status).body(new FieldErrors(Map.of(field, message)));
    }

    private static ResponseEntity<FieldErrors> registrationUnavailable() {
        return fieldError(HttpStatus.FORBIDDEN, "body", "O cadastro de novas contas está fechado.");
    }

    private static String emailField(JsonNode field) {
        return field.isString() ? readEmail(field.asString("")) : null;
    }

    static String readEmail(String input) {
        if (input == null || input.length() > 254) {
            return null;
        }
        String email = EmailRegistration.normalizeEmail(input);
        int separator = email.lastIndexOf('@');
        if (separator < 1 || separator > 64
                || !email.chars().allMatch(character -> character > 32 && character < 127)) {
            return null;
        }
        try {
            var address = new InternetAddress(email, true);
            address.validate();
            return email.equals(address.getAddress()) && address.getPersonal() == null ? email : null;
        } catch (AddressException exception) {
            return null;
        }
    }

    record CsrfHeader(String headerName, String token) {}

    record FieldErrors(Map<String, String> errors) {}

    record AgendaAccount(String username, String email, String timeZone, LocalDate today, boolean remindersAvailable,
                         boolean hairProfileAvailable, boolean hairProductsAvailable, boolean guidesAvailable, boolean forumAvailable,
                         boolean billingAvailable, boolean hairDiaryAvailable, boolean registrationEnabled,
                         boolean forumPublicReadingAvailable, AccountProfileStore.Profile profile) {}

    record HelpFeatures(boolean guidesAvailable, boolean forumAvailable) {}
}
