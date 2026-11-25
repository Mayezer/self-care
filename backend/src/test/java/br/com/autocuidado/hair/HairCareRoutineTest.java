package br.com.autocuidado.hair;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HairCareRoutineTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void composesFineCurlyHairWithoutContradictingWetDetanglingOrDuplicatingSteps() {
        var routine = routine("""
                {"curlPattern":{"kind":"SINGLE","groups":[{"group":"CURLY"}]},"strandThickness":"FINE",
                 "observations":["DRYNESS","FRIZZ"],"chemicalTreatments":["BLEACH"],"heatFrequency":"NEVER"}
                """);
        assertTrue(routine.personalized());
        assertEquals(List.of("cleanse", "condition", "detangle", "dry"), ids(routine));
        assertTrue(routine.steps().get(1).instruction().contains("pontas"));
        assertTrue(routine.steps().get(2).instruction().contains("úmidos"));
        assertFalse(routine.steps().get(2).instruction().contains("secarem"));
        assertEquals(4, routine.steps().stream().map(HairCareRoutine.Step::id).distinct().count());
    }

    @Test
    void handlesStraightFineOilyHairWithoutSuppressingConditioner() {
        var routine = routine("""
                {"curlPattern":{"kind":"SINGLE","groups":[{"group":"STRAIGHT"}]},"strandThickness":"FINE",
                 "rootOiliness":"OFTEN"}
                """);
        assertEquals(List.of("cleanse", "condition", "dry", "detangle"), ids(routine));
        assertTrue(routine.steps().get(3).instruction().contains("secarem um pouco"));
        assertTrue(routine.notes().stream().anyMatch(note -> note.contains("não elimina o condicionamento")));
    }

    @Test
    void identifiesPersonalizationWhenOnlyLengthOrThickStrandsChangeHandling() {
        for (String profile : List.of("{\"strandThickness\":\"THICK\"}", "{\"visualLength\":\"BELOW_SHOULDERS\"}")) {
            var routine = routine(profile);
            assertTrue(routine.steps().stream().anyMatch(step -> step.instruction().contains("pequenas seções")));
            assertTrue(routine.personalized(), profile);
            assertFalse(routine.summary().contains("Preencha Meu cabelo"));
        }
    }

    @Test
    void doesNotApplyCurlyOrStraightTimingToMixedOrUnknownPatterns() {
        for (String profile : List.of("{}", "{\"curlPattern\":{\"kind\":\"UNKNOWN\"}}", """
                {"curlPattern":{"kind":"MULTIPLE","groups":[{"group":"STRAIGHT"},{"group":"CURLY"}]}}
                """)) {
            var routine = routine(profile);
            var detangle = routine.steps().stream().filter(step -> step.id().equals("detangle")).findFirst().orElseThrow();
            assertFalse(detangle.instruction().contains("úmidos"));
            assertFalse(detangle.instruction().contains("secarem"));
        }
    }

    @Test
    void heatIsConditionalAndNeverAnObligationOrInferredFromUnknownAnswers() {
        assertFalse(ids(routine("{\"heatFrequency\":\"UNKNOWN\"}")).contains("heat"));
        assertFalse(ids(routine("{\"heatFrequency\":\"NEVER\",\"heatTools\":[\"DRYER\"]}")).contains("heat"));
        var routine = routine("{\"heatFrequency\":\"ONE_TO_THREE_PER_WEEK\",\"heatTools\":[\"FLAT_IRON\"]}");
        assertEquals("heat", routine.steps().getLast().id());
        assertTrue(routine.steps().getLast().title().startsWith("Se usar"));
    }

    @Test
    void symptomsDoNotBecomeANutrientDiagnosisOrTreatmentFrequency() {
        var routine = routine("""
                {"observations":["DRYNESS","BREAKAGE","FRIZZ"],"chemicalTreatments":["BLEACH"],
                 "lastChemicalTreatmentMonth":"2020-01"}
                """);
        assertEquals(4, routine.steps().size());
        assertFalse(ids(routine).contains("reconstruction"));
        assertFalse(ids(routine).contains("nutrition"));
        assertTrue(routine.notes().stream().anyMatch(note -> note.contains("não comprova falta de queratina")));
        assertTrue(routine.notes().stream().anyMatch(note -> note.contains("não determina a recuperação")));
    }

    private HairCareRoutine routine(String profile) { return HairCareRoutine.from(json.readTree(profile)); }

    private static List<String> ids(HairCareRoutine routine) {
        return routine.steps().stream().map(HairCareRoutine.Step::id).toList();
    }
}
