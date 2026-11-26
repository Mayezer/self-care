package br.com.autocuidado.hair;

import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record HairSuggestionRequest(boolean useProfile, boolean autoWeekdays, String sourceRevision, boolean enhanced) {
    public static HairSuggestionRequest parse(JsonNode value, Map<String, String> errors) {
        if (value.isMissingNode()) return null;
        if (!value.isObject() || !Set.of("useProfile", "autoWeekdays", "sourceRevision").containsAll(value.propertyNames())
                || (value.has("useProfile") && !value.path("useProfile").isBoolean())
                || (value.has("autoWeekdays") && !value.path("autoWeekdays").isBoolean())
                || (value.has("sourceRevision") && (!value.path("sourceRevision").isString()
                    || !value.path("sourceRevision").asString().matches("[a-f0-9]{64}")))) {
            errors.put("suggestion", "Confira as opções da sugestão e gere uma nova prévia.");
            return null;
        }
        return new HairSuggestionRequest(value.path("useProfile").asBoolean(false), value.path("autoWeekdays").asBoolean(false),
                value.has("sourceRevision") ? value.path("sourceRevision").asString() : null,
                value.has("autoWeekdays") || value.has("sourceRevision"));
    }
}
