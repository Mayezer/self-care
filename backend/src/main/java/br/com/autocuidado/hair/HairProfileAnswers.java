package br.com.autocuidado.hair;

import java.time.YearMonth;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

final class HairProfileAnswers {

    private static final Set<String> FIELDS = Set.of("curlPattern", "visualLength", "strandThickness", "rootOiliness",
            "chemicalTreatments", "lastChemicalTreatmentMonth", "heatFrequency", "heatTools", "observations");
    private static final Set<String> EXCLUSIVE = Set.of("NONE", "UNKNOWN");
    private static final Map<String, String> CURL_GROUPS = Map.of("STRAIGHT", "1", "WAVY", "2", "CURLY", "3", "COILY", "4");
    private static final Pattern MONTH = Pattern.compile("[0-9]{4}-(0[1-9]|1[0-2])");

    private HairProfileAnswers() {}

    static ObjectNode parse(JsonNode request, YearMonth currentMonth, Map<String, String> errors) {
        var answers = JsonNodeFactory.instance.objectNode();
        if (!FIELDS.containsAll(request.propertyNames())) {
            errors.put("body", "Envie apenas as respostas do perfil. A conta é identificada pela sessão.");
        }
        parseCurlPattern(request.path("curlPattern"), answers, errors);
        copyChoice(request, answers, errors, "visualLength",
                Set.of("ABOVE_EAR", "BETWEEN_EAR_AND_SHOULDERS", "AT_SHOULDERS", "BELOW_SHOULDERS", "UNKNOWN"));
        copyChoice(request, answers, errors, "strandThickness", Set.of("FINE", "MEDIUM", "THICK", "UNKNOWN"));
        copyChoice(request, answers, errors, "rootOiliness", Set.of("RARELY", "SOMETIMES", "OFTEN", "UNKNOWN"));
        var treatments = copyChoices(request, answers, errors, "chemicalTreatments",
                Set.of("DYE", "BLEACH", "HIGHLIGHTS", "STRAIGHTENING_OR_RELAXING", "OTHER", "NONE", "UNKNOWN"), EXCLUSIVE);
        parseChemicalMonth(request.path("lastChemicalTreatmentMonth"), currentMonth, treatments, answers, errors);
        copyChoice(request, answers, errors, "heatFrequency",
                Set.of("NEVER", "LESS_THAN_WEEKLY", "ONE_TO_THREE_PER_WEEK", "FOUR_OR_MORE_PER_WEEK", "UNKNOWN"));
        copyChoices(request, answers, errors, "heatTools", Set.of("DRYER", "FLAT_IRON", "CURLING_IRON"), Set.of());
        if ("NEVER".equals(answers.path("heatFrequency").asString(""))) {
            answers.remove("heatTools");
        }
        copyChoices(request, answers, errors, "observations",
                Set.of("DRYNESS", "BREAKAGE", "SPLIT_ENDS", "FRIZZ", "NONE", "UNKNOWN"), EXCLUSIVE);
        return answers;
    }

    private static void parseCurlPattern(JsonNode value, ObjectNode answers, Map<String, String> errors) {
        if (absent(value)) {
            return;
        }
        String kind = value.path("kind").asString("");
        var groups = value.path("groups");
        int count = groups.isArray() ? groups.size() : 0;
        if (!value.isObject() || !Set.of("kind", "groups").containsAll(value.propertyNames())
                || !value.path("kind").isString() || (!absent(groups) && !groups.isArray())
                || !switch (kind) {
                    case "SINGLE" -> count == 1;
                    case "MULTIPLE" -> count <= 2;
                    case "UNKNOWN" -> count == 0;
                    default -> false;
                }) {
            errors.put("curlPattern", "Informe um grupo, até dois grupos distintos em mais de um padrão ou não sei.");
            return;
        }
        var pattern = answers.putObject("curlPattern").put("kind", kind);
        if (count == 0) {
            return;
        }
        var selected = new HashSet<String>();
        var normalized = pattern.putArray("groups");
        for (JsonNode group : groups) {
            var name = group.path("group");
            String prefix = CURL_GROUPS.get(name.asString(""));
            if (!group.isObject() || !Set.of("group", "subtype").containsAll(group.propertyNames())
                    || !name.isString() || prefix == null || !selected.add(name.asString())) {
                errors.put("curlPattern", "Escolha grupos de curvatura válidos e distintos.");
                return;
            }
            var option = normalized.addObject().put("group", name.asString());
            var subtype = group.path("subtype");
            if (!absent(subtype)) {
                if (!subtype.isString() || !Set.of(prefix + "A", prefix + "B", prefix + "C").contains(subtype.asString())) {
                    errors.put("curlPattern", "Escolha um subtipo que corresponda ao grupo selecionado.");
                    return;
                }
                option.set("subtype", subtype);
            }
        }
    }

    private static void parseChemicalMonth(JsonNode value, YearMonth currentMonth, Set<String> treatments,
            ObjectNode answers, Map<String, String> errors) {
        if (absent(value)) {
            return;
        }
        String month = value.asString("");
        if (!value.isString() || !MONTH.matcher(month).matches() || month.startsWith("0000")
                || YearMonth.parse(month).isAfter(currentMonth) || treatments.isEmpty()
                || treatments.stream().anyMatch(EXCLUSIVE::contains)) {
            errors.put("lastChemicalTreatmentMonth", "Informe um mês atual ou anterior e selecione um procedimento.");
            return;
        }
        answers.set("lastChemicalTreatmentMonth", value);
    }

    private static void copyChoice(JsonNode request, ObjectNode answers, Map<String, String> errors,
            String field, Set<String> choices) {
        var value = request.path(field);
        if (!absent(value)) {
            if (value.isString() && choices.contains(value.asString())) {
                answers.set(field, value);
            } else {
                errors.put(field, "Escolha uma opção válida.");
            }
        }
    }

    private static Set<String> copyChoices(JsonNode request, ObjectNode answers, Map<String, String> errors,
            String field, Set<String> choices, Set<String> exclusive) {
        var value = request.path(field);
        if (absent(value)) {
            return Set.of();
        }
        var selected = new HashSet<String>();
        if (!value.isArray() || value.size() > choices.size()) {
            errors.put(field, "Escolha opções válidas.");
            return Set.of();
        }
        for (JsonNode item : value) {
            if (!item.isString() || !choices.contains(item.asString()) || !selected.add(item.asString())) {
                errors.put(field, "Escolha opções válidas sem repetir.");
                return Set.of();
            }
        }
        if (selected.size() > 1 && selected.stream().anyMatch(exclusive::contains)) {
            errors.put(field, "Selecione essa opção sozinha.");
            return Set.of();
        }
        if (!selected.isEmpty()) {
            answers.set(field, value);
        }
        return selected;
    }

    private static boolean absent(JsonNode value) {
        return value.isNull() || value.isMissingNode();
    }
}
