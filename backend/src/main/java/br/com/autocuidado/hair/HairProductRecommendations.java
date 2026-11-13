package br.com.autocuidado.hair;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import br.com.autocuidado.hair.HairProductCatalog.Category;
import br.com.autocuidado.hair.HairProductCatalog.Item;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class HairProductRecommendations {
    private static final List<String> GROUPS = List.of("STRAIGHT", "WAVY", "CURLY", "COILY");
    private static final List<String> TOOLS = List.of("DRYER", "FLAT_IRON", "CURLING_IRON");
    private static final Set<String> COLOR_TAGS = Set.of("COLOR_TREATED", "HIGHLIGHTS");
    private static final Set<String> CHEMICAL = Set.of("DYE", "BLEACH", "HIGHLIGHTS", "STRAIGHTENING_OR_RELAXING", "OTHER");
    private static final Set<String> OBSERVATIONS = Set.of("DRYNESS", "BREAKAGE", "SPLIT_ENDS", "FRIZZ");
    private static final Set<String> HEAT_FREQUENCIES = Set.of("LESS_THAN_WEEKLY", "ONE_TO_THREE_PER_WEEK", "FOUR_OR_MORE_PER_WEEK");
    private static final String LENGTH_AND_ENDS = "Comprimento e pontas";
    private static final Comparator<Item> STABLE_TIE_BREAK = Comparator.comparing(Item::id, Comparator.reverseOrder());

    private final List<Item> catalog;

    HairProductRecommendations(JsonMapper mapper) {
        this(HairProductCatalog.load(mapper));
    }

    HairProductRecommendations(List<Item> catalog) {
        this.catalog = List.copyOf(catalog);
    }

    List<Card> recommend(JsonNode savedAnswers, LocalDate date) {
        var profile = Profile.read(savedAnswers);
        var available = catalog.stream().filter(item -> item.availableOn(date)).toList();
        var cards = new ArrayList<Card>();
        if (profile.rootOiliness().equals("OFTEN")) {
            cards.add(shampoo(available, profile, true));
        } else if (Set.of("RARELY", "SOMETIMES").contains(profile.rootOiliness())) {
            cards.add(shampoo(available, profile, false));
        }
        if (profile.wantsConditioner()) cards.add(conditioner(available, profile));
        if (profile.usesHeat()) cards.add(heatProtection(available, profile));
        if (profile.wantsLeaveIn()) cards.add(leaveIn(available, profile));
        if (profile.dry()) cards.add(mask(available, profile));
        return List.copyOf(cards);
    }

    private static Card shampoo(List<Item> available, Profile profile, boolean oily) {
        Item item = available.stream()
                .filter(candidate -> candidate.category() == Category.SHAMPOO && coversGroup(candidate, profile.groups()))
                .filter(candidate -> oily ? candidate.tags().contains("OILY_ROOTS")
                        : candidate.tags().contains("REGULAR_CLEANSE") || profile.dry() && candidate.tags().contains("DRYNESS"))
                .max(Comparator.comparingInt((Item candidate) -> coveredGroupCount(candidate, profile.groups()))
                        .thenComparingInt(candidate -> profile.dry() && candidate.tags().contains("DRYNESS") ? 1 : 0)
                        .thenComparing(STABLE_TIE_BREAK)).orElse(null);
        String title = oily ? "Xampu para raiz oleosa" : "Xampu de uso regular";
        String reason = switch (profile.rootOiliness()) {
            case "OFTEN" -> "Você informou que a raiz fica oleosa com frequência.";
            case "SOMETIMES" -> "Você informou que a raiz fica oleosa às vezes.";
            default -> "Você informou que a raiz raramente fica oleosa.";
        };
        return card(Category.SHAMPOO, title, groupScope(reason, item, profile), "Raiz e couro cabeludo",
                "Aplique no couro cabeludo e enxágue conforme o rótulo. Ajuste a lavagem à sua necessidade.", false, item, List.of());
    }

    private static Card conditioner(List<Item> available, Profile profile) {
        var color = profile.colorTags();
        Item item = bestForHair(available, Category.CONDITIONER, profile,
                candidate -> color.isEmpty() ? !matchesAny(candidate.tags(), COLOR_TAGS) : candidate.tags().containsAll(color));
        String title = "Condicionador de enxágue";
        String reason = conditionerReason(profile, color);
        String area = profile.fine() || profile.groups().equals(Set.of("STRAIGHT")) ? "Pontas" : LENGTH_AND_ENDS;
        String instructions = "Após lavar, aplique nos fios e enxágue conforme o rótulo.";
        if (!color.isEmpty()) {
            String purpose = color.size() == 2 ? "tingidos e com mechas"
                    : color.contains("COLOR_TREATED") ? "tingidos" : "com luzes ou mechas";
            title = (profile.fine() ? "Condicionador leve para cabelos " : "Condicionador para cabelos ") + purpose;
            instructions = "Procure no rótulo uma indicação compatível com o procedimento informado. " + instructions;
        } else if (profile.fine()) {
            title = "Condicionador leve de enxágue";
        } else if (profile.dry()) {
            title = "Condicionador de enxágue para fios ressecados";
        }
        return card(Category.CONDITIONER, title, groupScope(reason, item, profile), area, instructions, false, item, List.of());
    }

    private static String conditionerReason(Profile profile, Set<String> color) {
        if (!color.isEmpty()) {
            String treatment = color.size() == 2 ? "tintura e luzes ou mechas"
                    : color.contains("COLOR_TREATED") ? "tintura" : "luzes ou mechas";
            return "Você informou " + treatment + (profile.fine() ? " e fios finos." : ".");
        }
        if (profile.fine() && profile.dry()) return "Você informou fios finos e percebeu ressecamento.";
        if (profile.fine()) return "Você informou que seus fios são finos.";
        if (profile.dry()) return "Você percebeu ressecamento nos fios.";
        if (profile.observations().contains("BREAKAGE")) return "Você percebeu quebra nos fios.";
        if (profile.observations().contains("SPLIT_ENDS")) return "Você percebeu pontas duplas.";
        if (profile.treatments().contains("BLEACH")) return "Você informou descoloração.";
        if (!profile.treatments().isEmpty()) return "Você informou um procedimento químico.";
        return curvatureReason(profile.groups());
    }

    private static Card heatProtection(List<Item> available, Profile profile) {
        var tools = profile.tools();
        Item item = available.stream().filter(candidate -> candidate.category() == Category.HEAT_PROTECTANT)
                .filter(candidate -> coversGroup(candidate, profile.groups()))
                .filter(candidate -> matchesAny(tools, candidate.tools()) && tools.containsAll(candidate.requiredTools()))
                .max(Comparator.comparingInt((Item candidate) -> intersectionCount(tools, candidate.tools()))
                        .thenComparingInt(candidate -> coveredGroupCount(candidate, profile.groups()))
                        .thenComparing(STABLE_TIE_BREAK)).orElse(null);
        String reason = tools.isEmpty() ? "Você informou uso de calor."
                : "Você informou uso de " + toolNames(tools) + ".";
        var covered = item == null ? List.<String>of() : TOOLS.stream().filter(tools::contains).filter(item.tools()::contains).toList();
        if (item != null && covered.size() < tools.size()) {
            var uncovered = TOOLS.stream().filter(tools::contains).filter(tool -> !item.tools().contains(tool)).toList();
            reason += " Esta variante cobre " + toolNames(covered) + ". Para " + toolNames(uncovered)
                    + ", confira uma opção que indique essa ferramenta no rótulo.";
        }
        String instructions = "Confira no rótulo a ferramenta e a condição dos fios antes de aplicar. Use calor baixo ou médio.";
        return card(Category.HEAT_PROTECTANT, "Protetor térmico", groupScope(reason, item, profile), "Fios, conforme o rótulo",
                instructions, false, item, covered);
    }

    private static Card leaveIn(List<Item> available, Profile profile) {
        boolean frizzOnly = !profile.hasWavesOrCurls();
        Item item = bestForHair(available, Category.LEAVE_IN, profile,
                candidate -> !frizzOnly || candidate.tags().contains("FRIZZ"));
        String reason = profile.observations().contains("FRIZZ") ? "Você percebeu frizz nos fios." : curvatureReason(profile.groups());
        String title = profile.fine() ? "Finalizador leve sem enxágue" : "Finalizador sem enxágue";
        return card(Category.LEAVE_IN, title, groupScope(reason, item, profile), LENGTH_AND_ENDS,
                "Procure um finalizador indicado para seus fios. Aplique conforme o rótulo e confira a condição de aplicação.",
                true, item, List.of());
    }

    private static Card mask(List<Item> available, Profile profile) {
        Item item = bestForHair(available, Category.MASK, profile, candidate -> candidate.tags().contains("DRYNESS"));
        return card(Category.MASK, profile.fine() ? "Máscara leve de enxágue" : "Máscara de enxágue para fios ressecados",
                groupScope("Você percebeu ressecamento nos fios.", item, profile), LENGTH_AND_ENDS,
                "Se quiser complementar o condicionamento, procure uma máscara compatível. Respeite o tempo de ação e enxágue.",
                true, item, List.of());
    }

    private static Item bestForHair(List<Item> available, Category category, Profile profile, Predicate<Item> fitsNeed) {
        return available.stream().filter(item -> item.category() == category && coversGroup(item, profile.groups()))
                .filter(item -> !profile.fine() || item.tags().contains("FINE"))
                .filter(fitsNeed)
                .max(Comparator.comparingInt((Item item) -> coveredGroupCount(item, profile.groups()))
                        .thenComparingInt(item -> intersectionCount(item.tags(), profile.observations()))
                        .thenComparing(STABLE_TIE_BREAK)).orElse(null);
    }

    private static boolean coversGroup(Item item, Set<String> groups) {
        return !matchesAny(item.tags(), GROUPS) || matchesAny(item.tags(), groups);
    }

    private static int coveredGroupCount(Item item, Set<String> groups) {
        return matchesAny(item.tags(), GROUPS) ? intersectionCount(item.tags(), groups) : groups.size();
    }

    private static String groupScope(String reason, Item item, Profile profile) {
        if (item == null || coveredGroupCount(item, profile.groups()) == profile.groups().size()) return reason;
        var covered = GROUPS.stream().filter(profile.groups()::contains).filter(item.tags()::contains).map(HairProductRecommendations::groupName).toList();
        return reason + " A indicação desta variante se aplica aos fios " + join(covered) + ".";
    }

    private static String curvatureReason(Set<String> groups) {
        return "Você informou fios " + join(GROUPS.stream().filter(groups::contains).map(HairProductRecommendations::groupName).toList()) + ".";
    }

    private static String groupName(String code) {
        return switch (code) {
            case "STRAIGHT" -> "lisos";
            case "WAVY" -> "ondulados";
            case "CURLY" -> "cacheados";
            case "COILY" -> "crespos";
            default -> throw new IllegalArgumentException("Unknown curl group");
        };
    }

    private static String toolNames(Collection<String> tools) {
        var names = new ArrayList<String>();
        for (String code : TOOLS) {
            if (!tools.contains(code)) continue;
            names.add(switch (code) {
                case "DRYER" -> "secador";
                case "FLAT_IRON" -> "chapinha";
                default -> "modelador";
            });
        }
        return join(names);
    }

    private static String join(List<String> names) {
        if (names.size() < 2) return String.join("", names);
        return String.join(", ", names.subList(0, names.size() - 1)) + " e " + names.getLast();
    }

    private static boolean matchesAny(Set<String> left, Iterable<String> right) {
        for (String value : right) {
            if (left.contains(value)) return true;
        }
        return false;
    }

    private static int intersectionCount(Set<String> left, Set<String> right) {
        return (int) right.stream().filter(left::contains).count();
    }

    private static Card card(Category category, String title, String reason, String area, String instructions,
                             boolean optional, Item item, List<String> coveredHeatTools) {
        if (item == null) return new Card(category, title, reason, area, instructions, optional, null);
        var variant = new Variant(item.id(), item.brand(), item.name(), item.presentation(), item.hairCondition(), item.rinse(),
                item.warnings(), item.manufacturerUrl(), coveredHeatTools);
        String usage = category == Category.HEAT_PROTECTANT ? item.instructions() + " Use calor baixo ou médio." : item.instructions();
        return new Card(category, title, reason, item.area(), usage, optional, variant);
    }

    record Card(Category category, String title, String reason, String area, String instructions, boolean optional,
                Variant variant) {}

    record Variant(String id, String brand, String name, String presentation, String hairCondition, String rinse,
                   List<String> warnings, String manufacturerUrl, List<String> coveredHeatTools) {}

    private record Profile(String rootOiliness, boolean fine, Set<String> groups, Set<String> treatments,
                           String heatFrequency, Set<String> tools, Set<String> observations) {
        static Profile read(JsonNode answers) {
            var curl = answers.path("curlPattern").path("groups");
            var groups = new ArrayList<String>();
            if (curl.isArray()) {
                for (JsonNode group : curl) groups.add(group.path("group").asString(""));
            }
            return new Profile(answers.path("rootOiliness").asString(""), "FINE".equals(answers.path("strandThickness").asString("")),
                    known(groups, GROUPS), known(HairProductCatalog.strings(answers.path("chemicalTreatments")), CHEMICAL),
                    answers.path("heatFrequency").asString(""), known(HairProductCatalog.strings(answers.path("heatTools")), TOOLS),
                    known(HairProductCatalog.strings(answers.path("observations")), OBSERVATIONS));
        }

        boolean dry() { return observations.contains("DRYNESS"); }

        boolean wantsConditioner() {
            return fine || !groups.isEmpty() || !treatments.isEmpty() || dry()
                    || observations.contains("BREAKAGE") || observations.contains("SPLIT_ENDS");
        }

        boolean wantsLeaveIn() {
            return observations.contains("FRIZZ") || hasWavesOrCurls();
        }

        boolean hasWavesOrCurls() { return groups.stream().anyMatch(group -> !group.equals("STRAIGHT")); }

        boolean usesHeat() {
            return !"NEVER".equals(heatFrequency) && (!tools.isEmpty() || HEAT_FREQUENCIES.contains(heatFrequency));
        }

        Set<String> colorTags() {
            if (treatments.contains("DYE") && treatments.contains("HIGHLIGHTS")) return COLOR_TAGS;
            if (treatments.contains("DYE")) return Set.of("COLOR_TREATED");
            return treatments.contains("HIGHLIGHTS") ? Set.of("HIGHLIGHTS") : Set.of();
        }

        private static Set<String> known(List<String> values, Collection<String> supported) {
            return Set.copyOf(values.stream().filter(supported::contains).toList());
        }
    }
}
