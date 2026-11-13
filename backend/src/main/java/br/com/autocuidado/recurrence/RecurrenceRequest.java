package br.com.autocuidado.recurrence;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import br.com.autocuidado.recurrence.RecurrenceDateGenerator.InvalidRecurrenceException;
import tools.jackson.databind.JsonNode;

public record RecurrenceRequest(LocalDate startDate, int weeks, Set<DayOfWeek> weekdays) {

    private static final Pattern ISO_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    public static RecurrenceRequest parse(JsonNode request, Map<String, String> errors) {
        LocalDate startDate = parseDate(request.path("startDate"));
        if (startDate == null) {
            errors.put("startDate", "Informe uma data válida no formato AAAA-MM-DD.");
        }
        // Check JSON types explicitly so numbers and strings are not silently converted.
        JsonNode weeks = request.path("weeks");
        if (!weeks.isIntegralNumber() || !weeks.canConvertToInt()) {
            errors.put("weeks", "Informe a duração como um número inteiro de semanas.");
        }
        return new RecurrenceRequest(startDate, weeks.asInt(0), parseWeekdays(request.path("weekdays"), errors));
    }

    public static LocalDate parseDate(JsonNode value) {
        if (value.isString() && ISO_DATE.matcher(value.asString()).matches()) {
            try {
                return LocalDate.parse(value.asString());
            } catch (DateTimeParseException ignored) {
                // An impossible calendar date gets the same field error as an invalid format.
            }
        }
        return null;
    }

    public static UUID parseRequestId(JsonNode value, Map<String, String> errors) {
        String id = value.asString("");
        try {
            if (id.length() == 36) {
                return UUID.fromString(id);
            }
        } catch (IllegalArgumentException ignored) {
            // A malformed identifier gets the same field error as a missing one.
        }
        errors.put("requestId", "Identificador de salvamento inválido.");
        return null;
    }

    private static Set<DayOfWeek> parseWeekdays(JsonNode value, Map<String, String> errors) {
        var weekdays = EnumSet.noneOf(DayOfWeek.class);
        if (!value.isArray()) {
            errors.put("weekdays", "Informe os dias da semana em uma lista.");
            return weekdays;
        }
        for (JsonNode day : value) {
            try {
                weekdays.add(DayOfWeek.valueOf(day.asString("")));
            } catch (IllegalArgumentException ignored) {
                errors.put("weekdays", "Selecione apenas dias válidos da semana.");
                break;
            }
        }
        return weekdays;
    }

    public static Map<String, String> fieldErrors(InvalidRecurrenceException exception) {
        return switch (exception.problem()) {
            case INVALID_START_DATE -> Map.of("startDate", "Escolha a data de hoje ou uma data futura.");
            case INVALID_DURATION -> Map.of("weeks", "Escolha uma duração de 1 a 12 semanas.");
            case INVALID_WEEKDAYS -> Map.of("weekdays", "Selecione pelo menos um dia da semana.");
            case MISSING_ZONE -> Map.of("timeZone", "Informe um fuso horário IANA válido.");
            case DATE_RANGE_OVERFLOW -> Map.of("startDate", "O fim do período excede o limite de datas.");
        };
    }
}
