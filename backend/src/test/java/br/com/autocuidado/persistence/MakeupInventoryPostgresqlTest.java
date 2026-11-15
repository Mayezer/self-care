package br.com.autocuidado.persistence;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;

import br.com.autocuidado.SelfCareApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgresql")
class MakeupInventoryPostgresqlTest {
    private static final String PATH = "/api/makeup-items";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void keepsItemsPrivateAndPersistsEditsPurchasesAndRemoval() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "e307_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String password = UUID.randomUUID().toString();
        String id = UUID.randomUUID().toString();
        String itemPath = PATH + "/" + id;
        String create = """
                {"id":"%s","name":"  Máscara  ","status":"TO_BUY"}
                """.formatted(id);
        String edit = """
                {"name":"Máscara marrom","category":"Olhos","status":"RUNNING_LOW"}
                """;

        try (var admin = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var schemas = admin.createStatement()) {
            schemas.execute("CREATE SCHEMA " + schema);
            try {
                try (var app = start(url)) {
                    var jdbc = app.getBean(JdbcTemplate.class);
                    var hash = app.getBean(PasswordEncoder.class).encode(password);
                    jdbc.update("""
                            INSERT INTO accounts (username, password_hash, time_zone) VALUES
                            ('vanity_first', ?, 'America/Sao_Paulo'), ('vanity_second', ?, 'UTC')
                            """, hash, hash);

                    var first = new Session(app);
                    var second = new Session(app);
                    first.send("GET", PATH, null, false, 401);
                    first.send("POST", PATH, create, true, 401);
                    first.login("vanity_first", password);
                    second.login("vanity_second", password);
                    assertTrue(first.get(PATH).isEmpty());
                    first.send("POST", PATH, create, false, 403);
                    var invalid = first.send("POST", PATH, """
                            {"id":"%s","name":" ","category":7,"status":"UNKNOWN"}
                            """.formatted(id), true, 400);
                    for (String key : new String[]{"name", "category", "status"}) {
                        assertTrue(invalid.path("errors").path(key).isString());
                    }
                    first.send("POST", PATH, create.replace("{", "{\"accountId\":1,"), true, 400);
                    assertTrue(first.get(PATH).isEmpty());
                    var saved = first.send("POST", PATH, create, true, 200);
                    assertEquals("Máscara", saved.path("name").asString());
                    assertTrue(saved.path("category").isNull());
                    assertEquals(saved, first.send("POST", PATH, create, true, 200));
                    first.send("POST", PATH, create.replace("TO_BUY", "OWNED"), true, 409);
                    assertEquals(1, first.get(PATH).size());

                    assertTrue(second.get(PATH).isEmpty());
                    second.send("PUT", itemPath, edit, true, 404);
                    second.send("POST", itemPath + "/purchased", null, true, 404);
                    second.send("DELETE", itemPath, null, true, 404);
                    assertEquals(saved, first.get(PATH).get(0));

                    var edited = first.send("PUT", itemPath, edit, true, 200);
                    assertEquals("RUNNING_LOW", edited.path("status").asString());
                    assertEquals("Olhos", edited.path("category").asString());
                    var bought = first.send("POST", itemPath + "/purchased", null, true, 200);
                    assertEquals("OWNED", bought.path("status").asString());
                    assertEquals(edited.path("name"), bought.path("name"));
                    assertEquals(bought, first.send("POST", itemPath + "/purchased", null, true, 200));
                }
                try (var app = start(url)) {
                    var first = new Session(app);
                    first.login("vanity_first", password);
                    var saved = first.get(PATH).get(0);
                    assertEquals("Máscara marrom", saved.path("name").asString());
                    assertEquals("Olhos", saved.path("category").asString());
                    assertEquals("OWNED", saved.path("status").asString());
                    first.send("DELETE", itemPath, null, false, 403);
                    first.send("DELETE", itemPath, null, true, 204);
                    assertTrue(first.get(PATH).isEmpty());
                }
            } finally {
                schemas.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(SelfCareApplication.class)
                .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url);
    }

    private static final class Session {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        private final String origin;

        Session(ConfigurableApplicationContext app) {
            origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
        }

        void login(String username, String password) throws Exception {
            send("POST", "/api/auth/login", "username=" + username + "&password=" + password, true, 204);
        }

        JsonNode get(String path) throws Exception {
            return send("GET", path, null, false, 200);
        }

        JsonNode send(String method, String path, String body, boolean csrf, int expected) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10));
            if (csrf) {
                var token = get("/api/auth/csrf");
                request.header(token.path("headerName").asString(), token.path("token").asString());
            }
            if (body != null) {
                request.header("Content-Type", path.equals("/api/auth/login")
                        ? "application/x-www-form-urlencoded" : "application/json");
            }
            var response = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(expected, response.statusCode(), () -> method + " " + path);
            if (expected == 200 && path.startsWith(PATH)) {
                assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            }
            return response.body().isBlank() ? JSON.missingNode() : JSON.readTree(response.body());
        }
    }
}
