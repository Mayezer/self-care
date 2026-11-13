package br.com.autocuidado.hair;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class HairProductPreview {
    private HairProductPreview() {}

    public static void main(String[] args) throws IOException {
        LocalDate date = LocalDate.parse(args[0]);
        var mapper = new JsonMapper();
        var recommendations = new HairProductRecommendations(mapper);
        var output = mapper.createArrayNode();
        for (Scenario scenario : scenarios(mapper).values()) {
            output.addObject().put("id", scenario.id()).put("title", scenario.title()).put("description", scenario.description())
                    .set("cards", mapper.valueToTree(recommendations.recommend(scenario.answers(), date)));
        }
        System.out.println(mapper.writeValueAsString(output));
    }

    static LinkedHashMap<String, Scenario> scenarios(JsonMapper mapper) throws IOException {
        try (var file = HairProductPreview.class.getResourceAsStream("/hair-products/scenarios.json")) {
            if (file == null) throw new IllegalStateException("Missing hair product scenarios");
            var scenarios = new LinkedHashMap<String, Scenario>();
            for (JsonNode entry : mapper.readTree(file)) {
                String id = entry.path("id").asString();
                scenarios.put(id, new Scenario(id, entry.path("title").asString(), entry.path("description").asString(), entry.path("answers")));
            }
            return scenarios;
        }
    }

    record Scenario(String id, String title, String description, JsonNode answers) {}
}
