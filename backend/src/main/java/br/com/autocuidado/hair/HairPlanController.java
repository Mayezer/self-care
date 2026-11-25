package br.com.autocuidado.hair;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.auth.AccountTimeZones;
import br.com.autocuidado.persistence.HairPlanService;
import br.com.autocuidado.persistence.HairPlanService.Occurrence;
import br.com.autocuidado.persistence.HairProfileStore;
import br.com.autocuidado.persistence.PlanCreationConflictException;
import br.com.autocuidado.recurrence.RecurrenceDateGenerator;
import br.com.autocuidado.recurrence.RecurrenceDateGenerator.InvalidRecurrenceException;
import br.com.autocuidado.recurrence.RecurrenceRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@RestController
public class HairPlanController {

    private final RecurrenceDateGenerator generator;
    private final HairPlanService plans;
    private final AccountTimeZones timeZones;
    private final HairProfileStore profiles;
    private final boolean profileEnabled;

    HairPlanController(RecurrenceDateGenerator generator, HairPlanService plans, AccountTimeZones timeZones,
            HairProfileStore profiles, @Value("${app.hair.profile-enabled:false}") boolean profileEnabled) {
        this.generator = generator;
        this.plans = plans;
        this.timeZones = timeZones;
        this.profiles = profiles;
        this.profileEnabled = profileEnabled;
    }

    @PostMapping(path = "/api/hair-plans/preview", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> preview(@RequestBody JsonNode request, @AuthenticationPrincipal AccountPrincipal account) {
        if (!request.isObject()) {
            return invalidBody();
        }

        var errors = new LinkedHashMap<String, String>();
        var schedule = RecurrenceRequest.parse(request, errors);
        var suggestion = parseSuggestion(request.path("suggestion"), errors);
        ZoneId zone = AccountTimeZones.DEFAULT_ZONE;
        if (request.has("timeZone") && (!request.path("timeZone").isString()
                || !AccountTimeZones.isValid(request.path("timeZone").asString()))) {
            errors.put("timeZone", "A agenda usa sempre o horário de São Paulo (America/Sao_Paulo).");
        }
        if (!errors.isEmpty()) {
            return invalidFields(errors);
        }

        var dates = generator.generate(schedule.startDate(), schedule.weeks(), schedule.weekdays(), zone);
        if (suggestion == null) {
            return ResponseEntity.ok(new Preview(dates.stream()
                    .map(date -> new Occurrence(date, HairStage.WASH_ONLY)).toList()));
        }

        var profile = profileEnabled && account != null && suggestion.useProfile()
                ? profiles.find(account.accountId()) : JsonNodeFactory.instance.objectNode();
        var routine = HairCareRoutine.from(profile);
        var occurrences = dates.stream().map(date -> new Occurrence(date, HairStage.WASH_ONLY)).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SuggestedPreview(occurrences, routine));
    }

    @PostMapping(path = "/api/hair-plans", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> create(@RequestBody JsonNode request, @AuthenticationPrincipal AccountPrincipal account) {
        if (!request.isObject()) {
            return invalidBody();
        }
        var errors = new LinkedHashMap<String, String>();
        var schedule = RecurrenceRequest.parse(request, errors);
        UUID requestId = RecurrenceRequest.parseRequestId(request.path("requestId"), errors);
        Map<LocalDate, HairStage> stages = parseStages(request.path("occurrences"), errors);
        var suggestion = parseSuggestion(request.path("suggestion"), errors);
        if (request.has("accountId") || request.has("planId")) {
            errors.put("body", "A conta é identificada pela sessão e o plano recebe um novo identificador.");
        }
        if (!errors.isEmpty()) {
            return invalidFields(errors);
        }
        long planId = suggestion == null
                ? plans.create(account, requestId, schedule.startDate(), schedule.weeks(), schedule.weekdays(), stages)
                : plans.create(account, requestId, schedule.startDate(), schedule.weeks(), schedule.weekdays(), stages,
                        suggestion.useProfile());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedPlan(planId));
    }

    @GetMapping("/api/hair-plans/{planId}")
    ResponseEntity<HairPlanService.SavedPlan> find(@PathVariable long planId,
            @AuthenticationPrincipal AccountPrincipal account) {
        return ResponseEntity.of(plans.find(account.accountId(), planId));
    }

    @PostMapping("/api/hair-plans/{planId}/close")
    ResponseEntity<HairPlanService.ClosedPlan> close(@PathVariable long planId,
            @AuthenticationPrincipal AccountPrincipal account) {
        return ResponseEntity.of(plans.close(account, planId));
    }

    private static Map<LocalDate, HairStage> parseStages(JsonNode value, Map<String, String> errors) {
        var stages = new LinkedHashMap<LocalDate, HairStage>();
        if (value.isArray() && value.size() <= 84) {
            for (JsonNode occurrence : value) {
                LocalDate date = RecurrenceRequest.parseDate(occurrence.path("date"));
                try {
                    HairStage stage = HairStage.valueOf(occurrence.path("stage").asString(""));
                    if (date != null && stages.putIfAbsent(date, stage) == null) {
                        continue;
                    }
                } catch (IllegalArgumentException ignored) {
                    // The field error covers invalid dates, stages and duplicate dates.
                }
                errors.put("occurrences", "Informe uma etapa válida para cada data da prévia, sem repetir datas.");
                break;
            }
        } else {
            errors.put("occurrences", "Informe as etapas das sessões da prévia.");
        }
        return stages;
    }

    private static SuggestionRequest parseSuggestion(JsonNode value, Map<String, String> errors) {
        if (value.isMissingNode()) {
            return null;
        }
        var useProfile = value.path("useProfile");
        if (!value.isObject() || !Set.of("useProfile").containsAll(value.propertyNames())
                || (!useProfile.isMissingNode() && !useProfile.isBoolean())) {
            errors.put("suggestion", "Informe apenas se deseja usar as respostas de Meu cabelo. Etapas opcionais são escolhidas em cada sessão.");
            return null;
        }
        return new SuggestionRequest(useProfile.asBoolean(false));
    }

    @ExceptionHandler(InvalidRecurrenceException.class)
    ResponseEntity<FieldErrors> invalidRecurrence(InvalidRecurrenceException exception) {
        return invalidFields(RecurrenceRequest.fieldErrors(exception));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<FieldErrors> invalidBody() {
        return invalidFields(Map.of("body", "Envie um objeto JSON válido."));
    }

    @ExceptionHandler(HairPlanService.PreviewMismatchException.class)
    ResponseEntity<FieldErrors> previewMismatch() {
        return invalidFields(Map.of("occurrences", "As datas não correspondem à prévia. Gere a prévia novamente."));
    }

    @ExceptionHandler(PlanCreationConflictException.class)
    ResponseEntity<FieldErrors> requestConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new FieldErrors(Map.of("requestId", "Este pedido já foi usado com outras escolhas.")));
    }

    private static ResponseEntity<FieldErrors> invalidFields(Map<String, String> errors) {
        return ResponseEntity.badRequest().body(new FieldErrors(errors));
    }

    record Preview(List<Occurrence> occurrences) {}
    record SuggestedPreview(List<Occurrence> occurrences, HairCareRoutine suggestion) {}
    private record SuggestionRequest(boolean useProfile) {}
    record CreatedPlan(long planId) {}
    record FieldErrors(Map<String, String> errors) {}
}
