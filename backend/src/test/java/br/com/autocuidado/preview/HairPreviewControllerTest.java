package br.com.autocuidado.preview;

import br.com.autocuidado.auth.LoginRateLimit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import br.com.autocuidado.auth.AccountTimeZones;
import br.com.autocuidado.auth.SecurityConfiguration;
import br.com.autocuidado.hair.HairPlanController;
import br.com.autocuidado.persistence.HairPlanService;
import br.com.autocuidado.persistence.HairProfileStore;
import org.junit.jupiter.api.Test;
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

    @ParameterizedTest
    @ValueSource(strings = {"HYDRATION", "NUTRITION", "RECONSTRUCTION"})
    void schedulesTheChosenStageOnlyOnTheFirstGeneratedDate(String goal) throws Exception {
        submit(suggest("{\"goal\":\"%s\",\"useProfile\":true}".formatted(goal)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.suggestion.status").value("MODEL_SELECTED"))
                .andExpect(jsonPath("$.suggestion.version").value(1))
                .andExpect(jsonPath("$.suggestion.reviewedOn").value("2026-11-13"))
                .andExpect(jsonPath("$.occurrences.length()").value(4))
                .andExpect(jsonPath("$.occurrences[0].date").value("2026-11-12"))
                .andExpect(jsonPath("$.occurrences[0].stage").value(goal))
                .andExpect(jsonPath("$.occurrences[1].date").value("2026-11-16"))
                .andExpect(jsonPath("$.occurrences[1].stage").value("WASH_ONLY"))
                .andExpect(jsonPath("$.occurrences[3].stage").value("WASH_ONLY"));
        verifyNoInteractions(profiles, plans);
    }

    @Test
    void fallsBackToWashingWhenAVisitorHasNoGoal() throws Exception {
        submit(suggest("{\"useProfile\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion.status").value("INSUFFICIENT_ANSWERS"))
                .andExpect(jsonPath("$.occurrences[0].stage").value("WASH_ONLY"));
        verifyNoInteractions(profiles, plans);
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
                .andExpect(jsonPath("$.errors.weekdays").isString())
                .andExpect(jsonPath("$.errors.timeZone").isString());
    }

    @Test
    void rejectsAnEmptySelection() throws Exception {
        submit(payload("2026-11-11", "2", "[]", "America/Sao_Paulo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.weekdays").isString());
    }

    @Test
    void validatesTodayInTheSubmittedZone() throws Exception {
        submit(payload("2026-11-09", "1", "[\"MONDAY\"]", "America/Sao_Paulo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences[0].date").value("2026-11-09"));
        submit(payload("2026-11-09", "1", "[\"MONDAY\"]", "UTC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.startDate").isString());
    }

    @Test
    void rejectsAnIntervalThatCannotBeRepresentedWithFourDigitYears() throws Exception {
        submit(payload("9999-12-31", "2", "[\"MONDAY\"]", "UTC"))
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
