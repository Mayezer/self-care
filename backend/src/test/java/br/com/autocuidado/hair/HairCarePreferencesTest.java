package br.com.autocuidado.hair;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class HairCarePreferencesTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private final LocalDate today=LocalDate.of(2026,11,26);

    @Test void validatesDatesIntervalsAndKnownToleranceWithoutInventingDefaults() {
        for(String data: new String[]{"{\"washesPerWeek\":0}","{\"washesPerWeek\":\"3\"}","{\"scalpSymptoms\":false}",
                "{\"treatments\":{\"NUTRITION\":{\"status\":\"KNOWN\",\"lastUse\":\"2026-11-27\",\"every\":2,\"unit\":\"WEEKS\"}}}",
                "{\"treatments\":{\"RECONSTRUCTION\":{\"status\":\"FIRST_USE\",\"every\":0,\"unit\":\"MONTHS\"}}}",
                "{\"treatments\":{\"RECONSTRUCTION\":{\"status\":\"FIRST_USE\",\"every\":2,\"unit\":\"WASHES\"}}}"}) {
            var errors=new LinkedHashMap<String,String>();
            HairProfileAnswers.parse(json.readTree("{\"careSchedule\":"+data+"}"),today,errors);
            assertTrue(errors.containsKey("careSchedule"),data);
        }
        var errors=new LinkedHashMap<String,String>();
        var empty=HairProfileAnswers.parse(json.readTree("{}"),today,errors);
        assertTrue(empty.isEmpty());assertTrue(errors.isEmpty());
        var value=HairProfileAnswers.parse(json.readTree("""
                {"careSchedule":{"washesPerWeek":3,"scalpSymptoms":"UNKNOWN","treatments":{
                 "HYDRATION":{"status":"AVOID","every":1,"unit":"DAYS","lastUse":"2020-01-01"},
                 "RECONSTRUCTION":{"status":"KNOWN","every":2,"unit":"MONTHS","lastUse":"2026-10-31"}}}}
                """),today,errors);
        assertTrue(errors.isEmpty());
        assertEquals("UNKNOWN",value.at("/careSchedule/scalpSymptoms").asString());
        assertFalse(value.at("/careSchedule/treatments/HYDRATION").has("every"));
        assertEquals("MONTHS",value.at("/careSchedule/treatments/RECONSTRUCTION/unit").asString());
        assertEquals("2026-10-31",value.at("/careSchedule/treatments/RECONSTRUCTION/lastUse").asString());
    }
}
