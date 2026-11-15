package br.com.autocuidado.vanity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.recurrence.RecurrenceRequest;
import br.com.autocuidado.vanity.MakeupCleaning.Accessory;
import br.com.autocuidado.vanity.MakeupCleaning.Settings;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/makeup-cleaning")
class MakeupCleaningController {
    private final MakeupCleaning cleaning;

    MakeupCleaningController(MakeupCleaning cleaning) {
        this.cleaning = cleaning;
    }

    @GetMapping
    Settings settings(@AuthenticationPrincipal AccountPrincipal account) {
        return cleaning.settings(account.accountId());
    }

    @PutMapping("/{accessory}")
    ResponseEntity<?> save(@AuthenticationPrincipal AccountPrincipal account, @PathVariable Accessory accessory,
                           @RequestBody JsonNode body) {
        if (!validBody(body, Set.of("revision", "intervalDays", "startDate"))) return invalidBody();
        var errors = new LinkedHashMap<String, String>();
        int revision = revision(body);
        if (revision < 0) errors.put("body", "Reabra os lembretes para tentar novamente.");
        int interval = integer(body.path("intervalDays"));
        if (interval < 1 || interval > MakeupCleaning.MAX_INTERVAL) {
            errors.put("intervalDays", "Informe um intervalo de 1 a " + MakeupCleaning.MAX_INTERVAL + " dias.");
        }
        var startDate = RecurrenceRequest.parseDate(body.path("startDate"));
        if (startDate == null) errors.put("startDate", "Informe uma data válida.");
        return errors.isEmpty() ? ResponseEntity.ok(cleaning.save(account.accountId(), accessory, revision, interval, startDate))
                : ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    @PostMapping("/{accessory}/pause")
    ResponseEntity<?> pause(@AuthenticationPrincipal AccountPrincipal account, @PathVariable Accessory accessory,
                            @RequestBody JsonNode body) {
        if (!validBody(body, Set.of("revision")) || revision(body) < 0) return invalidBody();
        return ResponseEntity.ok(cleaning.pause(account.accountId(), accessory, revision(body)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidBody() {
        return ResponseEntity.badRequest().body(Map.of("errors", Map.of("body", "Envie somente os dados do lembrete.")));
    }

    @ExceptionHandler(MakeupCleaning.InvalidDate.class)
    ResponseEntity<?> invalidDate() {
        return ResponseEntity.badRequest().body(Map.of("errors",
                Map.of("startDate", "Escolha hoje ou uma data futura que permita a próxima limpeza.")));
    }

    @ExceptionHandler(MakeupCleaning.RevisionConflict.class)
    ResponseEntity<?> stale() {
        return ResponseEntity.status(409).body(Map.of("errors",
                Map.of("body", "Este lembrete mudou. Atualize os dados e confira antes de salvar.")));
    }

    private static boolean validBody(JsonNode body, Set<String> fields) {
        return body.isObject() && fields.containsAll(body.propertyNames());
    }

    private static int revision(JsonNode body) {
        return integer(body.path("revision"));
    }

    private static int integer(JsonNode number) {
        return number.isIntegralNumber() && number.canConvertToInt() ? number.asInt() : -1;
    }
}
