package br.com.autocuidado.hair;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class HairProductCatalog {
    private static final String RESOURCE = "/hair-products/catalog.json";
    private static final Set<String> HEAT_TOOLS = Set.of("DRYER", "FLAT_IRON", "CURLING_IRON");

    private HairProductCatalog() {}

    static List<Item> load(JsonMapper mapper) {
        try (var resource = HairProductCatalog.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) throw new IllegalStateException("Missing hair product catalog");
            return read(mapper.readTree(resource));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read hair product catalog", exception);
        }
    }

    static List<Item> read(JsonNode catalog) {
        var products = catalog.path("products");
        if (!products.isArray()) throw new IllegalArgumentException("Missing products array");

        var items = new ArrayList<Item>();
        var ids = new HashSet<String>();
        for (JsonNode product : products) {
            String id = product.path("id").asString("");
            if (id.isBlank() || !ids.add(id)) throw new IllegalArgumentException("Invalid or duplicate product ID: " + id);

            Item available = readPublished(id, product);
            if (available != null) items.add(available);
        }
        return List.copyOf(items);
    }

    private static Item readPublished(String id, JsonNode product) {
        var review = product.path("review");
        var registry = product.path("registry");
        var label = product.path("label");
        if (!"PUBLISHED".equals(review.path("status").asString(""))
                || !"ACTIVE".equals(registry.path("status").asString(""))
                || label.hasNonNull("audienceRestriction")) return null;

        String brand = product.path("brand").asString("");
        String name = product.path("name").asString("");
        String presentation = product.path("presentation").asString("");
        String manufacturer = product.path("manufacturerUrl").asString("");
        String area = label.path("area").asString("");
        String condition = label.path("hairCondition").asString("");
        String instructions = label.path("instructions").asString("");
        String rinse = label.path("rinse").asString("");
        List<String> warnings = strings(label.path("warnings"));
        if (List.of(brand, name, presentation, area, condition, instructions).stream().anyMatch(String::isBlank)
                || warnings.isEmpty() || !Set.of("RINSE_OFF", "LEAVE_IN").contains(rinse)
                || !isHttps(manufacturer) || !isHttps(label.path("source").asString(""))
                || !isHttps(label.path("compositionSource").asString(""))
                || review.path("reviewer").asString("").isBlank()
                || !registry.path("process").asString("").matches("25351\\d{12}")) return null;

        try {
            Category category = Category.valueOf(product.path("category").asString(""));
            Set<String> tags = Set.copyOf(strings(product.path("tags")));
            Set<String> tools = Set.copyOf(strings(label.path("heatTools")));
            Set<String> requiredTools = Set.copyOf(strings(label.path("requiredHeatTools")));
            if (tags.isEmpty() || !HEAT_TOOLS.containsAll(tools) || !tools.containsAll(requiredTools)
                    || category == Category.HEAT_PROTECTANT && tools.isEmpty()) return null;

            LocalDate reviewed = LocalDate.parse(review.path("reviewedOn").asString(""));
            LocalDate checked = LocalDate.parse(registry.path("checkedOn").asString(""));
            LocalDate validUntil = LocalDate.parse(registry.path("validUntil").asString(""));
            if (checked.isAfter(reviewed) || reviewed.isAfter(validUntil)) return null;

            return new Item(id, category, brand, name, presentation, manufacturer, area, condition, instructions, rinse,
                    warnings, tags, tools, requiredTools, reviewed, validUntil);
        } catch (DateTimeParseException | IllegalArgumentException exception) {
            // A malformed entry cannot become a recommendation.
            return null;
        }
    }

    private static boolean isHttps(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static List<String> strings(JsonNode array) {
        if (!array.isArray()) return List.of();
        var result = new ArrayList<String>();
        for (JsonNode value : array) {
            if (value.isString() && !value.asString().isBlank()) result.add(value.asString());
        }
        return List.copyOf(result);
    }

    enum Category { SHAMPOO, CONDITIONER, HEAT_PROTECTANT, LEAVE_IN, MASK }

    record Item(String id, Category category, String brand, String name, String presentation, String manufacturerUrl,
                String area, String hairCondition, String instructions, String rinse, List<String> warnings,
                Set<String> tags, Set<String> tools, Set<String> requiredTools, LocalDate reviewedOn, LocalDate validUntil) {
        boolean availableOn(LocalDate date) {
            return !date.isBefore(reviewedOn) && !date.isAfter(validUntil);
        }
    }
}
