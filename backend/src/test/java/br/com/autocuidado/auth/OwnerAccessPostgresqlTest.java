package br.com.autocuidado.auth;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import br.com.autocuidado.SelfCareApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgresql")
class OwnerAccessPostgresqlTest {
    private static final String OWNER = "owner@example.test";
    private static final String OTHER = "another@example.test";
    private static final String PASSWORD = "A synthetic local password! 13245";
    private static final String ADMIN = "/api/admin/access";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant TIME = Instant.parse("2026-11-13T15:00:05Z");

    @Test
    void onlyTheProvisionedAccountGetsAdminAccessAndOnlyAfterMfa() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "owner_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String username = System.getenv("SPRING_DATASOURCE_USERNAME");
        String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        String key = HexFormat.of().formatHex(AuthSecrets.randomBytes(32));
        try (var connection = DriverManager.getConnection(database, username, password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, InboxConfiguration.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                            "--app.auth.mfa-required=true", "--app.auth.mfa-key=" + key,
                            "--app.forum.enabled=true", "--app.forum.public-reading-enabled=true",
                            "--app.auth.password-exposure-check=false", "--app.auth.email.from=sender@example.test");
                    var provisioner = DriverManager.getConnection(url, username, password)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                var hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                assertTrue(OwnerProvisioning.provision(provisioner, OWNER, hash, false));
                assertFalse(OwnerProvisioning.provision(provisioner, OTHER, hash, false));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email = ?", Integer.class, OTHER));

                jdbc.update("INSERT INTO accounts (username, password_hash, time_zone) VALUES ('legacy_test', ?, 'America/Sao_Paulo')", hash);
                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var inbox = app.getBean(Inbox.class);
                var clock = app.getBean(AdjustableClock.class);
                var owner = new BrowserSession(origin);
                var ordinary = new BrowserSession(origin);
                var legacy = new BrowserSession(origin);
                owner.get(ADMIN, 401);
                legacy.login("legacy_test", 401);
                legacy.post("/api/auth/mfa/email", "{}", 401);

                owner.login(OWNER, 202);
                owner.get(ADMIN, 401);
                owner.get("/api/forum/moderation/queue", 401);
                byte[] ownerSeed = enroll(owner, inbox, OWNER);
                owner.get(ADMIN, 204);

                ordinary.post("/api/auth/register", "{\"email\":\"" + OTHER + "\"}", 202);
                String registrationCode = inbox.takeCode(OTHER, 22);
                ordinary.login(OTHER, 401);
                ordinary.post("/api/auth/confirm", JSON.writeValueAsString(
                        java.util.Map.of("email", OTHER, "code", registrationCode, "password", PASSWORD)), 201);
                ordinary.login(OTHER, 204);
                ordinary.get("/api/agenda", 200);
                ordinary.get(ADMIN, 403);
                verifiesForumModeration(jdbc, new BrowserSession(origin), owner, ordinary);
                verifiesOptionalMfa(origin, inbox, clock, owner, ownerSeed, ordinary, jdbc, provisioner);
                assertFalse(OwnerProvisioning.provision(provisioner, OTHER, hash, true));
                try (var secondOwner = provisioner.prepareStatement("""
                        INSERT INTO app_owner (account_id) SELECT id FROM accounts WHERE email = ?
                        """)) {
                    secondOwner.setString(1, OTHER);
                    assertEquals("23505", assertThrows(SQLException.class, secondOwner::executeUpdate).getSQLState());
                }
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void verifiesForumModeration(JdbcTemplate jdbc, BrowserSession guest, BrowserSession moderator,
                                               BrowserSession author) throws Exception {
        String forum = "/api/forum";
        String queue = forum + "/moderation/queue";
        String posts = forum + "/posts";
        String reads = forum + "/public/topics";
        String search = reads + "?kind=QUESTION&q=pincel";
        String participation = forum + "/participation";
        String submit = posts + "/" + UUID.randomUUID() + "/submit";
        String content = """
                {"kind":"QUESTION","title":"Como lavar o pincel?","category":"MAKEUP","body":"Pergunta fictícia."}
                """;
        assertTrue(JSON.readTree(moderator.get(participation, 200).body()).path("canModerate").asBoolean());
        assertFalse(JSON.readTree(author.get(participation, 200).body()).path("canModerate").asBoolean());
        guest.get(queue, 401);
        author.get(queue, 403);
        moderator.post(submit, content, 403);
        author.post(submit, content, 403);
        jdbc.update("""
                INSERT INTO billing_accounts (account_id, customer_request_id, subscription_exists, access_until)
                SELECT id, gen_random_uuid(), true, ? FROM accounts WHERE email = ?
                """, TIME.plusSeconds(1).atOffset(ZoneOffset.UTC), OTHER);
        assertTrue(author.post(submit, content.replace("Como lavar o pincel?", " "), 400).at("/errors/title").isString());
        var receipt = author.post(submit, content, 200);
        assertEquals(receipt, author.post(submit, content, 200));
        assertEquals("IN_REVIEW", receipt.path("status").asString());
        var pending = JSON.readTree(moderator.get(queue, 200).body()).at("/pending/0");
        String topicId = pending.path("id").asString();
        assertEquals("Como lavar o pincel?", pending.path("title").asString());
        assertEquals("MAKEUP", pending.path("category").asString());
        String detail = reads + "/" + topicId;
        String thread = forum + "/topics/" + topicId;
        String moderate = forum + "/moderation/topics/" + topicId;
        String topicAction = moderate + "/TOPIC/" + topicId;
        String report = """
                {"reason":"Motivo fictício para revisão."}
                """;
        guest.get(detail, 404);
        assertTrue(JSON.readTree(guest.get(search, 200).body()).path("topics").isEmpty());
        moderator.post(thread + "/reports/TOPIC/" + topicId, report, 404);
        author.post(topicAction + "/APPROVE", "{}", 403);
        moderator.post(topicAction + "/APPROVE", "{}", 204);
        assertEquals("QUESTION", JSON.readTree(guest.get(detail, 200).body()).at("/topic/kind").asString());
        assertEquals(topicId, JSON.readTree(guest.get(search, 200).body()).at("/topics/0/id").asString());
        assertTrue(JSON.readTree(guest.get(reads + "?kind=STORY&q=pincel", 200).body()).path("topics").isEmpty());

        var imageId = jdbc.queryForObject("""
                INSERT INTO forum_images (topic_id, alt_text, jpeg_bytes, status)
                VALUES (?, 'Imagem fictícia', decode('ffd8ffd9', 'hex'), 'IN_REVIEW') RETURNING id
                """, UUID.class, UUID.fromString(topicId));
        String image = detail + "/images/" + imageId;
        author.get(moderate + "/images/" + imageId, 403);
        moderator.get(moderate + "/images/" + imageId, 200);
        guest.get(image, 404);
        moderator.post(moderate + "/IMAGE/" + imageId + "/APPROVE", "{}", 204);
        assertEquals("no-store", guest.get(image, 200).headers().firstValue("Cache-Control").orElseThrow());

        String comment = thread + "/comments/" + UUID.randomUUID();
        String answer = """
                {"body":"Resposta fictícia."}
                """;
        assertEquals("ACTIVE", JSON.readTree(author.get(participation, 200).body()).path("state").asString());
        updateForumAccess(jdbc, TIME);
        author.post(comment, answer, 403);
        updateForumAccess(jdbc, TIME.plusSeconds(1));
        var answerReceipt = author.post(comment, answer, 200);
        assertEquals(answerReceipt, author.post(comment, answer, 200));
        String answerId = answerReceipt.path("id").asString();
        assertTrue(JSON.readTree(guest.get(detail, 200).body()).path("comments").isEmpty());
        moderator.post(moderate + "/COMMENT/" + answerId + "/APPROVE", "{}", 204);
        assertEquals(answerId, JSON.readTree(guest.get(detail, 200).body()).at("/comments/0/id").asString());
        String rejected = author.post(thread + "/comments/" + UUID.randomUUID(), answer, 200).path("id").asString();
        moderator.post(moderate + "/COMMENT/" + rejected + "/REJECT", "{}", 204);
        moderator.post(moderate + "/COMMENT/" + rejected + "/APPROVE", "{}", 409);
        assertEquals(1, JSON.readTree(guest.get(detail, 200).body()).path("comments").size());
        String withdrawAnswer = forum + "/comments/" + answerId + "/withdraw";
        moderator.post(withdrawAnswer, "{}", 404);
        updateForumAccess(jdbc, TIME);
        author.post(withdrawAnswer, "{}", 204);
        assertTrue(JSON.readTree(guest.get(detail, 200).body()).path("comments").isEmpty());

        String reportImage = thread + "/reports/IMAGE/" + imageId;
        guest.post(reportImage, report, 401);
        author.post(reportImage, "{\"reason\":\" \"}", 400);
        author.post(reportImage, report, 204);
        author.post(reportImage, report, 204);
        author.post(thread + "/reports/COMMENT/" + answerId, report, 404);
        author.post(thread + "/reports/TOPIC/" + topicId, report, 204);
        assertEquals(2, JSON.readTree(moderator.get(queue, 200).body()).path("reports").size());
        moderator.post(moderate + "/IMAGE/" + imageId + "/REMOVE", "{}", 204);
        assertEquals("no-store", guest.get(image, 404).headers().firstValue("Cache-Control").orElseThrow());
        var remaining = JSON.readTree(moderator.get(queue, 200).body()).path("reports");
        assertEquals(1, remaining.size());
        String reportId = remaining.get(0).path("id").asString();
        moderator.post(forum + "/moderation/reports/" + reportId + "/close", "{}", 204);
        assertTrue(JSON.readTree(moderator.get(queue, 200).body()).path("reports").isEmpty());
        guest.get(detail, 200);
        moderator.post(topicAction + "/REMOVE", "{}", 204);
        assertEquals("no-store", guest.get(detail, 404).headers().firstValue("Cache-Control").orElseThrow());
        var result = guest.get(search, 200);
        assertEquals("no-store", result.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(JSON.readTree(result.body()).path("topics").isEmpty());
    }

    private static void updateForumAccess(JdbcTemplate jdbc, Instant until) {
        jdbc.update("UPDATE billing_accounts SET access_until = ?", until.atOffset(ZoneOffset.UTC));
    }

    private static void verifiesOptionalMfa(String origin, Inbox inbox, AdjustableClock clock,
                                            BrowserSession owner, byte[] ownerSeed, BrowserSession ordinary,
                                            JdbcTemplate jdbc, java.sql.Connection provisioner) throws Exception {
        String mfa = "/api/auth/mfa";
        String passwordBody = JSON.writeValueAsString(java.util.Map.of("currentPassword", PASSWORD));
        var status = ordinary.get(mfa, 200);
        assertEquals("no-store", status.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(JSON.readTree(status.body()).path("enabled").asBoolean());
        assertFalse(JSON.readTree(status.body()).path("required").asBoolean());
        new BrowserSession(origin).post(mfa + "/setup", passwordBody, 401);
        ordinary.postWithoutCsrf(mfa + "/setup", passwordBody, 403);
        ordinary.post(mfa + "/setup", "{\"currentPassword\":\"wrong\"}", 403);

        var oldSession = new BrowserSession(origin);
        oldSession.login(OTHER, 204);
        ordinary.post(mfa + "/setup", passwordBody, 202);
        var setup = ordinary.post(mfa + "/email/confirm", codeBody(inbox.takeCode(OTHER, 43)), 200);
        byte[] seed = decodeBase32(setup.path("secret").asString());
        ordinary.post(mfa + "/activate", codeBody(AuthSecrets.totp(seed, AuthSecrets.step(clock.instant()))), 204);
        ordinary.get("/api/agenda", 401);
        oldSession.get("/api/agenda", 401);

        clock.advance(301);
        ordinary.login(OTHER, 202);
        ordinary.get("/api/agenda", 401);
        ordinary.post(mfa + "/setup", passwordBody, 401);
        ordinary.post(mfa + "/verify", codeBody(AuthSecrets.totp(seed, AuthSecrets.step(clock.instant()))), 204);
        assertTrue(JSON.readTree(ordinary.get(mfa, 200).body()).path("enabled").asBoolean());
        ordinary.get(ADMIN, 403);
        assertTrue(JSON.readTree(owner.get(mfa, 200).body()).path("required").asBoolean());
        owner.post(mfa + "/disable", JSON.writeValueAsString(java.util.Map.of("currentPassword", PASSWORD,
                "code", AuthSecrets.totp(ownerSeed, AuthSecrets.step(clock.instant())))), 403);
        owner.get(ADMIN, 204);

        clock.advance(301);
        var peer = new BrowserSession(origin);
        peer.login(OTHER, 202);
        peer.post(mfa + "/verify", codeBody(AuthSecrets.totp(seed, AuthSecrets.step(clock.instant()))), 204);
        clock.advance(301);
        ordinary.post("/api/auth/password/reset/request", "{\"email\":\"" + OTHER + "\"}", 202);
        String oldReset = inbox.takeCode(OTHER, 43);
        String resetWithoutFactor = JSON.writeValueAsString(java.util.Map.of(
                "email", OTHER, "code", oldReset, "password", "Quiet orchard! 12345Cc"));
        ordinary.post("/api/auth/password/reset/complete", resetWithoutFactor, 400);
        String factor = AuthSecrets.totp(seed, AuthSecrets.step(clock.instant()));
        String wrongFactor = factor.equals("000000") ? "111111" : "000000";
        ordinary.post(mfa + "/disable", JSON.writeValueAsString(java.util.Map.of(
                "currentPassword", "wrong", "code", factor)), 403);
        ordinary.post(mfa + "/disable", JSON.writeValueAsString(java.util.Map.of(
                "currentPassword", PASSWORD, "code", wrongFactor)), 403);
        ordinary.postWithoutCsrf(mfa + "/disable", passwordBody, 403);
        ordinary.get("/api/agenda", 200);
        String disable = JSON.writeValueAsString(java.util.Map.of("currentPassword", PASSWORD, "code", factor));
        var firstRequest = ordinary.preparePost(mfa + "/disable", disable);
        var secondRequest = peer.preparePost(mfa + "/disable", disable);
        long version = jdbc.queryForObject("SELECT credential_version FROM accounts WHERE email = ?", Long.class, OTHER);
        provisioner.setAutoCommit(false);
        try (var lock = provisioner.prepareStatement("SELECT id FROM accounts WHERE email = ? FOR UPDATE")) {
            lock.setString(1, OTHER);
            try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
            var first = ordinary.client.sendAsync(firstRequest, HttpResponse.BodyHandlers.ofString());
            var second = peer.client.sendAsync(secondRequest, HttpResponse.BodyHandlers.ofString());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                        AND wait_event_type = 'Lock' AND query LIKE '%accounts%FOR UPDATE%'
                    """, Integer.class) < 2) {
                assertTrue(System.nanoTime() < deadline, "Both independent requests must reach the locked row");
                Thread.sleep(10);
            }
            provisioner.commit();
            var statuses = java.util.List.of(first.get(10, TimeUnit.SECONDS).statusCode(), second.get(10, TimeUnit.SECONDS).statusCode());
            assertEquals(1, statuses.stream().filter(code -> code == 204).count());
            assertTrue(statuses.stream().allMatch(code -> code == 204 || code == 401 || code == 403));
        } finally {
            provisioner.rollback();
            provisioner.setAutoCommit(true);
        }
        assertEquals(version + 1, jdbc.queryForObject("SELECT credential_version FROM accounts WHERE email = ?", Long.class, OTHER));
        ordinary.get("/api/agenda", 401);
        peer.get("/api/agenda", 401);
        ordinary.login(OTHER, 204);
        assertFalse(JSON.readTree(ordinary.get(mfa, 200).body()).path("enabled").asBoolean());
        ordinary.post("/api/auth/password/reset/complete", resetWithoutFactor, 400);

        clock.advance(301);
        ordinary.post(mfa + "/setup", passwordBody, 202);
        var cancelled = ordinary.post(mfa + "/email/confirm", codeBody(inbox.takeCode(OTHER, 43)), 200);
        assertNotEquals(setup.path("secret").asString(), cancelled.path("secret").asString());
        assertNotEquals(setup.path("recovery").asString(), cancelled.path("recovery").asString());
        ordinary.post(mfa + "/cancel", "{}", 204);
        ordinary.get("/api/agenda", 200);
        ordinary.post(mfa + "/activate", codeBody(AuthSecrets.totp(
                decodeBase32(cancelled.path("secret").asString()), AuthSecrets.step(clock.instant()))), 401);
        assertFalse(JSON.readTree(ordinary.get(mfa, 200).body()).path("enabled").asBoolean());

        clock.advance(301);
        String changedPassword = "Different meadow! 97531Aa";
        ordinary.post("/api/auth/password/change", JSON.writeValueAsString(java.util.Map.of(
                "currentPassword", PASSWORD, "password", changedPassword)), 204);
        ordinary.login(OTHER, 401);
        ordinary.login(OTHER, changedPassword, 204);
        ordinary.get("/api/agenda", 200);
        ordinary.post("/api/auth/password/reset/request", "{\"email\":\"" + OTHER + "\"}", 202);
        String reset = inbox.takeCode(OTHER, 43);
        String recoveredPassword = "Calm valley! 86420Bb";
        ordinary.post("/api/auth/password/reset/complete", JSON.writeValueAsString(java.util.Map.of(
                "email", OTHER, "code", reset, "password", recoveredPassword)), 204);
        ordinary.login(OTHER, recoveredPassword, 204);
        ordinary.get("/api/agenda", 200);
        ordinary.get(ADMIN, 403);
    }

    private static byte[] enroll(BrowserSession session, Inbox inbox, String address) throws Exception {
        session.post("/api/auth/mfa/email", "{\"email\":\"not-the-recipient@example.test\"}", 202);
        var setup = session.post("/api/auth/mfa/email/confirm", codeBody(inbox.takeCode(address, 43)), 200);
        byte[] seed = decodeBase32(setup.path("secret").asString());
        session.post("/api/auth/mfa/activate", codeBody(AuthSecrets.totp(seed, AuthSecrets.step(TIME))), 204);
        return seed;
    }

    private static String codeBody(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    private static byte[] decodeBase32(String encoded) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        byte[] decoded = new byte[encoded.length() * 5 / 8];
        int bits = 0;
        int buffer = 0;
        int offset = 0;
        for (char character : encoded.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(character);
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                decoded[offset++] = (byte) (buffer >> bits);
            }
        }
        return decoded;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InboxConfiguration {
        @Bean
        @Primary
        AdjustableClock testClock() {
            return new AdjustableClock();
        }

        @Bean
        Inbox inbox() {
            return new Inbox();
        }
    }

    static final class AdjustableClock extends Clock {
        private volatile Instant now = TIME;

        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }

    static final class Inbox implements MailSender {
        private final LinkedBlockingQueue<SimpleMailMessage> messages = new LinkedBlockingQueue<>();

        @Override
        public void send(SimpleMailMessage message) {
            messages.add(message);
        }

        @Override
        public void send(SimpleMailMessage... messages) {
            for (var message : messages) {
                send(message);
            }
        }

        String takeCode(String recipient, int length) throws InterruptedException {
            var message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message);
            assertEquals(recipient, message.getTo()[0]);
            return message.getText().lines().filter(line -> line.length() == length && line.matches("[A-Za-z0-9_-]+"))
                    .findFirst().orElseThrow();
        }
    }

    private static final class BrowserSession {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        private final String origin;

        BrowserSession(String origin) {
            this.origin = origin;
        }

        void login(String identifier, int expected) throws Exception {
            login(identifier, PASSWORD, expected);
        }

        void login(String identifier, String password, int expected) throws Exception {
            post("/api/auth/login", "username=" + URLEncoder.encode(identifier, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), "application/x-www-form-urlencoded", expected);
        }

        void postWithoutCsrf(String path, String body, int expected) throws Exception {
            send(request(path).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)), expected);
        }

        JsonNode post(String path, String body, int expected) throws Exception {
            return post(path, body, "application/json", expected);
        }

        HttpRequest preparePost(String path, String body) throws Exception {
            var csrf = JSON.readTree(get("/api/auth/csrf", 200).body());
            return request(path).header(csrf.path("headerName").asString(), csrf.path("token").asString())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        }

        private JsonNode post(String path, String body, String type, int expected) throws Exception {
            var csrf = JSON.readTree(get("/api/auth/csrf", 200).body());
            var response = send(request(path).header(csrf.path("headerName").asString(), csrf.path("token").asString())
                    .header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body)), expected);
            return response.body().isEmpty() ? null : JSON.readTree(response.body());
        }

        HttpResponse<String> get(String path, int expected) throws Exception {
            return send(request(path).GET(), expected);
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create(origin + path));
        }

        private HttpResponse<String> send(HttpRequest.Builder request, int expected) throws Exception {
            var built = request.build();
            var response = client.send(built, HttpResponse.BodyHandlers.ofString());
            assertEquals(expected, response.statusCode(), built.method() + " " + built.uri().getPath());
            return response;
        }
    }
}
