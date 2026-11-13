package br.com.autocuidado.hair;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.autocuidado.hair.HairProductCatalog.Category;
import br.com.autocuidado.hair.HairProductRecommendations.Card;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static br.com.autocuidado.hair.HairProductCatalog.Category.CONDITIONER;
import static br.com.autocuidado.hair.HairProductCatalog.Category.HEAT_PROTECTANT;
import static br.com.autocuidado.hair.HairProductCatalog.Category.LEAVE_IN;
import static br.com.autocuidado.hair.HairProductCatalog.Category.MASK;
import static br.com.autocuidado.hair.HairProductCatalog.Category.SHAMPOO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HairProductRecommendationsTest {
    private static final LocalDate REVIEW_DATE = LocalDate.of(2026, 11, 11);
    private static final String SHAMPOO_ID = "elseve-pure-hialuronico-shampoo-micelar-300";
    private static final String LEAVE_IN_ID = "elseve-cachos-longos-creme-3-em-1-500";
    private static final String HEAT_ID = "amend-blindagem-essencial-fluido-180";
    private final JsonMapper mapper = new JsonMapper();
    private final HairProductRecommendations recommendations = new HairProductRecommendations(mapper);

    @Test
    void separatesOilyRootsFromDryFineEndsAndKeepsTheMaskOptional() throws IOException {
        var cards = forScenario("oily-dry-fine");
        assertEquals(List.of(SHAMPOO, CONDITIONER, MASK), categories(cards));
        assertEquals(SHAMPOO_ID, cards.get(0).variant().id());
        assertEquals("Raiz e couro cabeludo", cards.get(0).area());
        assertTrue(cards.get(0).reason().contains("oleosa com frequência"));
        assertEquals("Pontas", cards.get(1).area());
        assertEquals("Condicionador leve de enxágue", cards.get(1).title());
        assertTrue(cards.get(1).reason().contains("fios finos"));
        assertTrue(cards.get(1).reason().contains("ressecamento"));
        assertNull(cards.get(1).variant());
        assertTrue(cards.get(2).optional());
    }

    @Test
    void keepsFineMixedHairInLightCategoriesWhenTheCatalogLacksAMatch() throws IOException {
        var cards = forScenario("fine-mixed");
        assertEquals(List.of(CONDITIONER, LEAVE_IN), categories(cards));
        assertTrue(cards.get(0).title().contains("leve"));
        assertTrue(cards.get(1).title().contains("leve"));
        assertTrue(cards.get(1).reason().contains("frizz"));
        assertTrue(cards.stream().allMatch(card -> card.variant() == null));
    }

    @Test
    void recommendsTheMixedPatternVariantOnceAndExplainsItsScope() throws IOException {
        var cards = forScenario("colored-mixed");
        assertEquals(List.of(SHAMPOO, CONDITIONER, LEAVE_IN), categories(cards));
        assertTrue(cards.get(1).title().contains("tingidos"));
        assertTrue(cards.get(1).reason().contains("tintura"));
        assertNull(cards.get(1).variant());
        assertEquals(LEAVE_IN_ID, cards.get(2).variant().id());
        assertTrue(cards.get(2).reason().contains("ondulados e cacheados"));
        assertTrue(cards.get(2).reason().contains("se aplica aos fios cacheados"));
        assertEquals(cards, recommend("""
                {"curlPattern":{"kind":"MULTIPLE","groups":[{"group":"CURLY"},{"group":"WAVY"}]},
                 "chemicalTreatments":["DYE"],"rootOiliness":"OFTEN"}
                """));
    }

    @Test
    void selectsThePublishedVariantThatCoversBothGroupsBeforeOneGroup() throws IOException {
        var single = copyCatalogItem(LEAVE_IN_ID);
        var shared = single.deepCopy();
        shared.put("id", "shared-variant");
        shared.putArray("tags").add("CURLY").add("WAVY");
        var catalog = mapper.createObjectNode();
        catalog.putArray("products").add(single).add(shared);
        var selected = new HairProductRecommendations(HairProductCatalog.read(catalog))
                .recommend(scenario("colored-mixed"), REVIEW_DATE);
        assertEquals("shared-variant", find(selected, LEAVE_IN).variant().id());
        assertFalse(find(selected, LEAVE_IN).reason().contains("se aplica aos fios"));
    }

    @Test
    void requiresBothFineHairAndColorIndicationsWhenBothWereReported() throws IOException {
        var colorOnly = copyCatalogItem("elseve-cachos-longos-condicionador-200");
        colorOnly.putArray("tags").add("COLOR_TREATED");
        var fineOnly = colorOnly.deepCopy();
        fineOnly.put("id", "fine-only").putArray("tags").add("FINE");
        var catalog = mapper.createObjectNode();
        var products = catalog.putArray("products").add(colorOnly).add(fineOnly);
        var answers = mapper.readTree("""
                {"strandThickness":"FINE","chemicalTreatments":["DYE"]}
                """);
        var cards = new HairProductRecommendations(HairProductCatalog.read(catalog)).recommend(answers, REVIEW_DATE);
        assertNull(find(cards, CONDITIONER).variant());
        var both = colorOnly.deepCopy();
        both.put("id", "fine-and-color").putArray("tags").add("COLOR_TREATED").add("FINE");
        products.add(both);
        cards = new HairProductRecommendations(HairProductCatalog.read(catalog)).recommend(answers, REVIEW_DATE);
        assertEquals("fine-and-color", find(cards, CONDITIONER).variant().id());
        assertTrue(find(cards, CONDITIONER).reason().contains("tintura e fios finos"));
    }

    @Test
    void keepsCoilySuggestionsGenericAndSuppressesHeatWhenMarkedNever() throws IOException {
        var cards = forScenario("coily-no-heat");
        assertEquals(List.of(CONDITIONER, LEAVE_IN, MASK), categories(cards));
        assertTrue(cards.stream().allMatch(card -> card.variant() == null));
        assertTrue(recommend("""
                {"heatFrequency":"NEVER","heatTools":["DRYER"]}
                """).isEmpty());
    }

    @Test
    void retainsBleachedHairConditioningAndDoesNotAssumeADryerForAFlatIron() throws IOException {
        var cards = forScenario("bleach-flat-iron");
        assertEquals(List.of(CONDITIONER, HEAT_PROTECTANT), categories(cards));
        assertTrue(cards.get(0).reason().contains("quebra"));
        assertTrue(cards.get(1).reason().contains("chapinha"));
        assertNull(cards.get(1).variant());
    }

    @Test
    void usesPositiveFrequencyWithoutCurvatureAndIdentifiesPartialToolCoverage() throws IOException {
        var frequency = forScenario("frequency-only");
        assertEquals(List.of(HEAT_PROTECTANT), categories(frequency));
        assertNull(frequency.getFirst().variant());
        var partial = forScenario("mixed-tools");
        assertEquals(HEAT_ID, partial.getFirst().variant().id());
        assertEquals(List.of("DRYER"), partial.getFirst().variant().coveredHeatTools());
        assertTrue(partial.getFirst().reason().contains("Para modelador"));
        assertTrue(partial.getFirst().instructions().contains("calor baixo ou médio"));
        var full = recommend("""
                {"heatTools":["FLAT_IRON","DRYER"]}
                """);
        assertEquals(List.of("DRYER", "FLAT_IRON"), full.getFirst().variant().coveredHeatTools());
    }

    @Test
    void ordersMatchedProductsAndPreservesInstructionsAndOfficialSources() throws IOException {
        var cards = forScenario("curly-dry");
        assertEquals(List.of(CONDITIONER, HEAT_PROTECTANT, LEAVE_IN, MASK), categories(cards));
        for (Card card : cards) {
            var variant = card.variant();
            assertNotNull(variant);
            assertTrue(variant.manufacturerUrl().startsWith("https://"));
            assertFalse(variant.warnings().isEmpty());
            assertFalse(card.reason().isBlank());
        }
        assertEquals("Cabelo seco ou úmido", find(cards, LEAVE_IN).variant().hairCondition());
        assertEquals("LEAVE_IN", find(cards, LEAVE_IN).variant().rinse());
        assertEquals("RINSE_OFF", find(cards, MASK).variant().rinse());
    }

    @Test
    void ignoresEmptyUnknownAndDescriptiveAnswersAndOffersRegularWashingWhenRelevant() throws IOException {
        assertTrue(forScenario("insufficient").isEmpty());
        assertTrue(forScenario("empty").isEmpty());
        assertTrue(recommend("""
                {"curlPattern":{"kind":"MULTIPLE"},"heatFrequency":"UNKNOWN","chemicalTreatments":["UNKNOWN"]}
                """).isEmpty());
        var rare = recommend("""
                {"rootOiliness":"RARELY"}
                """);
        assertEquals(List.of(SHAMPOO), categories(rare));
        assertEquals("Xampu de uso regular", rare.getFirst().title());
        assertNull(rare.getFirst().variant());
    }

    @Test
    void fallsBackForOutOfDateOrIneligibleEditorialEntries() throws IOException {
        assertNull(find(recommendations.recommend(scenario("oily-dry-fine"), LocalDate.of(2035, 5, 25)), SHAMPOO).variant());
        assertNull(find(recommendations.recommend(scenario("oily-dry-fine"), REVIEW_DATE.minusDays(1)), SHAMPOO).variant());

        for (var entry : Map.of("review.status", "WITHDRAWN", "registry.status", "INACTIVE",
                "label.audienceRestriction", "+12 anos", "label.compositionSource", "http://example.invalid").entrySet()) {
            var item = copyCatalogItem(SHAMPOO_ID);
            var path = entry.getKey().split("\\.");
            ((ObjectNode) item.path(path[0])).put(path[1], entry.getValue());
            var catalog = mapper.createObjectNode();
            catalog.putArray("products").add(item);
            var cards = new HairProductRecommendations(HairProductCatalog.read(catalog)).recommend(scenario("oily-dry-fine"), REVIEW_DATE);
            assertNull(find(cards, SHAMPOO).variant(), entry.getKey());
        }
    }

    private List<Card> forScenario(String id) throws IOException {
        return recommendations.recommend(scenario(id), REVIEW_DATE);
    }

    private JsonNode scenario(String id) throws IOException {
        var input = HairProductPreview.scenarios(mapper).get(id).answers();
        var errors = new LinkedHashMap<String, String>();
        var answers = HairProfileAnswers.parse(input, YearMonth.from(REVIEW_DATE), errors);
        assertTrue(errors.isEmpty(), errors.toString());
        return answers;
    }

    private List<Card> recommend(String input) {
        return recommendations.recommend(mapper.readTree(input), REVIEW_DATE);
    }

    private static List<Category> categories(List<Card> cards) {
        return cards.stream().map(Card::category).toList();
    }

    private static Card find(List<Card> cards, Category category) {
        return cards.stream().filter(card -> card.category() == category).findFirst().orElseThrow();
    }

    private ObjectNode copyCatalogItem(String id) throws IOException {
        try (var file = getClass().getResourceAsStream("/hair-products/catalog.json")) {
            for (JsonNode item : mapper.readTree(file).path("products")) {
                if (item.path("id").asString().equals(id)) return ((ObjectNode) item).deepCopy();
            }
        }
        throw new IllegalArgumentException("Unknown catalog fixture: " + id);
    }
}
