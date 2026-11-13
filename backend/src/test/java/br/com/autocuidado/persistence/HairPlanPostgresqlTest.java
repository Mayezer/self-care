package br.com.autocuidado.persistence;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import br.com.autocuidado.SelfCareApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgresql")
class HairPlanPostgresqlTest {

    private static final String PLANS = "/api/hair-plans";
    private static final String PASSWORD = "Local-test-321";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void savesAndReloadsThePlanAtomicallyWithAccountScopedRetries() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "fsd05_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String body = payload(UUID.randomUUID());
        long planId;
        long otherPlanId;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                try (var application = startApplication(url, "2026-11-10T00:30:00Z")) {
                    var jdbc = application.getBean(JdbcTemplate.class);
                    String hash = application.getBean(PasswordEncoder.class).encode(PASSWORD);
                    for (String username : new String[] {"owner_test", "other_test"}) {
                        jdbc.update("INSERT INTO accounts (username, password_hash, time_zone) VALUES (?, ?, 'UTC')",
                                username, hash);
                    }
                    var anonymous = new Session(application);
                    assertStatus(401, anonymous.send(anonymous.createRequest(body, anonymous.csrf())));
                    var owner = new Session(application);
                    owner.login("owner_test");
                    var other = new Session(application);
                    other.login("other_test");
                    assertStatus(403, owner.send(owner.createRequest(body, null)));
                    verifyValidation(owner, body);
                    assertCounts(jdbc, 0, 0);

                    var request = owner.createRequest(body, owner.csrf());
                    var first = owner.client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                    var second = owner.client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                    planId = createdId(first.get(15, TimeUnit.SECONDS));
                    assertEquals(planId, createdId(second.get(15, TimeUnit.SECONDS)));
                    assertCounts(jdbc, 1, 4);
                    verifyPlan(owner, planId);

                    assertStatus(409, owner.create(body.replace("HYDRATION", "NUTRITION")));
                    var foreign = other.get(PLANS + "/" + planId);
                    var missing = other.get(PLANS + "/999999999");
                    assertStatus(404, foreign);
                    assertEquals(missing.statusCode(), foreign.statusCode());
                    assertEquals(missing.body(), foreign.body());
                    assertStatus(400, other.create(body.replaceFirst("\\{",
                            "{\"planId\":" + planId + ",\"accountId\":1,")));
                    assertCounts(jdbc, 1, 4);

                    otherPlanId = createdId(other.create(body.replace("HYDRATION", "NUTRITION")));
                    assertNotEquals(planId, otherPlanId);
                    assertEquals("NUTRITION", json(other.get(PLANS + "/" + otherPlanId))
                            .at("/occurrences/0/stage").asString());
                    assertStatus(404, owner.get(PLANS + "/" + otherPlanId));
                    verifyPlan(owner, planId);
                    assertCounts(jdbc, 2, 8);

                    verifyRollbackAndNewRequest(owner, jdbc);
                }
                try (var restarted = startApplication(url, "2026-11-12T00:30:00Z")) {
                    var owner = new Session(restarted);
                    owner.login("owner_test");
                    verifyPlan(owner, planId);
                    assertStatus(404, owner.get(PLANS + "/" + otherPlanId));
                    assertEquals(planId, createdId(owner.create(body)));
                    assertStatus(400, owner.create(payload(UUID.randomUUID())));
                    assertCounts(restarted.getBean(JdbcTemplate.class), 3, 12);
                }
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void verifyValidation(Session owner, String body) throws Exception {
        var invalidStage = owner.create(body.replace("HYDRATION", "UNKNOWN"));
        assertStatus(400, invalidStage);
        assertTrue(json(invalidStage).at("/errors/occurrences").isString());
        var wrongDate = owner.create(body.replace("2026-11-23", "2026-11-24"));
        assertStatus(400, wrongDate);
        assertTrue(json(wrongDate).at("/errors/occurrences").isString());
        var past = owner.create(body.replace("2026-11-11", "2026-11-09"));
        assertStatus(400, past);
        assertTrue(json(past).at("/errors/startDate").isString());
    }

    private static void verifyRollbackAndNewRequest(Session owner, JdbcTemplate jdbc) throws Exception {
        // Force the second occurrence to fail after PostgreSQL has inserted the plan and first occurrence.
        jdbc.execute("""
                CREATE FUNCTION fail_test_occurrence() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.planned_date = DATE '2026-11-16' THEN
                        RAISE EXCEPTION 'Forced failure in the second occurrence';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_test_occurrence BEFORE INSERT ON care_occurrences
                FOR EACH ROW EXECUTE FUNCTION fail_test_occurrence()
                """);
        String newRequest = payload(UUID.randomUUID());
        try {
            assertStatus(500, owner.create(newRequest));
            assertCounts(jdbc, 2, 8);
        } finally {
            jdbc.execute("DROP TRIGGER fail_test_occurrence ON care_occurrences");
        }
        long id = createdId(owner.create(newRequest));
        assertEquals(id, createdId(owner.create(newRequest)));
        assertCounts(jdbc, 3, 12);
    }

    private static void verifyPlan(Session owner, long planId) throws Exception {
        var result = owner.get(PLANS + "/" + planId);
        assertStatus(200, result);
        var plan = json(result);
        assertEquals(planId, plan.path("planId").asLong());
        assertEquals("2026-11-11", plan.path("startDate").asString());
        assertEquals(2, plan.path("weeks").asInt());
        assertEquals(JSON.readTree("[\"MONDAY\",\"THURSDAY\"]"), plan.path("weekdays"));
        assertEquals(JSON.readTree(payload(UUID.randomUUID())).path("occurrences"), plan.path("occurrences"));
    }

    private static String payload(UUID id) {
        return """
                {"requestId":"%s","startDate":"2026-11-11","weeks":2,
                 "weekdays":["THURSDAY","MONDAY","THURSDAY"],"timeZone":"America/Sao_Paulo","occurrences":[
                  {"date":"2026-11-12","stage":"HYDRATION"},
                  {"date":"2026-11-16","stage":"WASH_ONLY"},
                  {"date":"2026-11-19","stage":"NUTRITION"},
                  {"date":"2026-11-23","stage":"RECONSTRUCTION"}
                 ]}
                """.formatted(id);
    }

    private static ConfigurableApplicationContext startApplication(String url, String instant) {
        return new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                .run("--server.address=127.0.0.1", "--server.port=0",
                        "--spring.datasource.url=" + url, "--test.instant=" + instant);
    }

    private static long createdId(HttpResponse<String> response) {
        assertStatus(201, response);
        long id = json(response).path("planId").asLong();
        assertTrue(id > 0);
        return id;
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static void assertStatus(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), response::body);
    }

    private static void assertCounts(JdbcTemplate jdbc, int plans, int occurrences) {
        assertEquals(plans, jdbc.queryForObject("SELECT count(*) FROM recurring_plans", Integer.class));
        assertEquals(occurrences, jdbc.queryForObject("SELECT count(*) FROM care_occurrences", Integer.class));
    }

    private static class Session {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        private final String origin;

        Session(ConfigurableApplicationContext application) {
            origin = "http://127.0.0.1:" + ((WebServerApplicationContext) application).getWebServer().getPort();
        }

        void login(String username) throws Exception {
            String token = csrf();
            assertStatus(204, send(request("/api/auth/login")
                    .header("X-CSRF-TOKEN", token)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + PASSWORD))
                    .build()));
        }

        String csrf() throws Exception {
            var response = get("/api/auth/csrf");
            assertStatus(200, response);
            return json(response).path("token").asString();
        }

        HttpResponse<String> create(String body) throws Exception {
            return send(createRequest(body, csrf()));
        }

        HttpRequest createRequest(String body, String token) {
            var request = request(PLANS).header("Content-Type", "application/json");
            if (token != null) {
                request.header("X-CSRF-TOKEN", token);
            }
            return request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(request(path).GET().build());
        }

        HttpResponse<String> send(HttpRequest request) throws Exception {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock(@Value("${test.instant}") String instant) {
            return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        }
    }
}
