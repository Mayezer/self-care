package br.com.autocuidado.hair;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import br.com.autocuidado.recurrence.RecurrenceRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

final class HairCarePreferences {
    private HairCarePreferences() {}

    static void parse(JsonNode value, ObjectNode answers, LocalDate today, Map<String, String> errors) {
        if (value.isMissingNode() || value.isNull()) return;
        if (!value.isObject() || !Set.of("washesPerWeek", "scalpSymptoms", "treatments").containsAll(value.propertyNames())) {
            invalid(errors, "Envie apenas frequência, sinais de alerta e cuidados conhecidos.");
            return;
        }
        var result = JsonNodeFactory.instance.objectNode();
        var frequency = value.path("washesPerWeek");
        if (!frequency.isMissingNode() && !frequency.isNull()) {
            if (!frequency.isIntegralNumber() || !frequency.canConvertToInt() || frequency.asInt() < 1 || frequency.asInt() > 7) {
                invalid(errors, "Escolha de 1 a 7 lavagens por semana, ou deixe sem resposta.");
            } else result.set("washesPerWeek", frequency);
        }
        var symptoms = value.path("scalpSymptoms");
        if (!symptoms.isMissingNode() && !symptoms.isNull()) {
            if (!symptoms.isString() || !Set.of("YES", "NO", "UNKNOWN").contains(symptoms.asString())) {
                invalid(errors, "Informe sim, não ou não sei para os sinais de alerta.");
            } else result.set("scalpSymptoms", symptoms);
        }
        var treatments = value.path("treatments");
        if (!treatments.isMissingNode() && !treatments.isNull()) {
            if (!treatments.isObject() || !Set.of("HYDRATION", "NUTRITION", "RECONSTRUCTION").containsAll(treatments.propertyNames())) {
                invalid(errors, "Escolha apenas hidratação, nutrição e reconstrução.");
            } else {
                var normalized = result.putObject("treatments");
                for (String stage : treatments.propertyNames()) {
                    var treatment = treatments.path(stage);
                    String status = treatment.path("status").asString("");
                    if (!treatment.isObject() || !Set.of("status", "every", "unit", "lastUse").containsAll(treatment.propertyNames())
                            || !Set.of("KNOWN", "FIRST_USE", "AVOID", "UNKNOWN").contains(status)) {
                        invalid(errors, "Confira a situação de cada cuidado.");
                        continue;
                    }
                    var item = normalized.putObject(stage).put("status", status);
                    if (Set.of("AVOID", "UNKNOWN").contains(status)) continue;
                    if (status.equals("KNOWN")) {
                        var last = RecurrenceRequest.parseDate(treatment.path("lastUse"));
                        if (last == null || last.isAfter(today)) invalid(errors, "Informe uma última aplicação válida, até hoje.");
                        else item.put("lastUse", last.toString());
                    } else if (treatment.hasNonNull("lastUse")) {
                        invalid(errors, "Primeiro uso não pode ter uma aplicação anterior informada.");
                    }
                    var every = treatment.path("every");
                    var unit = treatment.path("unit");
                    boolean interval = !every.isMissingNode() && !every.isNull();
                    if (interval || !stage.equals("HYDRATION")) {
                        int maximum = switch (unit.asString("")) { case "DAYS" -> 365; case "WEEKS" -> 52; case "MONTHS" -> 12; default -> 0; };
                        if (!every.isIntegralNumber() || !every.canConvertToInt() || every.asInt() < 1 || every.asInt() > maximum) {
                            invalid(errors, "Informe o intervalo conhecido em dias (1–365), semanas (1–52) ou meses (1–12). Não converta número de lavagens em dias.");
                        } else item.put("every", every.asInt()).put("unit", unit.asString());
                    }
                }
                if (normalized.isEmpty()) result.remove("treatments");
            }
        }
        if (!result.isEmpty()) answers.set("careSchedule", result);
    }

    private static void invalid(Map<String, String> errors, String message) { errors.put("careSchedule", message); }
}
