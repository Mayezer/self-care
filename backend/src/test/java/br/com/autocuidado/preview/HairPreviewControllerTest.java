package br.com.autocuidado.preview;

import br.com.autocuidado.auth.LoginRateLimit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.List;
import br.com.autocuidado.hair.HairCareRoutine;
import br.com.autocuidado.hair.HairStage;
import tools.jackson.databind.json.JsonMapper;

import br.com.autocuidado.auth.AccountTimeZones;
import br.com.autocuidado.auth.SecurityConfiguration;
import br.com.autocuidado.hair.HairPlanController;
import br.com.autocuidado.persistence.HairPlanService;
import br.com.autocuidado.persistence.HairProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HairPlanController.class, properties = "app.auth.mfa-required=false")
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import(SecurityConfiguration.class)
class HairPreviewControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private HairPlanService plans;

    @MockitoBean
    private HairProfileStore profiles;

    @MockitoBean
    private AccountTimeZones timeZones;

    @MockitoBean
    private LoginRateLimit loginRateLimit;

    @BeforeEach
    void allowPreviewBudget() {
        when(loginRateLimit.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
        var general = new HairPlanService.SuggestedPreview(
                List.of("2026-11-12", "2026-11-16", "2026-11-19", "2026-11-23").stream()
                        .map(date -> new HairPlanService.Occurrence(LocalDate.parse(date), HairStage.WASH_ONLY)).toList(),
                HairCareRoutine.from(JsonMapper.builder().build().createObjectNode()), List.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY));
        when(plans.preview(isNull(), any(), anyInt(), anySet(), any())).thenReturn(general);
    }

    @Test
    void previewsTheSpecificationExampleAnonymouslyWithoutCreatingAPlanOrSession() throws Exception {
        String body = payload("2026-11-11", "2", "[\"THURSDAY\",\"MONDAY\",\"THURSDAY\"]", "America/Sao_Paulo");
        String expected = """
                {"occurrences":[
                  {"date":"2026-11-12","stage":"WASH_ONLY"},
                  {"date":"2026-11-16","stage":"WASH_ONLY"},
                  {"date":"2026-11-19","stage":"WASH_ONLY"},
                  {"date":"2026-11-23","stage":"WASH_ONLY"}
                ]}
                """;

        for (int attempt = 0; attempt < 2; attempt++) {
            submit(body)
                    .andExpect(status().isOk())
                    .andExpect(content().json(expected, JsonCompareMode.STRICT))
                    .andExpect(result -> assertNull(result.getRequest().getSession(false)));
        }
    }

    @Test
    void suppliesAFullCareRoutineForEveryDateWithoutInferringTreatmentIntervals() throws Exception {
        submit(suggest("{\"useProfile\":true}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.suggestion.modelId").value("daily-hair-care"))
                .andExpect(jsonPath("$.suggestion.version").value(2))
                .andExpect(jsonPath("$.suggestion.reviewedOn").value("2026-11-23"))
                .andExpect(jsonPath("$.suggestion.steps.length()").value(4))
                .andExpect(jsonPath("$.suggestion.personalized").value(false))
                .andExpect(jsonPath("$.occurrences.length()").value(4))
                .andExpect(jsonPath("$.occurrences[0].date").value("2026-11-12"))
                .andExpect(jsonPath("$.occurrences[0].stage").value("WASH_ONLY"))
                .andExpect(jsonPath("$.occurrences[1].date").value("2026-11-16"))
                .andExpect(jsonPath("$.occurrences[1].stage").value("WASH_ONLY"))
                .andExpect(jsonPath("$.occurrences[3].stage").value("WASH_ONLY"));
        verifyNoInteractions(profiles);
        verify(plans).preview(isNull(), any(), anyInt(), anySet(), any());
    }

    @Test
    void offersGeneralCareWhenTheVisitorHasNoSavedProfile() throws Exception {
        submit(suggest("{\"useProfile\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion.personalized").value(false))
                .andExpect(jsonPath("$.suggestion.steps[0].id").value("cleanse"))
                .andExpect(jsonPath("$.occurrences[0].stage").value("WASH_ONLY"));
        verifyNoInteractions(profiles);
        verify(plans).preview(isNull(), any(), anyInt(), anySet(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{\"goal\":\"INVALID\"}", "{\"useProfile\":\"true\"}", "{\"accountId\":1}"})
    void rejectsInvalidSuggestionOptions(String options) throws Exception {
        submit(suggest(options)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.suggestion").isString());
        verifyNoInteractions(profiles, plans);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "[\"MONDAY\"] | 12 | 2027-01-25",
        "[\"SUNDAY\",\"SATURDAY\",\"FRIDAY\",\"THURSDAY\",\"WEDNESDAY\",\"TUESDAY\",\"MONDAY\"] | 84 | 2027-01-31"
    })
    void previewsTwelveWeeksWithTheMinimumAndMaximumFrequency(String weekdays, int total, String lastDate)
            throws Exception {
        submit(payload("2026-11-09", "12", weekdays, "America/Sao_Paulo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences.length()").value(total))
                .andExpect(jsonPath("$.occurrences[0].date").value("2026-11-09"))
                .andExpect(jsonPath("$.occurrences[%d].date".formatted(total - 1)).value(lastDate));
    }

    @ParameterizedTest
    @ValueSource(strings = {"13", "0", "1.5", "\"2\""})
    void rejectsOutOfRangeOrNonIntegerWeeks(String weeks) throws Exception {
        submit(payload("2026-11-11", weeks, "[\"MONDAY\"]", "America/Sao_Paulo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.weeks").isString());
    }

    @Test
    void reportsInvalidFieldsSeparately() throws Exception {
        submit(payload("2026-02-30", "null", "[null,\"FUNDAY\"]", "+03:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.startDate").isString())
                .andExpect(jsonPath("$.errors.weeks").isString())
                .andExpect(jsonPath("$.errors.weekdays").isString())
                .andExpect(jsonPath("$.errors.timeZone").isString());
    }

    @Test
    void reportsMissingRequiredFields() throws Exception {
        submit("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.startDate").isString())
                .andExpect(jsonPath("$.errors.weeks").isString())
                .andExpect(jsonPath("$.errors.weekdays").isString());
    }

    @Test
    void rejectsAnEmptySelection() throws Exception {
        submit(payload("2026-11-11", "2", "[]", "America/Sao_Paulo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.weekdays").isString());
    }

    @Test
    void usesTheSaoPauloDayForVisitorsAndRejectsChangingTheZone() throws Exception {
        submit(payload("2026-11-09", "1", "[\"MONDAY\"]", "America/Sao_Paulo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences[0].date").value("2026-11-09"));
        submit(payload("2026-11-09", "1", "[\"MONDAY\"]", "UTC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.timeZone").isString());
        submit("{\"startDate\":\"2026-11-09\",\"weeks\":1,\"weekdays\":[\"MONDAY\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences[0].date").value("2026-11-09"));
    }

    @Test
    void rejectsAnIntervalThatCannotBeRepresentedWithFourDigitYears() throws Exception {
        submit(payload("9999-12-31", "2", "[\"MONDAY\"]", "America/Sao_Paulo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.startDate").isString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "{\"weeks\":"})
    void rejectsMissingOrInvalidJsonObjects(String body) throws Exception {
        submit(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.body").isString());
    }

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/api/hair-plans/preview").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String suggest(String options) {
        return """
                {"startDate":"2026-11-11","weeks":2,"weekdays":["MONDAY","THURSDAY"],
                 "timeZone":"America/Sao_Paulo","suggestion":%s}
                """.formatted(options);
    }

    private static String payload(String date, String weeks, String weekdays, String zone) {
        return """
                {"startDate":"%s","weeks":%s,"weekdays":%s,"timeZone":"%s"}
                """.formatted(date, weeks, weekdays, zone);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"), ZoneOffset.UTC);
        }
    }
}
