package br.com.autocuidado.hair;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.imageio.ImageIO;

import br.com.autocuidado.SelfCareApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgresql")
class HairDiaryPostgresqlTest {
    private static final Instant NOW = Instant.parse("2026-11-11T01:00:00Z");
    private static final String PATH = "/api/hair-diary/photos";
    private static final String PASSWORD = "FicticiaDiary42!";
    private static final JsonMapper JSON = new JsonMapper();

    @Test
    void storesOnlyPrivateChronologicalPhotosAndPurgesThemAfterAccessEnds() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        String user = System.getenv("SPRING_DATASOURCE_USERNAME");
        String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(database, user, password));
        String schema = "diary_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String schemaUrl = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try {
            Flyway.configure().dataSource(schemaUrl, user, password).schemas(schema).target("10").load().migrate();
            var previous = new JdbcTemplate(new DriverManagerDataSource(schemaUrl, user, password));
            long ownerId = account(previous, "diary_owner");
            long otherId = account(previous, "diary_other");
            account(previous, "diary_free");
            for (long id : new long[] {ownerId, otherId}) {
                previous.update("INSERT INTO billing_accounts (account_id, customer_request_id, access_until) VALUES (?, ?, ?)",
                        id, UUID.randomUUID(), NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC));
            }
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + schemaUrl,
                    "--app.billing.enabled=true", "--app.hair.diary-enabled=true",
                    "--app.billing.stripe.secret-key=sk_test_fixture", "--app.billing.stripe.price-id=price_fixture",
                    "--app.billing.stripe.webhook-secret=whsec_fixture",
                    "--app.billing.return-url=http://127.0.0.1:4226/configuracoes/assinatura")) {
                var jdbc = app.getBean(JdbcTemplate.class);
                jdbc.update("UPDATE accounts SET password_hash = ?", app.getBean(PasswordEncoder.class).encode(PASSWORD));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND success", Integer.class));
                assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM billing_accounts", Integer.class));
                String origin = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
                var anonymous = new Session(origin);
                var owner = new Session(origin);
                var other = new Session(origin);
                var free = new Session(origin);
                owner.login("diary_owner");
                other.login("diary_other");
                free.login("diary_free");

                String late = UUID.randomUUID().toString();
                String early = UUID.randomUUID().toString();
                byte[] png = PhotoRasterTest.raster(2000, 600, "PNG");
                assertEquals(401, anonymous.get(PATH + "/" + late).statusCode());
                assertEquals(403, free.get(PATH).statusCode());
                assertEquals(403, free.put(late, "2026-11-10", png, true).statusCode());
                assertEquals(403, owner.put(late, "2026-11-10", png, false).statusCode());
                assertEquals(400, owner.put(late, "2026-11-11", png, true).statusCode());
                assertEquals(400, owner.put(late, "2026-11-10", new byte[] {1, 2, 3}, true).statusCode());
                assertEquals(413, owner.put(late, "2026-11-10", new byte[PhotoRaster.MAX_BYTES + 1], true).statusCode());

                assertEquals(204, owner.put(late, "2026-11-10", png, true).statusCode());
                assertEquals(204, owner.put(early, "2026-10-30", png, true).statusCode());
                assertEquals(204, owner.put(early, "2026-10-30", png, true).statusCode());
                assertEquals(409, owner.put(early, "2026-10-29", png, true).statusCode());
                var listed = owner.get(PATH);
                assertEquals(200, listed.statusCode());
                assertTrue(listed.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
                var photos = JSON.readTree(listed.body());
                assertEquals(2, photos.size());
                assertEquals(early, photos.get(0).path("id").asString());
                assertEquals(late, photos.get(1).path("id").asString());
                var file = owner.get(PATH + "/" + late);
                assertEquals(200, file.statusCode());
                assertEquals("image/jpeg", file.headers().firstValue("Content-Type").orElseThrow());
                assertTrue(file.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
                assertEquals("same-origin", file.headers().firstValue("Cross-Origin-Resource-Policy").orElseThrow());
                assertEquals(1600, ImageIO.read(new ByteArrayInputStream(file.body())).getWidth());
                assertEquals(404, other.get(PATH + "/" + late).statusCode());
                assertEquals(404, other.delete(late).statusCode());
                assertEquals(403, free.get(PATH + "/" + late).statusCode());
                assertEquals(204, owner.delete(early).statusCode());
                assertEquals(404, owner.get(PATH + "/" + early).statusCode());
                assertEquals(204, other.put(early, "2026-10-30", png, true).statusCode());

                jdbc.update("UPDATE billing_accounts SET access_until = ? WHERE account_id = ?",
                        NOW.minus(31, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC), ownerId);
                assertEquals(403, owner.get(PATH).statusCode());
                assertEquals(403, owner.get(PATH + "/" + late).statusCode());
                assertEquals(403, owner.put(early, "2026-10-30", png, true).statusCode());
                var retention = app.getBean(DiaryRetention.class);
                retention.purgeExpired();
                assertEquals(1, photoCount(jdbc, ownerId));
                jdbc.update("UPDATE billing_accounts SET access_lost_at = ?, access_until = NULL WHERE account_id = ?",
                        NOW.minus(29, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC), ownerId);
                retention.purgeExpired();
                assertEquals(1, photoCount(jdbc, ownerId));
                jdbc.update("UPDATE billing_accounts SET access_lost_at = ?, access_until = NULL WHERE account_id = ?",
                        NOW.minus(30, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC), ownerId);
                retention.purgeExpired();
                assertEquals(0, photoCount(jdbc, ownerId));
                assertEquals(1, photoCount(jdbc, otherId));
            }
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static long account(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (username, password_hash, time_zone) VALUES (?, 'temporary', 'America/Sao_Paulo')
                RETURNING id
                """, Long.class, name);
    }

    private static int photoCount(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT count(*) FROM hair_diary_photos WHERE account_id = ?", Integer.class, id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock diaryClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    private static final class Session {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        private final String origin;

        Session(String origin) { this.origin = origin; }

        void login(String name) throws Exception {
            var request = csrf(request("/api/auth/login")).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + name + "&password=" + PASSWORD));
            assertEquals(204, send(request).statusCode());
        }

        HttpResponse<byte[]> get(String path) throws Exception { return send(request(path).GET()); }

        HttpResponse<byte[]> delete(String id) throws Exception { return send(csrf(request(PATH + "/" + id)).DELETE()); }

        HttpResponse<byte[]> put(String id, String date, byte[] image, boolean token) throws Exception {
            String boundary = "fixture-" + UUID.randomUUID();
            var body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"date\"\r\n\r\n"
                    + date + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"fixture.jpg\"\r\n"
                    + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(image);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var request = request(PATH + "/" + id).header("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (token) csrf(request);
            return send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
        }

        private HttpRequest.Builder csrf(HttpRequest.Builder request) throws Exception {
            var response = get("/api/auth/csrf");
            assertEquals(200, response.statusCode());
            var data = JSON.readTree(response.body());
            return request.header(data.path("headerName").asString(), data.path("token").asString());
        }

        private HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create(origin + path)); }

        private HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }
}
