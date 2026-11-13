package br.com.autocuidado.agenda;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.persistence.AgendaService;
import br.com.autocuidado.persistence.AgendaService.Action;
import br.com.autocuidado.persistence.CarePeriod;
import br.com.autocuidado.persistence.PlanCreationConflictException;
import br.com.autocuidado.recurrence.RecurrenceDateGenerator.InvalidRecurrenceException;
import br.com.autocuidado.recurrence.RecurrenceRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class AgendaController {

    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern NON_WHITESPACE = Pattern.compile("\\S", Pattern.UNICODE_CHARACTER_CLASS);

    private final AgendaService agenda;

    AgendaController(AgendaService agenda) {
        this.agenda = agenda;
    }

    @GetMapping("/api/agenda/week")
    ResponseEntity<?> week(@AuthenticationPrincipal AccountPrincipal account,
                           @RequestParam(required = false) String date) {
        LocalDate selectedDate = parseDate(date);
        if (date != null && selectedDate == null) {
            return invalidField("date", "Informe uma data válida.");
        }
        return ResponseEntity.ok(agenda.loadWeek(account, selectedDate));
    }

    @GetMapping("/api/history")
    List<AgendaService.HistoryItem> history(@AuthenticationPrincipal AccountPrincipal account) {
        return agenda.loadHistory(account);
    }

    @PostMapping("/api/occurrences/skin")
    ResponseEntity<?> scheduleSkinCare(@AuthenticationPrincipal AccountPrincipal account, @RequestBody JsonNode request) {
        if (!request.isObject()) {
            return invalidBody();
        }
        var errors = new LinkedHashMap<String, String>();
        String title = parseTitle(request.path("title"), "para o cuidado de pele", errors);
        LocalDate date = parsePlannedDate(request.path("plannedDate"), errors);
        CarePeriod period = parseSkinPeriod(request.path("period"), errors);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }
        return scheduledCare(agenda.scheduleSkinCare(account, title, date, period));
    }

    @PostMapping("/api/occurrences/{category:makeup|nails}")
    ResponseEntity<?> scheduleBeautyCare(@AuthenticationPrincipal AccountPrincipal account,
                                         @PathVariable String category, @RequestBody JsonNode request) {
        return saveBeautyCare(account, category, request, false);
    }

    @PostMapping("/api/history/{category:makeup|nails}")
    ResponseEntity<?> recordBeautyCare(@AuthenticationPrincipal AccountPrincipal account,
                                       @PathVariable String category, @RequestBody JsonNode request) {
        return saveBeautyCare(account, category, request, true);
    }

    private ResponseEntity<?> saveBeautyCare(AccountPrincipal account, String category, JsonNode request, boolean direct) {
        if (!request.isObject()) {
            return invalidBody();
        }
        var errors = new LinkedHashMap<String, String>();
        boolean makeup = category.equals("makeup");
        String title = parseTitle(request.path("title"), makeup ? "para a maquiagem" : "para o cuidado de unhas", errors);
        LocalDate date = direct ? parseCompletionDate(request.path("completedOn"), errors)
                : parsePlannedDate(request.path("plannedDate"), errors);
        String note = makeup ? parseOptionalText(request, "note", "Informe a nota em texto.", errors) : null;
        String color = makeup ? null : parseOptionalText(request, "polishColor", "Informe a cor em texto.", errors);
        String product = makeup ? null : parseOptionalText(request, "polishProduct", "Informe o produto em texto.", errors);
        if (request.has("accountId") || request.has("planId")) {
            errors.put("body", "O cuidado avulso pertence à conta da sessão e não tem plano.");
        }
        if (direct && request.hasNonNull("plannedDate")) {
            errors.put("plannedDate", "O registro direto não tem data planejada.");
        }
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }
        if (direct) {
            Long id = makeup ? agenda.recordMakeup(account, title, date, note)
                    : agenda.recordNails(account, title, date, color, product);
            return id == null ? futureCompletion() : createdCare(id);
        }
        return scheduledCare(makeup ? agenda.scheduleMakeup(account, title, date, note)
                : agenda.scheduleNails(account, title, date, color, product));
    }

    private static ResponseEntity<?> scheduledCare(Long id) {
        if (id == null) {
            return invalidField("plannedDate", "Escolha a data de hoje ou uma data futura.");
        }
        return createdCare(id);
    }

    private static ResponseEntity<?> createdCare(long id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PostMapping("/api/skin-plans")
    ResponseEntity<?> scheduleSkinPlan(@AuthenticationPrincipal AccountPrincipal account, @RequestBody JsonNode request) {
        if (!request.isObject()) {
            return invalidBody();
        }
        var errors = new LinkedHashMap<String, String>();
        String title = parseTitle(request.path("title"), "para o cuidado de pele", errors);
        CarePeriod period = parseSkinPeriod(request.path("period"), errors);
        var schedule = RecurrenceRequest.parse(request, errors);
        var requestId = RecurrenceRequest.parseRequestId(request.path("requestId"), errors);
        if (request.has("accountId") || request.has("planId")) {
            errors.put("body", "A conta é identificada pela sessão e o plano recebe um novo identificador.");
        }
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }
        long planId = agenda.scheduleSkinPlan(account, requestId, schedule.startDate(), schedule.weeks(),
                schedule.weekdays(), title, period);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("planId", planId));
    }

    @PostMapping("/api/skin-plans/{planId}/close")
    ResponseEntity<Void> closeSkinPlan(@AuthenticationPrincipal AccountPrincipal account, @PathVariable long planId) {
        return agenda.closeSkinPlan(account, planId) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private static String parseTitle(JsonNode titleField, String context, Map<String, String> errors) {
        String title = titleField.isString() ? titleField.asString().strip() : "";
        if (!NON_WHITESPACE.matcher(title).find() || title.indexOf(0) >= 0) {
            errors.put("title", "Informe um título válido " + context + ".");
        }
        return title;
    }

    private static LocalDate parsePlannedDate(JsonNode value, Map<String, String> errors) {
        LocalDate date = parseDate(value.isString() ? value.asString() : null);
        if (date == null) {
            errors.put("plannedDate", "Informe uma data planejada válida.");
        }
        return date;
    }

    private static LocalDate parseCompletionDate(JsonNode value, Map<String, String> errors) {
        LocalDate date = parseDate(value.isString() ? value.asString() : null);
        if (date == null) {
            errors.put("completedOn", "Informe uma data de realização válida.");
        }
        return date;
    }

    private static String parseOptionalText(JsonNode request, String field, String message, Map<String, String> errors) {
        JsonNode value = request.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString() || value.asString().indexOf(0) >= 0) {
            errors.put(field, message);
            return null;
        }
        String text = value.asString().strip();
        return NON_WHITESPACE.matcher(text).find() ? text : null;
    }

    private static CarePeriod parseSkinPeriod(JsonNode periodField, Map<String, String> errors) {
        CarePeriod period = null;
        if (!periodField.isMissingNode() && !periodField.isNull()) {
            if (periodField.isString()) {
                period = switch (periodField.asString()) {
                    case "MORNING" -> CarePeriod.MORNING;
                    case "NIGHT" -> CarePeriod.NIGHT;
                    default -> null;
                };
            }
            if (period == null) {
                errors.put("period", "Escolha Manhã, Noite ou Sem período.");
            }
        }
        return period;
    }

    @PostMapping("/api/occurrences/{id}/complete")
    ResponseEntity<?> complete(@AuthenticationPrincipal AccountPrincipal account, @PathVariable long id,
                               @RequestBody JsonNode request) {
        if (!request.isObject()) {
            return invalidBody();
        }
        var errors = new LinkedHashMap<String, String>();
        LocalDate date = parseCompletionDate(request.path("completedOn"), errors);
        return errors.isEmpty() ? act(account, id, Action.COMPLETE, date)
                : ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    @PostMapping("/api/occurrences/{id}/skip")
    ResponseEntity<?> skip(@AuthenticationPrincipal AccountPrincipal account, @PathVariable long id) {
        return act(account, id, Action.SKIP, null);
    }

    @PostMapping("/api/occurrences/{id}/undo")
    ResponseEntity<?> undo(@AuthenticationPrincipal AccountPrincipal account, @PathVariable long id) {
        return act(account, id, Action.UNDO, null);
    }

    @PostMapping("/api/occurrences/{id}/reschedule")
    ResponseEntity<?> reschedule(@AuthenticationPrincipal AccountPrincipal account, @PathVariable long id,
                                 @RequestBody JsonNode request) {
        if (!request.isObject()) {
            return invalidBody();
        }
        JsonNode value = request.path("plannedDate");
        LocalDate date = parseDate(value.isString() ? value.asString() : null);
        if (date == null) {
            return invalidField("plannedDate", "Informe uma data válida para o reagendamento.");
        }
        return switch (agenda.reschedule(account, id, date)) {
            case APPLIED -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case CONFLICT -> conflict("status", "Este cuidado não pode ser reagendado.");
            case PAST_DATE -> invalidField("plannedDate", "Escolha a data de hoje ou uma data futura.");
            case OCCUPIED_DATE -> conflict("plannedDate", "Já existe um cuidado desta rotina nessa data.");
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidBody() {
        return invalidField("body", "Envie um objeto JSON válido.");
    }

    @ExceptionHandler(InvalidRecurrenceException.class)
    ResponseEntity<?> invalidRecurrence(InvalidRecurrenceException exception) {
        return ResponseEntity.badRequest().body(Map.of("errors", RecurrenceRequest.fieldErrors(exception)));
    }

    @ExceptionHandler(PlanCreationConflictException.class)
    ResponseEntity<?> requestConflict() {
        return conflict("requestId", "Este pedido já foi usado com outras escolhas.");
    }

    private ResponseEntity<?> act(AccountPrincipal account, long id, Action action, LocalDate date) {
        return switch (agenda.act(account, id, action, date)) {
            case APPLIED -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case CONFLICT -> conflict("status", "Este cuidado não permite a ação selecionada.");
            case INVALID_COMPLETION_DATE -> futureCompletion();
        };
    }

    private static ResponseEntity<?> futureCompletion() {
        return invalidField("completedOn", "Escolha a data de hoje ou uma data passada.");
    }

    private static LocalDate parseDate(String value) {
        if (value != null && DATE.matcher(value).matches() && !value.startsWith("0000")) {
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException ignored) {
                // An impossible calendar date gets the same field error as an invalid format.
            }
        }
        return null;
    }

    private static ResponseEntity<?> invalidField(String field, String message) {
        return ResponseEntity.badRequest().body(Map.of("errors", Map.of(field, message)));
    }

    private static ResponseEntity<?> conflict(String field, String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("errors", Map.of(field, message)));
    }
}
