package br.com.autocuidado.agenda;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.auth.AccountTimeZones;
import br.com.autocuidado.persistence.AgendaReminderService;
import br.com.autocuidado.persistence.CareCategory;
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
@RequestMapping("/api/agenda/reminder-preferences")
class AgendaReminderController {
    private static final Pattern TIME = Pattern.compile("(?:[01][0-9]|2[0-3]):[0-5][0-9]");
    private final AgendaReminderService reminders;

    AgendaReminderController(AgendaReminderService reminders) {
        this.reminders = reminders;
    }

    @GetMapping
    ResponseEntity<SettingsResponse> find(@AuthenticationPrincipal AccountPrincipal account) {
        if (!reminders.available()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(response(reminders.load(account.accountId())));
    }

    @PutMapping
    ResponseEntity<?> replace(@AuthenticationPrincipal AccountPrincipal account, @RequestBody JsonNode request) {
        if (!reminders.available()) return ResponseEntity.notFound().build();
        if (!request.isObject() || request.has("accountId")) return invalidBody();

        var errors = new LinkedHashMap<String, String>();
        var activation = request.path("enabled");
        boolean enabled = activation.isBoolean() && activation.asBoolean();
        if (!activation.isBoolean()) {
            errors.put("enabled", "Informe se deseja ativar os lembretes na agenda.");
        }
        var categories = EnumSet.noneOf(CareCategory.class);
        var selection = request.path("categories");
        if (!selection.isArray() || selection.size() > CareCategory.values().length) {
            errors.put("categories", "Escolha categorias válidas.");
        } else {
            for (var entry : selection) {
                try {
                    if (categories.add(CareCategory.valueOf(entry.asString("")))) continue;
                } catch (IllegalArgumentException ignored) {
                    // Invalid and repeated values use the same field error.
                }
                errors.put("categories", "Escolha categorias válidas, sem repetir.");
                break;
            }
            if (enabled && selection.isEmpty()) {
                errors.put("categories", "Escolha pelo menos uma categoria.");
            }
        }

        var timeField = request.path("time");
        String time = timeField.asString("");
        boolean validTime = timeField.isString() && TIME.matcher(time).matches();
        if (!validTime && (enabled || !timeField.isNull())) {
            errors.put("time", "Informe um horário válido, como 09:00.");
        }
        String timeZone = AccountTimeZones.DEFAULT_ID;
        if (request.has("timeZone") && (!request.path("timeZone").isString()
                || !AccountTimeZones.isValid(request.path("timeZone").asString()))) {
            errors.put("timeZone", "A agenda usa sempre o horário de São Paulo (America/Sao_Paulo).");
        }
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(Map.of("errors", errors));

        var preferences = new AgendaReminderService.Preferences(enabled, categories,
                validTime ? LocalTime.parse(time) : null);
        return ResponseEntity.ok(response(reminders.replace(account.accountId(), preferences, timeZone)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidBody() {
        return ResponseEntity.badRequest().body(Map.of("errors",
                Map.of("body", "Envie as preferências da própria conta em um objeto JSON válido.")));
    }

    private static SettingsResponse response(AgendaReminderService.Settings settings) {
        var preferences = settings.preferences();
        return new SettingsResponse(preferences.enabled(), preferences.categories().stream().sorted().toList(),
                preferences.time() == null ? null : preferences.time().toString(),
                settings.timeZone(), AccountTimeZones.ids());
    }

    record SettingsResponse(boolean enabled, List<CareCategory> categories, String time,
                            String timeZone, List<String> timeZones) {}
}
