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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import br.com.autocuidado.SelfCareApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgresql")
class HairProfilePostgresqlTest {

    private static final String PROFILE = "/api/hair-profile";
    private static final String PRODUCTS = PROFILE + "/products";
    private static final String HISTORY = "/api/history";
    private static final String PASSWORD = "Local-test-321";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void migratesPilotAccountsAndKeepsOptionalProfilePrivateAcrossRestart() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String username = System.getenv("SPRING_DATASOURCE_USERNAME");
        String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        String schema = "e204_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var month = YearMonth.now(ZoneId.of("America/Sao_Paulo"));
        String profile = """
                {"curlPattern":{"kind":"MULTIPLE","groups":[{"group":"WAVY","subtype":"2A"},{"group":"CURLY"}]},
                "strandThickness":"FINE","rootOiliness":"OFTEN","chemicalTreatments":["DYE"],
                "lastChemicalTreatmentMonth":"%s","heatFrequency":"LESS_THAN_WEEKLY","heatTools":["DRYER"],
                "observations":["DRYNESS"]}
                """.formatted(month);

        try (var admin = DriverManager.getConnection(database, username, password); var schemas = admin.createStatement()) {
            schemas.execute("CREATE SCHEMA " + schema);
            try {
                long owner;
                long plan;
                try (var previous = new SpringApplicationBuilder(SelfCareApplication.class)
                        .web(WebApplicationType.NONE).run("--spring.datasource.url=" + url, "--spring.flyway.target=4")) {
                    var jdbc = previous.getBean(JdbcTemplate.class);
                    var hash = previous.getBean(PasswordEncoder.class).encode(PASSWORD);
                    owner = jdbc.queryForObject("""
                            INSERT INTO accounts (username, password_hash, time_zone)
                            VALUES ('legacy_profile', ?, 'America/Sao_Paulo') RETURNING id
                            """, Long.class, hash);
                    plan = jdbc.queryForObject("""
                            INSERT INTO recurring_plans (account_id, category, start_date, weeks, end_date)
                            VALUES (?, 'HAIR', '2026-11-09', 1, '2026-11-15') RETURNING id
                            """, Long.class, owner);
                    jdbc.update("INSERT INTO recurring_plan_weekdays VALUES (?, 'MONDAY')", plan);
                    jdbc.update("""
                            INSERT INTO care_occurrences
                                (account_id, plan_id, category, title, planned_date, status, completed_on, hair_stage)
                            VALUES (?, ?, 'HAIR', 'Lavar cabelo', '2026-11-09', 'COMPLETED', '2026-11-09', 'HYDRATION')
                            """, owner, plan);
                }

                String planPath = "/api/hair-plans/" + plan;
                JsonNode savedPlan;
                JsonNode history;
                try (var app = start(url)) {
                    var jdbc = app.getBean(JdbcTemplate.class);
                    jdbc.update("INSERT INTO accounts (username, password_hash, time_zone) VALUES (?, ?, 'UTC')",
                            "other_profile", app.getBean(PasswordEncoder.class).encode(PASSWORD));
                    assertEquals(1, jdbc.queryForObject("""
                            SELECT count(*) FROM flyway_schema_history WHERE version = '5' AND success
                            """, Integer.class));
                    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM hair_profiles", Integer.class));

                    var session = new Session(origin(app));
                    var other = new Session(origin(app));
                    session.get(PROFILE, 401);
                    session.put(PROFILE, profile, true, 401);
                    session.login("legacy_profile");
                    assertEquals("legacy_profile", session.get("/api/agenda", 200).path("username").asString());
                    savedPlan = session.get(planPath, 200);
                    history = session.get(HISTORY, 200);
                    assertEquals("HYDRATION", history.at("/0/stage").asString());
                    assertTrue(session.get(PROFILE, 200).isEmpty());
                    session.put(PROFILE, profile, false, 403);

                    assertEquals(JSON.readTree(profile), session.put(PROFILE, profile, true, 200));
                    assertEquals(JSON.readTree(profile), session.get(PROFILE, 200));
                    other.login("other_profile");
                    assertTrue(other.get(PROFILE, 200).isEmpty());
                    other.get(PROFILE + "/" + owner, 404);
                    other.put(PROFILE + "/" + owner, "{}", true, 404);
                    assertTrue(other.put(PROFILE, "{\"accountId\":" + owner + "}", true, 400).at("/errors/body").isString());
                    other.put(PROFILE, """
                            {"visualLength":"AT_SHOULDERS","heatFrequency":"NEVER","heatTools":["DRYER"]}
                            """, true, 200);
                    assertEquals(JSON.readTree("{\"visualLength\":\"AT_SHOULDERS\",\"heatFrequency\":\"NEVER\"}"),
                            other.get(PROFILE, 200));

                    var errors = session.put(PROFILE, """
                            {"curlPattern":{"kind":"SINGLE","groups":[{"group":"WAVY","subtype":"3A"}]},
                            "chemicalTreatments":["NONE","DYE"],"lastChemicalTreatmentMonth":"%s",
                            "observations":["UNKNOWN","FRIZZ"]}
                            """.formatted(month.plusMonths(1)), true, 400).path("errors");
                    for (String field : new String[]{"curlPattern", "chemicalTreatments", "lastChemicalTreatmentMonth", "observations"}) {
                        assertTrue(errors.path(field).isString(), field);
                    }
                    assertTrue(session.put(PROFILE, """
                            {"chemicalTreatments":["DYE"],"lastChemicalTreatmentMonth":"%s"}
                            """.formatted(month.plusMonths(1)), true, 400).at("/errors/lastChemicalTreatmentMonth").isString());
                    assertEquals(JSON.readTree(profile), session.get(PROFILE, 200));
                }

                try (var paused = start(url, false)) {
                    var session = new Session(origin(paused));
                    session.login("legacy_profile");
                    var features = session.get("/api/agenda", 200);
                    assertEquals(JSON.readTree("false"), features.path("hairProfileAvailable"));
                    assertEquals(JSON.readTree("false"), features.path("hairProductsAvailable"));
                    session.get(PROFILE, 404);
                    session.get(PRODUCTS, 404);
                    session.put(PROFILE, "{}", true, 404);
                    assertEquals(savedPlan, session.get(planPath, 200));
                    assertEquals(history, session.get(HISTORY, 200));
                }

                try (var restarted = start(url)) {
                    var session = new Session(origin(restarted));
                    session.login("legacy_profile");
                    assertEquals(JSON.readTree(profile), session.get(PROFILE, 200));
                    assertEquals(JSON.readTree("{\"rootOiliness\":\"RARELY\"}"),
                            session.put(PROFILE, "{\"rootOiliness\":\"RARELY\"}", true, 200));
                    assertEquals(JSON.readTree("{\"rootOiliness\":\"RARELY\"}"), session.get(PROFILE, 200));
                    assertTrue(session.put(PROFILE, "{}", true, 200).isEmpty());
                    assertTrue(session.get(PROFILE, 200).isEmpty());
                    assertEquals(0, restarted.getBean(JdbcTemplate.class).queryForObject(
                            "SELECT count(*) FROM hair_profiles WHERE account_id = ?", Integer.class, owner));
                    assertEquals(savedPlan, session.get(planPath, 200));
                    assertEquals(history, session.get(HISTORY, 200));
                    var other = new Session(origin(restarted));
                    other.login("other_profile");
                    assertEquals("AT_SHOULDERS", other.get(PROFILE, 200).path("visualLength").asString());
                }
            } finally {
                schemas.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void recommendsFromTheSavedProfileOfEachSessionAndCanPauseIndependently() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String username = System.getenv("SPRING_DATASOURCE_USERNAME");
        String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        String schema = "e220_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String oily = "{\"rootOiliness\":\"OFTEN\"}";
        String heat = "{\"heatFrequency\":\"LESS_THAN_WEEKLY\",\"heatTools\":[\"CURLING_IRON\"]}";
        var empty = JSON.readTree("{\"hasProfile\":false,\"cards\":[]}");

        try (var admin = DriverManager.getConnection(database, username, password); var schemas = admin.createStatement()) {
            schemas.execute("CREATE SCHEMA " + schema);
            try {
                try (var app = startProducts(url, true)) {
                    var jdbc = app.getBean(JdbcTemplate.class);
                    var hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                    long ownerId = jdbc.queryForObject("""
                            INSERT INTO accounts (username, password_hash, time_zone)
                            VALUES ('products_owner', ?, 'UTC') RETURNING id
                            """, Long.class, hash);
                    jdbc.update("""
                            INSERT INTO accounts (username, password_hash, time_zone)
                            VALUES ('products_other', ?, 'America/Sao_Paulo')
                            """, hash);

                    var owner = new Session(origin(app));
                    var other = new Session(origin(app));
                    owner.get(PRODUCTS, 401);
                    owner.login("products_owner");
                    other.login("products_other");
                    assertEquals(JSON.readTree("true"), owner.get("/api/agenda", 200).path("hairProductsAvailable"));
                    assertEquals(empty, owner.get(PRODUCTS + "?rootOiliness=OFTEN", 200));
                    owner.put(PROFILE, oily, true, 200);
                    var suggestions = owner.get(PRODUCTS, 200);
                    assertTrue(suggestions.path("hasProfile").asBoolean());
                    assertEquals(1, suggestions.path("cards").size());
                    assertEquals("SHAMPOO", suggestions.at("/cards/0/category").asString());
                    assertEquals("elseve-pure-hialuronico-shampoo-micelar-300",
                            suggestions.at("/cards/0/variant/id").asString());
                    assertTrue(suggestions.at("/cards/0/variant/manufacturerUrl").asString().startsWith("https://"));
                    assertEquals(empty, other.get(PRODUCTS + "?accountId=" + ownerId, 200));
                    other.get(PRODUCTS + "/" + ownerId, 404);

                    other.put(PROFILE, oily, true, 200);
                    var beforeReview = other.get(PRODUCTS, 200);
                    assertEquals("SHAMPOO", beforeReview.at("/cards/0/category").asString());
                    assertTrue(beforeReview.at("/cards/0/variant").isNull());

                    other.put(PROFILE, heat, true, 200);
                    var otherSuggestions = other.get(PRODUCTS + "?accountId=" + ownerId, 200);
                    assertEquals(1, otherSuggestions.path("cards").size());
                    assertEquals("HEAT_PROTECTANT", otherSuggestions.at("/cards/0/category").asString());
                    assertEquals(suggestions, owner.get(PRODUCTS, 200));

                    owner.put(PROFILE, "{\"observations\":[\"DRYNESS\"]}", true, 200);
                    var edited = owner.get(PRODUCTS, 200).path("cards");
                    assertEquals(2, edited.size());
                    assertEquals("CONDITIONER", edited.get(0).path("category").asString());
                    assertEquals("MASK", edited.get(1).path("category").asString());
                    owner.put(PROFILE, "{\"rootOiliness\":\"UNKNOWN\"}", true, 200);
                    assertEquals(JSON.readTree("{\"hasProfile\":true,\"cards\":[]}"), owner.get(PRODUCTS, 200));
                    owner.put(PROFILE, "{}", true, 200);
                    assertEquals(empty, owner.get(PRODUCTS, 200));
                    assertEquals(otherSuggestions, other.get(PRODUCTS, 200));
                }

                try (var paused = startProducts(url, false)) {
                    var other = new Session(origin(paused));
                    other.get(PRODUCTS, 401);
                    other.login("products_other");
                    other.get(PRODUCTS, 404);
                    assertEquals(JSON.readTree(heat), other.get(PROFILE, 200));
                    var account = other.get("/api/agenda", 200);
                    assertEquals("products_other", account.path("username").asString());
                    assertEquals(JSON.readTree("true"), account.path("hairProfileAvailable"));
                    assertEquals(JSON.readTree("false"), account.path("hairProductsAvailable"));
                    assertTrue(other.get(HISTORY, 200).isArray());
                }
            } finally {
                schemas.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static ConfigurableApplicationContext start(String url) {
        return start(url, true);
    }

    private static ConfigurableApplicationContext start(String url, boolean profileEnabled) {
        return new SpringApplicationBuilder(SelfCareApplication.class)
                .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                        "--app.hair.profile-enabled=" + profileEnabled, "--app.hair.products-enabled=true");
    }

    private static ConfigurableApplicationContext startProducts(String url, boolean enabled) {
        return new SpringApplicationBuilder(SelfCareApplication.class, CatalogClock.class)
                .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                        "--app.hair.profile-enabled=true", "--app.hair.products-enabled=" + enabled);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CatalogClock {
        @Bean
        @Primary
        Clock catalogClock() {
            return Clock.fixed(Instant.parse("2026-11-11T00:30:00Z"), ZoneOffset.UTC);
        }
    }

    private static String origin(ConfigurableApplicationContext app) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    private static class Session {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        private final String origin;

        Session(String origin) {
            this.origin = origin;
        }

        void login(String username) throws Exception {
            var request = withCsrf(request("/api/auth/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + PASSWORD));
            send(request, 204);
        }

        JsonNode get(String path, int status) throws Exception {
            return send(request(path).GET(), status);
        }

        JsonNode put(String path, String body, boolean csrf, int status) throws Exception {
            var request = request(path).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body));
            return send(csrf ? withCsrf(request) : request, status);
        }

        private HttpRequest.Builder withCsrf(HttpRequest.Builder request) throws Exception {
            var csrf = get("/api/auth/csrf", 200);
            return request.header(csrf.path("headerName").asString(), csrf.path("token").asString());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10));
        }

        private JsonNode send(HttpRequest.Builder request, int status) throws Exception {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(status, response.statusCode(), response::body);
            String path = response.uri().getPath();
            if (status == 200 && (path.equals(PROFILE) || path.equals(PRODUCTS))) {
                assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            }
            return response.body().isBlank() ? JSON.missingNode() : JSON.readTree(response.body());
        }
    }
}
