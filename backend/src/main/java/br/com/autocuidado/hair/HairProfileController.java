package br.com.autocuidado.hair;

import java.time.Clock;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.auth.AccountTimeZones;
import br.com.autocuidado.persistence.HairProfileStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/hair-profile")
@ConditionalOnProperty(name = "app.hair.profile-enabled", havingValue = "true")
class HairProfileController {

    private final HairProfileStore profiles;
    private final Clock clock;
    private final AccountTimeZones timeZones;

    HairProfileController(HairProfileStore profiles, Clock clock, AccountTimeZones timeZones) {
        this.profiles = profiles;
        this.clock = clock;
        this.timeZones = timeZones;
    }

    @GetMapping
    JsonNode find(@AuthenticationPrincipal AccountPrincipal account) {
        return profiles.find(account.accountId());
    }

    @PutMapping
    ResponseEntity<?> replace(@AuthenticationPrincipal AccountPrincipal account, @RequestBody JsonNode request) {
        if (!request.isObject()) {
            return invalidBody();
        }
        var errors = new LinkedHashMap<String, String>();
        var answers = HairProfileAnswers.parse(request,
                YearMonth.now(clock.withZone(timeZones.current(account.accountId()))), errors);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new FieldErrors(errors));
        }
        profiles.replace(account.accountId(), answers);
        return ResponseEntity.ok(answers);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<FieldErrors> invalidBody() {
        return ResponseEntity.badRequest().body(new FieldErrors(Map.of("body", "Envie um objeto JSON válido.")));
    }

    record FieldErrors(Map<String, String> errors) {}
}
