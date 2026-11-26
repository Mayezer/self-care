package br.com.autocuidado.hair;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class HairScheduleSuggestionsTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final LocalDate start = LocalDate.of(2026, 11, 26);

    @Test void schedulesActualStagesOnBalancedDaysAndDoesNotInferIntervalsFromCurlOrFrizz() {
        var profile = json.readTree("""
                {"observations":["DRYNESS"],"careSchedule":{"washesPerWeek":3,"scalpSymptoms":"NO","treatments":{
                  "HYDRATION":{"status":"FIRST_USE"},
                  "NUTRITION":{"status":"FIRST_USE","every":2,"unit":"WEEKS"},
                  "RECONSTRUCTION":{"status":"FIRST_USE","every":1,"unit":"MONTHS"}}}}
                """);
        assertEquals(Set.of(DayOfWeek.THURSDAY, DayOfWeek.SATURDAY, DayOfWeek.MONDAY), HairScheduleSuggestions.weekdays(profile,start));
        var result=HairScheduleSuggestions.build(profile, dates(56), Map.of(), List.of(), "fixture");
        assertTrue(result.sessions().stream().anyMatch(s->s.stage()==HairStage.HYDRATION));
        assertTrue(result.sessions().stream().anyMatch(s->s.stage()==HairStage.NUTRITION));
        assertTrue(result.sessions().stream().anyMatch(s->s.stage()==HairStage.RECONSTRUCTION));
        for (HairStage stage:List.of(HairStage.HYDRATION,HairStage.NUTRITION)) {
            var applied=result.sessions().stream().filter(s->s.stage()==stage).map(HairScheduleSuggestions.Session::date).toList();
            for(int i=1;i<applied.size();i++) assertFalse(applied.get(i).isBefore(applied.get(i-1).plusDays(stage==HairStage.HYDRATION?7:14)));
        }
        var unknown=json.readTree("{\"observations\":[\"FRIZZ\",\"BREAKAGE\"],\"chemicalTreatments\":[\"BLEACH\"]}");
        assertTrue(HairScheduleSuggestions.build(unknown,dates(28),Map.of(),List.of(),"fixture").sessions().stream().allMatch(s->s.stage()==HairStage.WASH_ONLY));
        assertThrows(HairScheduleSuggestions.MissingDetailsException.class,()->HairScheduleSuggestions.weekdays(unknown,start));
    }

    @Test void usesRealCompletionEvenAfterFirstUseAndRespectsReservationsOnBothSides() {
        var p=json.readTree("""
                {"careSchedule":{"scalpSymptoms":"NO","treatments":{
                "RECONSTRUCTION":{"status":"FIRST_USE","every":1,"unit":"MONTHS"}}}}
                """);
        var last=LocalDate.of(2026,10,31);
        var dates=List.of(LocalDate.of(2026,11,29),LocalDate.of(2026,11,30),LocalDate.of(2026,12,15),LocalDate.of(2026,12,31));
        var r=HairScheduleSuggestions.build(p,dates,Map.of(HairStage.RECONSTRUCTION,last),
                List.of(new HairScheduleSuggestions.Reservation(HairStage.RECONSTRUCTION,LocalDate.of(2026,12,20))),"fixture");
        assertTrue(r.sessions().stream().allMatch(s->s.stage()==HairStage.WASH_ONLY));
        var r2=HairScheduleSuggestions.build(p,dates,Map.of(HairStage.RECONSTRUCTION,last),List.of(),"fixture");
        assertEquals(HairStage.WASH_ONLY,r2.sessions().getFirst().stage());
        assertEquals(HairStage.RECONSTRUCTION,r2.sessions().get(1).stage());
        assertEquals(HairStage.RECONSTRUCTION,r2.sessions().getLast().stage());
    }

    @Test void neverUsesMissingHistoryBadToleranceOrUnknownScalpAsPermission() {
        for (String scalp:List.of("YES","UNKNOWN","NO")) {
            var p=json.readTree("""
                    {"observations":["DRYNESS"],"careSchedule":{"scalpSymptoms":"%s","treatments":{
                    "HYDRATION":{"status":"UNKNOWN"},"NUTRITION":{"status":"AVOID"},"RECONSTRUCTION":{"status":"UNKNOWN"}}}}
                    """.formatted(scalp));
            assertTrue(HairScheduleSuggestions.build(p,dates(28),Map.of(),List.of(),"fixture").sessions().stream().allMatch(s->s.stage()==HairStage.WASH_ONLY));
        }
    }

    @Test void oldRoutineJsonKeepsItsContractWithoutInventingASchedule() {
        var old=HairCareRoutine.from(json.createObjectNode());
        String text=json.writeValueAsString(old);
        assertFalse(text.contains("schedule"));
        assertNull(json.readValue(text,HairCareRoutine.class).schedule());
        var schedule=HairScheduleSuggestions.build(json.createObjectNode(),dates(1),Map.of(),List.of(),"fixture");
        var enhanced=old.withSchedule(schedule);
        assertEquals(enhanced.summary(),enhanced.withSchedule(schedule).summary());
    }

    @Test void coversEverySupportedCycleAndFrequencyWithoutShrinkingKnownIntervals() {
        var profile=json.readTree("""
                {"careSchedule":{"washesPerWeek":1,"scalpSymptoms":"NO","treatments":{
                "NUTRITION":{"status":"FIRST_USE","every":2,"unit":"WEEKS"},
                "RECONSTRUCTION":{"status":"FIRST_USE","every":2,"unit":"MONTHS"}}}}
                """);
        for(int weeks=1;weeks<=12;weeks++)for(int count=1;count<=7;count++) {
            ((tools.jackson.databind.node.ObjectNode)profile.path("careSchedule")).put("washesPerWeek",count);
            var weekdays=HairScheduleSuggestions.weekdays(profile,start);
            var dates=dates(weeks*7).stream().filter(d->weekdays.contains(d.getDayOfWeek())).toList();
            var result=HairScheduleSuggestions.build(profile,dates,Map.of(),List.of(),"fixture");
            assertEquals(weeks*count,result.sessions().size());
            for(var stage:List.of(HairStage.NUTRITION,HairStage.RECONSTRUCTION)) {
                var applied=result.sessions().stream().filter(s->s.stage()==stage).map(HairScheduleSuggestions.Session::date).toList();
                for(int i=1;i<applied.size();i++) assertFalse(applied.get(i).isBefore(stage==HairStage.NUTRITION
                        ?applied.get(i-1).plusWeeks(2):applied.get(i-1).plusMonths(2)));
            }
        }
    }

    private List<LocalDate> dates(int count) { return IntStream.range(0,count).mapToObj(start::plusDays).toList(); }
}
