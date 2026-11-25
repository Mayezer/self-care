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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import br.com.autocuidado.SelfCareApplication;
import br.com.autocuidado.vanity.MakeupCleaning;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgresql")
class AgendaPostgresqlTest {

    private static final String AGENDA = "/api/agenda/week";
    private static final String DAILY_MESSAGE = "/api/agenda/daily-message";
    private static final String REMINDERS = "/api/agenda/reminder-preferences";
    private static final String HISTORY = "/api/history";
    private static final String SKIN = "/api/occurrences/skin";
    private static final String SKIN_PLANS = "/api/skin-plans";
    private static final String MAKEUP = "/api/occurrences/makeup";
    private static final String NAILS = "/api/occurrences/nails";
    private static final String PASSWORD = "Local-test-321";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void servesOnlyApprovedForumContentForVisitorsAndBothAccountPlans() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "e310_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var path = "/api/forum/public/topics";

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                UUID originalId = UUID.randomUUID();
                try (var previous = new SpringApplicationBuilder(SelfCareApplication.class).web(WebApplicationType.NONE)
                        .run("--spring.datasource.url=" + url, "--spring.flyway.target=18",
                                "--spring.jpa.hibernate.ddl-auto=none")) {
                    var jdbc = previous.getBean(JdbcTemplate.class);
                    var hash = previous.getBean(PasswordEncoder.class).encode(PASSWORD);
                    long free = insertAccount(jdbc, "forum_free", hash, "America/Sao_Paulo");
                    long paid = insertAccount(jdbc, "forum_paid", hash, "America/Sao_Paulo");
                    jdbc.update("""
                            INSERT INTO billing_accounts (account_id, customer_request_id, subscription_exists, access_until)
                            VALUES (?, gen_random_uuid(), true, now() + interval '7 days')
                            """, paid);
                    jdbc.update("""
                            INSERT INTO forum_posts (account_id, id, kind, category, body, status, accepted_at, accepted_week)
                            VALUES (?, ?, 'STORY', 'MAKEUP', 'Relato público preservado', 'PUBLISHED', now(), current_date)
                            """, free, originalId);
                }
                try (var app = new SpringApplicationBuilder(SelfCareApplication.class)
                        .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                                "--app.forum.public-reading-enabled=true")) {
                    var jdbc = app.getBean(JdbcTemplate.class);
                    var owner = jdbc.queryForObject("SELECT id FROM accounts WHERE username = 'forum_free'", Long.class);
                    var migrated = jdbc.queryForObject("SELECT public_id FROM forum_posts WHERE id = ?", UUID.class, originalId);
                    assertNotEquals(originalId, migrated);
                    var visible = addForumTopic(jdbc, owner, "PUBLISHED", "Qual pincel usar?", 14);
                    var pending = addForumTopic(jdbc, owner, "IN_REVIEW", "Segredo pendente", 21);
                    var rejected = addForumTopic(jdbc, owner, "REJECTED", "Segredo recusado", 28);
                    var removed = addForumTopic(jdbc, owner, "REMOVED", "Segredo removido", 35);
                    var draft = jdbc.queryForObject("""
                            INSERT INTO forum_posts (account_id, id, kind, body, status)
                            VALUES (?, gen_random_uuid(), 'STORY', 'Segredo rascunho', 'DRAFT') RETURNING public_id
                            """, UUID.class, owner);
                    for (String state : List.of("PUBLISHED", "IN_REVIEW", "REJECTED", "REMOVED")) {
                        jdbc.update("""
                                INSERT INTO forum_comments (topic_id, account_id, body, status) VALUES (?, ?, ?, ?)
                                """, visible, owner, "Resposta " + state, state);
                    }
                    var jpeg = new java.io.ByteArrayOutputStream();
                    assertTrue(javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2,
                            java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", jpeg));
                    var approvedImage = addForumImage(jdbc, visible, "PUBLISHED", jpeg.toByteArray());
                    var pendingImage = addForumImage(jdbc, visible, "IN_REVIEW", jpeg.toByteArray());
                    var rejectedImage = addForumImage(jdbc, visible, "REJECTED", jpeg.toByteArray());
                    var removedImage = addForumImage(jdbc, visible, "REMOVED", jpeg.toByteArray());
                    var hiddenTopicImage = addForumImage(jdbc, pending, "PUBLISHED", jpeg.toByteArray());

                    var guest = new Session(origin(app));
                    var free = new Session(origin(app));
                    var paid = new Session(origin(app));
                    guest.get("/api/agenda", 401);
                    free.login("forum_free");
                    paid.login("forum_paid");
                    for (var reader : List.of(guest, free, paid)) {
                        var response = reader.send(reader.request(path).GET().build());
                        assertEquals(200, response.statusCode());
                        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                        var list = JSON.readTree(response.body()).path("topics");
                        assertEquals(2, list.size());
                        assertEquals("Relato público preservado", list.get(0).path("body").asString());
                        assertFalse(list.get(0).has("accountId"));
                        assertFalse(list.get(0).has("status"));
                        assertEquals(visible.toString(), JSON.readTree(reader.get(path + "?q=PINCEL", 200))
                                .at("/topics/0/id").asString());
                        assertTrue(JSON.readTree(reader.get(path + "?q=segredo", 200)).path("topics").isEmpty());
                        assertTrue(JSON.readTree(reader.get(path + "?q=%25", 200)).path("topics").isEmpty());
                        var detail = JSON.readTree(reader.get(path + "/" + visible, 200));
                        assertEquals("QUESTION", detail.at("/topic/kind").asString());
                        assertEquals(1, detail.path("comments").size());
                        assertEquals("Resposta PUBLISHED", detail.at("/comments/0/body").asString());
                        assertEquals(1, detail.path("images").size());
                        assertEquals(approvedImage.toString(), detail.at("/images/0/id").asString());
                        for (var id : List.of(pending, rejected, removed, draft, originalId, UUID.randomUUID())) {
                            reader.get(path + "/" + id, 404);
                        }
                        for (var id : List.of(pendingImage, rejectedImage, removedImage, hiddenTopicImage)) {
                            reader.get(path + "/" + visible + "/images/" + id, 404);
                        }
                        reader.get(path + "/" + pending + "/images/" + hiddenTopicImage, 404);
                        var image = reader.send(reader.request(path + "/" + visible + "/images/" + approvedImage)
                                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
                        assertEquals(200, image.statusCode());
                        assertEquals("image/jpeg", image.headers().firstValue("Content-Type").orElseThrow());
                        assertEquals("no-store", image.headers().firstValue("Cache-Control").orElseThrow());
                    }
                    var img = HttpClient.newHttpClient().send(guest.request(path + "/" + visible + "/images/" + approvedImage)
                            .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(200, img.statusCode());
                    org.junit.jupiter.api.Assertions.assertArrayEquals(jpeg.toByteArray(), img.body());
                    paid.post("/api/forum/posts/" + UUID.randomUUID() + "/submit", "{}", true, 404);
                    jdbc.update("UPDATE forum_posts SET status = 'REMOVED' WHERE public_id = ?", visible);
                    guest.get(path + "/" + visible, 404);
                    guest.get(path + "/" + visible + "/images/" + approvedImage, 404);
                    assertTrue(JSON.readTree(guest.get(path + "?q=pincel", 200)).path("topics").isEmpty());
                }
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static UUID addForumTopic(JdbcTemplate jdbc, long owner, String status, String title, int daysAgo) {
        return jdbc.queryForObject("""
                INSERT INTO forum_posts (account_id, id, kind, category, title, body, status, accepted_at)
                VALUES (?, gen_random_uuid(), 'QUESTION', 'MAKEUP', ?, 'Corpo de teste', ?, now() - make_interval(days => ?))
                RETURNING public_id
                """, UUID.class, owner, title, status, daysAgo);
    }

    private static UUID addForumImage(JdbcTemplate jdbc, UUID topic, String status, byte[] jpeg) {
        return jdbc.queryForObject("""
                INSERT INTO forum_images (topic_id, alt_text, jpeg_bytes, status)
                VALUES (?, 'Imagem fictícia', ?, ?) RETURNING id
                """, UUID.class, topic, jpeg, status);
    }

    @Test
    void enforcesPaidForumParticipationAndSharedDailyQuotaAcrossSessions() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "e311_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String path = "/api/forum/posts";
        String participation = "/api/forum/participation";
        String review = """
                {"kind":"REVIEW","category":"SKIN","body":"Relato fictício","product":"Produto de teste","rating":5}
                """;
        String story = """
                {"kind":"QUESTION","category":"HAIR","title":"Qual rotina?","body":"Rotina fictícia"}
                """;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                try (var previous = new SpringApplicationBuilder(SelfCareApplication.class).web(WebApplicationType.NONE)
                        .run("--spring.datasource.url=" + url, "--spring.flyway.target=8",
                                "--spring.jpa.hibernate.ddl-auto=none")) {
                    var jdbc = previous.getBean(JdbcTemplate.class);
                    var hash = previous.getBean(PasswordEncoder.class).encode(PASSWORD);
                    insertAccount(jdbc, "forum_owner", hash, "Asia/Tokyo");
                    insertAccount(jdbc, "forum_other", hash, "America/Sao_Paulo");
                }
                try (var app = new SpringApplicationBuilder(SelfCareApplication.class, MutableClockConfiguration.class)
                        .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                                "--app.forum.enabled=true", "--app.forum.public-reading-enabled=true")) {
                    var jdbc = app.getBean(JdbcTemplate.class);
                    var time = app.getBean(MutableTime.class).instant();
                    var expires = Instant.parse("2026-11-10T02:59:59Z");
                    time.set(expires.minusSeconds(1));
                    var topic = jdbc.queryForObject("""
                            INSERT INTO forum_posts (account_id, id, kind, category, body, status, accepted_at)
                            SELECT id, gen_random_uuid(), 'STORY', 'HAIR', 'Tema fictício', 'PUBLISHED', ?
                            FROM accounts WHERE username = 'forum_other' RETURNING public_id
                            """, UUID.class, expires.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC));
                    var publicPath = "/api/forum/public/topics/" + topic;
                    var commentPath = "/api/forum/topics/" + topic + "/comments/";
                    var commentId = UUID.randomUUID();
                    var comment = """
                            {"body":"Comentário fictício"}
                            """;
                    var guest = new Session(origin(app));
                    var owner = new Session(origin(app));
                    var device = new Session(origin(app));
                    var other = new Session(origin(app));
                    var draft = path + "/" + UUID.randomUUID();
                    var second = path + "/" + UUID.randomUUID();
                    guest.get(path, 401);
                    guest.post(draft + "/submit", story, false, 401);
                    guest.post(draft + "/submit", story, true, 401);
                    guest.post(commentPath + commentId, comment, true, 401);
                    assertEquals("VISITOR", JSON.readTree(guest.get(participation, 200)).path("state").asString());
                    owner.login("forum_owner");
                    device.login("forum_owner");
                    other.login("forum_other");
                    assertEquals("INACTIVE", JSON.readTree(other.get(participation, 200)).path("state").asString());
                    assertEquals("SUBSCRIPTION_REQUIRED", JSON.readTree(other.post(draft + "/submit", story, true, 403))
                            .path("code").asString());
                    other.post(commentPath + commentId, comment, true, 403);
                    other.put(draft, review, true, 403);
                    jdbc.update("""
                            INSERT INTO billing_accounts (account_id, customer_request_id, subscription_exists,
                                access_until, cancel_at_period_end)
                            SELECT id, gen_random_uuid(), true, ?, true FROM accounts WHERE username = 'forum_owner'
                            """, expires.atOffset(ZoneOffset.UTC));
                    assertEquals("ACTIVE", JSON.readTree(owner.get(participation, 200)).path("state").asString());
                    assertEquals(guest.get(publicPath, 200), other.get(publicPath, 200));
                    owner.put(draft, review, false, 403);
                    for (String invalid : List.of("0", "6", "1.5", "5.0", "\"5\"")) {
                        assertTrue(JSON.readTree(owner.post(draft + "/submit", review.replace("\"rating\":5",
                                "\"rating\":" + invalid), true, 400)).at("/errors/rating").isString());
                    }
                    assertEquals("DRAFT", owner.put(draft, "{\"kind\":\"REVIEW\"}", true, 200).path("status").asString());
                    assertEquals("DRAFT", owner.put(draft, review, true, 200).path("status").asString());
                    assertEquals(1, JSON.readTree(other.get(path, 200)).path("posts").size());
                    assertTrue(JSON.readTree(owner.get(path, 200)).path("nextAvailableAt").isNull());
                    time.set(expires);
                    owner.post(draft + "/submit", review, true, 403);
                    owner.post(commentPath + commentId, comment, true, 403);
                    assertEquals("DRAFT", JSON.readTree(owner.get(path, 200)).at("/posts/0/status").asString());
                    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM forum_comments", Integer.class));
                    jdbc.update("UPDATE billing_accounts SET access_until = ?, cancel_at_period_end = false",
                            expires.plus(Duration.ofDays(7)).atOffset(ZoneOffset.UTC));
                    assertEquals("ACTIVE", JSON.readTree(owner.get(participation, 200)).path("state").asString());
                    var firstRequest = owner.postRequest(draft + "/submit", review, true);
                    var secondRequest = device.postRequest(second + "/submit", story, true);
                    HttpResponse<String> firstResponse;
                    HttpResponse<String> secondResponse;
                    var gate = new CyclicBarrier(2);
                    try (var workers = Executors.newFixedThreadPool(2)) {
                        var first = workers.submit(() -> { gate.await(5, TimeUnit.SECONDS); return owner.send(firstRequest); });
                        var retry = workers.submit(() -> { gate.await(5, TimeUnit.SECONDS); return device.send(secondRequest); });
                        firstResponse = first.get(15, TimeUnit.SECONDS);
                        secondResponse = retry.get(15, TimeUnit.SECONDS);
                    }
                    assertEquals(Set.of(200, 429), new HashSet<>(List.of(firstResponse.statusCode(), secondResponse.statusCode())));
                    boolean reviewWon = firstResponse.statusCode() == 200;
                    var accepted = JSON.readTree((reviewWon ? firstResponse : secondResponse).body());
                    var limited = JSON.readTree((reviewWon ? secondResponse : firstResponse).body());
                    var winner = reviewWon ? draft : second;
                    var loser = reviewWon ? second : draft;
                    var winningText = reviewWon ? review : story;
                    var losingText = reviewWon ? story : review;
                    assertEquals("2026-11-10T00:00:00-03:00", limited.path("nextAvailableAt").asString());
                    assertEquals("IN_REVIEW", accepted.path("status").asString());
                    assertEquals(1, JSON.readTree(other.get(path, 200)).path("posts").size());
                    var receipt = JSON.readTree(owner.post(commentPath + commentId, comment, true, 200));
                    assertEquals(receipt, JSON.readTree(device.post(commentPath + commentId, comment, true, 200)));
                    assertTrue(JSON.readTree(guest.get(publicPath, 200)).path("comments").isEmpty());
                    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM forum_comments WHERE status = 'IN_REVIEW'", Integer.class));
                    for (int i = 0; i < 9; i++) owner.post(commentPath + UUID.randomUUID(), comment, true, 200);
                    owner.post(commentPath + UUID.randomUUID(), comment, true, 429);
                    assertEquals(receipt, JSON.readTree(device.post(commentPath + commentId, comment, true, 200)));
                    other.post(winner + "/withdraw", null, true, 404);
                    owner.post(winner + "/withdraw", null, true, 204);
                    owner.post(loser + "/submit", losingText, true, 429);
                    time.set(expires.plusSeconds(1));
                    assertEquals(accepted, JSON.readTree(owner.post(winner + "/submit", winningText, true, 200)));
                    assertTrue(JSON.readTree(owner.get(path, 200)).path("nextAvailableAt").isNull());
                    assertEquals("IN_REVIEW", JSON.readTree(owner.post(loser + "/submit", losingText, true, 200))
                            .path("status").asString());
                    assertEquals(2, JSON.readTree(owner.get(path, 200)).path("posts").size());
                    assertEquals(200, owner.send(owner.request("/api/agenda").GET().build()).statusCode());
                }
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }


    @Test
    void schedulesCleaningByCivilDateAndKeepsCompletedOccurrencesThroughPauseAndResume() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "e308_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String path = "/api/makeup-cleaning";
        String brushes = path + "/BRUSHES";
        String sponges = path + "/SPONGES";
        String create = """
                {"revision":0,"startDate":"2026-11-30","intervalDays":14}
                """;
        String edit = """
                {"revision":1,"startDate":"2026-12-14","intervalDays":10}
                """;
        String pause = """
                {"revision":2}
                """;
        String resume = """
                {"revision":3,"startDate":"2026-12-24","intervalDays":10}
                """;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, MutableClockConfiguration.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                            "--app.agenda.reminders-enabled=true")) {
                var clock = app.getBean(MutableTime.class).instant();
                clock.set(Instant.parse("2026-12-01T01:00:00Z"));
                var jdbc = app.getBean(JdbcTemplate.class);
                var hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                insertAccount(jdbc, "cleaning_sp", hash, "America/Sao_Paulo");
                insertAccount(jdbc, "cleaning_utc", hash, "America/Sao_Paulo");
                var owner = new Session(origin(app));
                var other = new Session(origin(app));
                owner.get(path, 401);
                owner.login("cleaning_sp");
                other.login("cleaning_utc");
                var empty = JSON.readTree(owner.get(path, 200));
                assertEquals(14, empty.path("defaultInterval").asInt());
                assertEquals("2026-11-30", empty.path("today").asString());
                assertTrue(empty.path("schedules").isEmpty());
                assertEquals("2026-11-30", JSON.readTree(other.get(path, 200)).path("today").asString());
                other.put(brushes, create.replace("2026-11-30", "2026-11-29"), true, 400);
                owner.put(brushes, create, false, 403);
                owner.put(brushes, create.replace("14", "0"), true, 400);
                owner.put(brushes, create.replace("14", "14.5"), true, 400);
                owner.put(brushes, create.replace("{", "{\"accountId\":1,"), true, 400);
                assertEquals(empty, JSON.readTree(owner.get(path, 200)));

                var retry = new Session(origin(app));
                retry.login("cleaning_sp");
                var startAtOnce = new CyclicBarrier(2);
                try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                    var first = workers.submit(() -> {
                        startAtOnce.await(5, TimeUnit.SECONDS);
                        return owner.put(brushes, create, true, 200);
                    });
                    var second = workers.submit(() -> {
                        startAtOnce.await(5, TimeUnit.SECONDS);
                        return retry.put(brushes, create, true, 200);
                    });
                    var saved = first.get(15, TimeUnit.SECONDS);
                    assertEquals(1, saved.at("/schedules/0/revision").asInt());
                    assertEquals(saved, second.get(15, TimeUnit.SECONDS));
                }
                owner.put(brushes, create.replace("14", "12"), true, 409);
                var both = owner.put(sponges, create, true, 200);
                assertEquals(2, both.path("schedules").size());
                assertTrue(JSON.readTree(other.get(path, 200)).path("schedules").isEmpty());
                other.post(brushes + "/pause", pause, true, 409);

                var week = owner.week("");
                assertEquals(2, week.path("items").size());
                long firstBrush = week.at("/items/0/id").asLong();
                assertFalse(week.at("/items/0/canReschedule").asBoolean());
                assertTrue(week.at("/items/0/reminderTime").isNull());
                assertEquals("Limpeza de pincéis", week.at("/items/0/title").asString());
                other.action(firstBrush, "skip", null, true, 404);
                owner.action(firstBrush, "reschedule", plannedOn("2026-12-02"), true, 409);

                String prefs = """
                        {"enabled":true,"categories":["MAKEUP"],"time":"08:30","timeZone":"America/Sao_Paulo"}
                        """;
                owner.put(REMINDERS, prefs, true, 200);
                assertEquals("08:30", owner.week("").at("/items/0/reminderTime").asString());
                assertEquals(2, owner.week("?date=2026-12-14").path("items").size());
                assertEquals("2026-12-14", owner.week("?date=2026-12-14").at("/items/0/date").asString());
                long nextOriginal = owner.week("?date=2026-12-14").at("/items/0/id").asLong();
                assertEquals(1L, pushCandidates(jdbc, nextOriginal));

                // Complete the first brush cleaning two days late; the next date remains anchored to Nov 30.
                clock.set(Instant.parse("2026-12-02T16:00:00Z"));
                owner.login("cleaning_sp");
                owner.action(firstBrush, "complete", "{\"completedOn\":\"2026-12-02\"}", true, 204);
                var history = JSON.readTree(owner.get(HISTORY, 200));
                assertEquals(firstBrush, history.at("/0/id").asLong());
                assertEquals("2026-12-14", JSON.readTree(owner.get(path, 200)).at("/schedules/0/nextDate").asString());

                var changed = owner.put(brushes, edit, true, 200);
                assertEquals(changed, owner.put(brushes, edit, true, 200));
                var editedWeek = owner.week("?date=2026-12-24");
                long movedBrush = editedWeek.at("/items/0/id").asLong();
                assertEquals("2026-12-24", editedWeek.at("/items/0/date").asString());
                assertEquals(1L, pushCandidates(jdbc, movedBrush));

                var paused = JSON.readTree(owner.post(brushes + "/pause", pause, true, 200));
                assertEquals(paused, JSON.readTree(owner.post(brushes + "/pause", pause, true, 200)));
                assertFalse(paused.at("/schedules/0/active").asBoolean());
                assertTrue(paused.at("/schedules/1/active").asBoolean());
                assertEquals(0L, pushCandidates(jdbc, movedBrush));
                assertEquals(0L, pushCandidates(jdbc, nextOriginal));
                owner.action(movedBrush, "skip", null, true, 404);
                assertEquals(history, JSON.readTree(owner.get(HISTORY, 200)));
                assertFalse(owner.week("?date=2026-12-24").path("items").valueStream()
                        .anyMatch(item -> item.path("title").asString().equals("Limpeza de pincéis")));

                var resumed = owner.put(brushes, resume, true, 200);
                assertEquals(resumed, owner.put(brushes, resume, true, 200));
                owner.post(brushes + "/pause", pause, true, 409);
                assertEquals(movedBrush, owner.week("?date=2026-12-24").at("/items/0/id").asLong());
                assertTrue(hasCleaning(owner.week("?date=2027-01-03"), "Limpeza de pincéis", "2027-01-03"));
                assertEquals(1L, pushCandidates(jdbc, movedBrush));
                owner.put(REMINDERS, prefs.replace("MAKEUP", "SKIN"), true, 200);
                assertEquals(0L, pushCandidates(jdbc, movedBrush));
                assertTrue(owner.week("?date=2026-12-24").at("/items/0/reminderTime").isNull());
                assertEquals(history, JSON.readTree(owner.get(HISTORY, 200)));

                // Extend the schedule without a request from the user, then retry the refresh.
                clock.set(Instant.parse("2027-03-05T02:00:00Z"));
                var service = app.getBean(MakeupCleaning.class);
                service.refresh();
                service.refresh();
                owner.login("cleaning_sp");
                assertEquals("2027-03-04", JSON.readTree(owner.get(path, 200)).path("today").asString());
                assertEquals("2027-03-04", JSON.readTree(owner.get(path, 200)).at("/schedules/0/nextDate").asString());
                assertTrue(hasCleaning(owner.week("?date=2027-04-03"), "Limpeza de pincéis", "2027-04-03"));
                assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM makeup_cleaning_series", Long.class));
                assertEquals(0L, jdbc.queryForObject("""
                        SELECT count(*) FROM (SELECT cleaning_series_id, planned_date FROM care_occurrences
                        WHERE cleaning_series_id IS NOT NULL GROUP BY 1, 2 HAVING count(*) > 1) duplicates
                        """, Long.class));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static long pushCandidates(JdbcTemplate jdbc, long occurrence) {
        return jdbc.queryForObject("SELECT count(*) FROM web_push_reminders WHERE occurrence_id = ?", Long.class, occurrence);
    }

    private static boolean hasCleaning(JsonNode week, String title, String date) {
        return week.path("items").valueStream().anyMatch(item ->
                item.path("title").asString().equals(title) && item.path("date").asString().equals(date));
    }

    @Test
    void keepsAgendaRemindersInSyncWithPreferencesAndPlannedCare() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "e209_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String active = """
                {"enabled":true,"categories":["SKIN","MAKEUP"],"time":"08:30","timeZone":"America/Sao_Paulo"}
                """;
        String edited = """
                {"enabled":true,"categories":["SKIN"],"time":"19:15","timeZone":"America/Sao_Paulo"}
                """;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                long owner;
                try (var previous = new SpringApplicationBuilder(SelfCareApplication.class).web(WebApplicationType.NONE)
                        .run("--spring.datasource.url=" + url, "--spring.flyway.target=6",
                                "--spring.jpa.hibernate.ddl-auto=none")) {
                    var jdbc = previous.getBean(JdbcTemplate.class);
                    var hash = previous.getBean(PasswordEncoder.class).encode(PASSWORD);
                    owner = insertAccount(jdbc, "reminder_owner", hash, "America/Sao_Paulo");
                    insertAccount(jdbc, "reminder_other", hash, "America/Sao_Paulo");
                }

                long skin;
                try (var app = startReminderApp(url, true)) {
                    var jdbc = app.getBean(JdbcTemplate.class);
                    assertEquals(1, jdbc.queryForObject(
                            "SELECT count(*) FROM flyway_schema_history WHERE version = '7' AND success", Integer.class));
                    var session = new Session(origin(app));
                    var device = new Session(origin(app));
                    var other = new Session(origin(app));
                    session.get(REMINDERS, 401);
                    session.put(REMINDERS, active, true, 401);
                    session.login("reminder_owner");
                    device.login("reminder_owner");
                    other.login("reminder_other");
                    var defaults = JSON.readTree(session.get(REMINDERS, 200));
                    assertFalse(defaults.path("enabled").asBoolean());
                    assertTrue(defaults.path("categories").isEmpty());
                    assertTrue(defaults.path("time").isNull());
                    assertEquals("America/Sao_Paulo", defaults.path("timeZone").asString());
                    session.put(REMINDERS, active, false, 403);
                    assertTrue(other.put(REMINDERS, """
                            {"accountId":%d,"enabled":false,"categories":[],"time":null,"timeZone":"America/Sao_Paulo"}
                            """.formatted(owner), true, 400).at("/errors/body").isString());
                    var errors = session.put(REMINDERS, """
                            {"enabled":true,"categories":[],"time":"24:00","timeZone":"+03:00"}
                            """, true, 400).path("errors");
                    for (String field : List.of("categories", "time", "timeZone")) {
                        assertTrue(errors.path(field).isString(), field);
                    }
                    assertEquals(defaults, JSON.readTree(session.get(REMINDERS, 200)));

                    skin = JSON.readTree(session.post(SKIN, """
                            {"title":"Cuidado com lembrete","plannedDate":"2026-11-09"}
                            """, true, 201)).path("id").asLong();
                    assertTrue(item(session.week(""), skin).path("reminderTime").isNull());
                    assertEquals("08:30", session.put(REMINDERS, active, true, 200).path("time").asString());
                    assertEquals("08:30", session.week("").at("/nextCare/reminderTime").asString());
                    long makeup = JSON.readTree(session.post(MAKEUP, """
                            {"title":"Maquiagem com lembrete","plannedDate":"2026-11-09"}
                            """, true, 201)).path("id").asLong();
                    long nails = JSON.readTree(session.post(NAILS, """
                            {"title":"Unhas sem lembrete","plannedDate":"2026-11-11"}
                            """, true, 201)).path("id").asLong();
                    assertEquals("08:30", item(device.week(""), makeup).path("reminderTime").asString());
                    assertTrue(item(session.week(""), nails).path("reminderTime").isNull());
                    assertFalse(JSON.readTree(other.get(REMINDERS, 200)).path("enabled").asBoolean());
                    assertTrue(other.week("").path("items").isEmpty());

                    session.action(skin, "reschedule", "{\"plannedDate\":\"2026-11-11\"}", true, 204);
                    var moved = item(session.week(""), skin);
                    assertEquals("2026-11-11", moved.path("date").asString());
                    assertEquals("08:30", moved.path("reminderTime").asString());
                    session.action(skin, "reschedule", plannedOn("2026-11-09"), true, 204);
                    session.action(skin, "complete", "{\"completedOn\":\"2026-11-09\"}", true, 204);
                    assertTrue(item(session.week(""), skin).path("reminderTime").isNull());
                    session.action(skin, "undo", null, true, 204);
                    session.action(skin, "reschedule", plannedOn("2026-11-11"), true, 204);
                    assertEquals("08:30", item(session.week(""), skin).path("reminderTime").asString());
                    session.action(skin, "skip", null, true, 204);
                    assertTrue(item(session.week(""), skin).path("reminderTime").isNull());
                    session.action(skin, "undo", null, true, 204);

                    long plan = JSON.readTree(session.post(SKIN_PLANS, """
                            {"title":"Rotina de lembrete","startDate":"2026-11-09","weeks":1,
                             "weekdays":["TUESDAY","THURSDAY"],"requestId":"%s"}
                            """.formatted(UUID.randomUUID()), true, 201)).path("planId").asLong();
                    assertEquals(2, session.week("").path("items").valueStream()
                            .filter(item -> item.path("planId").asLong() == plan)
                            .filter(item -> item.path("reminderTime").asString().equals("08:30")).count());
                    session.post(SKIN_PLANS + "/" + plan + "/close", null, true, 204);
                    assertFalse(session.week("").path("items").valueStream()
                            .anyMatch(item -> item.path("planId").asLong() == plan));

                    session.put(REMINDERS, active.replace("\"SKIN\",", ""), true, 200);
                    assertTrue(item(session.week(""), skin).path("reminderTime").isNull());
                    assertEquals("08:30", item(session.week(""), makeup).path("reminderTime").asString());
                    session.put(REMINDERS, edited, true, 200);
                    app.getBean(MutableTime.class).instant().set(Instant.parse("2026-11-10T03:00:00Z"));
                    var week = device.week("");
                    assertEquals("2026-11-10", week.path("today").asString());
                    assertEquals("America/Sao_Paulo", JSON.readTree(device.get("/api/agenda", 200)).path("timeZone").asString());
                    assertEquals("2026-11-11", item(week, skin).path("date").asString());
                    assertEquals("19:15", item(week, skin).path("reminderTime").asString());
                    var overdue = item(week, "overdue", makeup);
                    assertEquals("2026-11-09", overdue.path("date").asString());
                    assertTrue(overdue.path("reminderTime").isNull());
                    assertEquals("2026-11-10", other.week("").path("today").asString());

                    var disabled = session.put(REMINDERS, edited.replace("true", "false"), true, 200);
                    assertFalse(disabled.path("enabled").asBoolean());
                    assertEquals("19:15", disabled.path("time").asString());
                    assertTrue(item(device.week(""), skin).path("reminderTime").isNull());
                    session.put(REMINDERS, edited, true, 200);
                }
                try (var disabled = startReminderApp(url, false)) {
                    var session = new Session(origin(disabled));
                    session.login("reminder_owner");
                    session.get(REMINDERS, 404);
                    session.put(REMINDERS, active, true, 404);
                    assertTrue(item(session.week(""), skin).path("reminderTime").isNull());
                    assertEquals("America/Sao_Paulo", JSON.readTree(session.get("/api/agenda", 200)).path("timeZone").asString());
                }
                try (var restarted = startReminderApp(url, true)) {
                    var session = new Session(origin(restarted));
                    session.login("reminder_owner");
                    assertTrue(JSON.readTree(session.get(REMINDERS, 200)).path("enabled").asBoolean());
                    assertEquals("19:15", item(session.week(""), skin).path("reminderTime").asString());
                }
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static ConfigurableApplicationContext startReminderApp(String url, boolean enabled) {
        var app = new SpringApplicationBuilder(SelfCareApplication.class, MutableClockConfiguration.class)
                .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                        "--app.agenda.reminders-enabled=" + enabled);
        app.getBean(MutableTime.class).instant().set(Instant.parse("2026-11-10T01:00:00Z"));
        return app;
    }

    private static String origin(ConfigurableApplicationContext app) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    @Test
    void rotatesDailyMessagesAcrossLocalDatesDevicesAndRestart() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "e208_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        LocalDate start = LocalDate.of(2026, 11, 10);
        Instant midnight = start.atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant();
        JsonNode first;
        JsonNode next;
        JsonNode secondCycleLast;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                long owner;
                long other;
                try (var previous = new SpringApplicationBuilder(SelfCareApplication.class).web(WebApplicationType.NONE)
                        .run("--spring.datasource.url=" + url, "--spring.flyway.target=5",
                                "--spring.jpa.hibernate.ddl-auto=none")) {
                    var jdbc = previous.getBean(JdbcTemplate.class);
                    var hash = previous.getBean(PasswordEncoder.class).encode(PASSWORD);
                    owner = insertAccount(jdbc, "daily_sp", hash, "America/Sao_Paulo");
                    other = insertAccount(jdbc, "daily_utc", hash, "America/Sao_Paulo");
                }
                try (var app = startDailyMessageApp(url, midnight)) {
                    var jdbc = app.getBean(JdbcTemplate.class);
                    var clock = app.getBean(MutableTime.class).instant();
                    var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                    var session = new Session(origin);
                    var device = new Session(origin);
                    session.get(DAILY_MESSAGE, 401);
                    session.login("daily_sp");
                    device.login("daily_sp");
                    session.get(AGENDA, 200);
                    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM daily_message_rotations", Integer.class));

                    var gate = new CyclicBarrier(2);
                    try (var requests = Executors.newFixedThreadPool(2)) {
                        var responseA = requests.submit(() -> {
                            gate.await(10, TimeUnit.SECONDS);
                            return session.dailyMessage();
                        });
                        var responseB = requests.submit(() -> {
                            gate.await(10, TimeUnit.SECONDS);
                            return device.dailyMessage();
                        });
                        first = responseA.get(15, TimeUnit.SECONDS);
                        assertEquals(first, responseB.get(15, TimeUnit.SECONDS));
                    }
                    assertEquals(first, session.dailyMessage());
                    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM daily_message_rotations", Integer.class));
                    assertEquals(start, jdbc.queryForObject(
                            "SELECT started_on FROM daily_message_rotations WHERE account_id = ?", LocalDate.class, owner));

                    clock.set(midnight.plus(Duration.ofDays(1)).minusSeconds(1));
                    assertEquals(first, device.dailyMessage());
                    var utc = new Session(origin);
                    utc.login("daily_utc");
                    utc.dailyMessage();
                    assertEquals(start, jdbc.queryForObject(
                            "SELECT started_on FROM daily_message_rotations WHERE account_id = ?", LocalDate.class, other));
                    clock.set(midnight.plus(Duration.ofDays(1)));
                    next = device.dailyMessage();
                    assertNotEquals(first, next);

                    clock.set(midnight.plus(Duration.ofDays(30)));
                    var firstVisitInSecondCycle = device.dailyMessage();
                    var cycleNumbers = new HashSet<Integer>();
                    JsonNode previousMessage = null;
                    for (int day = 0; day < 60; day++) {
                        clock.set(midnight.plus(Duration.ofDays(day)));
                        var message = session.dailyMessage();
                        if (day == 30) {
                            assertEquals(30, cycleNumbers.size());
                            assertNotEquals(previousMessage, message);
                            assertEquals(firstVisitInSecondCycle, message);
                            cycleNumbers.clear();
                        }
                        int number = message.path("number").asInt();
                        assertTrue(number >= 1 && number <= 30);
                        assertTrue(cycleNumbers.add(number), "Repeated message within a cycle on day " + day);
                        if (number == 27) {
                            assertEquals("SKIN", message.path("category").asString());
                            assertEquals("Lembre-se do protetor solar na rotina do dia.", message.path("text").asString());
                        } else {
                            assertTrue(message.path("category").isNull());
                        }
                        previousMessage = message;
                    }
                    assertEquals(30, cycleNumbers.size());
                    secondCycleLast = previousMessage;
                    assertEquals("[]", session.get(HISTORY, 200));
                    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                            "UPDATE accounts SET time_zone = 'Pacific/Honolulu' WHERE id = ?", owner));
                    clock.set(midnight);
                    var travel = new Session(origin);
                    travel.login("daily_sp");
                    assertEquals(first, travel.dailyMessage());
                    clock.set(midnight.plus(Duration.ofDays(1)));
                    assertEquals(next, travel.dailyMessage());
                    assertEquals("America/Sao_Paulo", JSON.readTree(travel.get("/api/agenda", 200)).path("timeZone").asString());
                }
                try (var restarted = startDailyMessageApp(url, midnight.plus(Duration.ofDays(59)))) {
                    var session = new Session("http://127.0.0.1:"
                            + ((WebServerApplicationContext) restarted).getWebServer().getPort());
                    session.login("daily_sp");
                    assertEquals(secondCycleLast, session.dailyMessage());
                    restarted.getBean(MutableTime.class).instant().set(midnight);
                    assertEquals(first, session.dailyMessage());
                }
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static ConfigurableApplicationContext startDailyMessageApp(String url, Instant now) {
        var app = new SpringApplicationBuilder(SelfCareApplication.class, MutableClockConfiguration.class)
                .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                        "--app.agenda.daily-message-enabled=true", "--app.auth.session-max-age=90d");
        app.getBean(MutableTime.class).instant().set(now);
        return app;
    }

    @Test
    void recalculatesTheCurrentStreakAcrossCategoriesAndAccountDayBoundaries() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "e207_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                            "--app.agenda.care-streak-enabled=true")) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                insertAccount(jdbc, "streak_sp", hash, "America/Sao_Paulo");
                insertAccount(jdbc, "streak_utc", hash, "America/Sao_Paulo");
                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                var utc = new Session(origin);
                session.get(AGENDA, 401);
                session.login("streak_sp");
                utc.login("streak_utc");
                assertEquals("2026-11-09", session.week("").path("today").asString());
                assertEquals("2026-11-09", utc.week("").path("today").asString());

                session.post(HISTORY + "/nails", """
                        {"title":"Esmalte antigo","completedOn":"2026-11-05"}
                        """, true, 201);
                session.post(HISTORY + "/makeup", """
                        {"title":"Maquiagem leve","completedOn":"2026-11-07"}
                        """, true, 201);
                session.post(HISTORY + "/nails", """
                        {"title":"Esmalte","completedOn":"2026-11-07"}
                        """, true, 201);
                assertStreak(session, "", 0);

                long skin = JSON.readTree(session.post(SKIN, """
                        {"title":"Rotina de pele","plannedDate":"2026-11-09"}
                        """, true, 201)).path("id").asLong();
                session.action(skin, "skip", null, true, 204);
                assertStreak(session, "", 0);
                session.action(skin, "undo", null, true, 204);
                session.action(skin, "complete", """
                        {"completedOn":"2026-11-08"}
                        """, true, 204);
                assertStreak(session, "", 2);
                assertStreak(session, "?date=2026-10-01", 2);
                assertStreak(utc, "", 0);

                utc.post(HISTORY + "/nails", """
                        {"title":"Esmalte","completedOn":"2026-11-07"}
                        """, true, 201);
                assertStreak(utc, "", 0);
                utc.post(HISTORY + "/makeup", """
                        {"title":"Maquiagem leve","completedOn":"2026-11-08"}
                        """, true, 201);
                assertStreak(utc, "", 2);

                long plan = JSON.readTree(session.post("/api/hair-plans", """
                        {"requestId":"%s","startDate":"2026-11-09","weeks":1,"weekdays":["MONDAY"],
                         "occurrences":[{"date":"2026-11-09","stage":"WASH_ONLY"}]}
                        """.formatted(UUID.randomUUID()), true, 201)).path("planId").asLong();
                long hair = jdbc.queryForObject("SELECT id FROM care_occurrences WHERE plan_id = ?", Long.class, plan);
                session.action(hair, "complete", """
                        {"completedOn":"2026-11-09"}
                        """, true, 204);
                assertStreak(session, "", 3);
                session.action(skin, "undo", null, true, 204);
                assertStreak(session, "", 1);
                assertStreak(utc, "", 2);
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void assertStreak(Session session, String query, int days) throws Exception {
        assertEquals(JSON.getNodeFactory().numberNode(days), session.week(query).path("careStreakDays"));
    }

    @Test
    void completesPlannedCareOnlyFromItsDateInTheAccountTimeZone() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "e302_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String friday = "2026-11-13";
        String sunday = "2026-11-15";
        String fridayCompletion = "{\"completedOn\":\"2026-11-13\"}";
        String sundayCompletion = "{\"completedOn\":\"2026-11-15\"}";

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, MutableClockConfiguration.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                            "--app.agenda.reminders-enabled=true", "--app.auth.session-max-age=3d")) {
                var time = app.getBean(MutableTime.class).instant();
                time.set(Instant.parse("2026-11-13T15:00:00Z"));
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                insertAccount(jdbc, "dates_sp", hash, "America/Sao_Paulo");
                insertAccount(jdbc, "dates_utc", hash, "America/Sao_Paulo");
                var saoPaulo = new Session(origin(app));
                var utc = new Session(origin(app));
                saoPaulo.login("dates_sp");
                utc.login("dates_utc");
                saoPaulo.put(REMINDERS, """
                        {"enabled":true,"categories":["SKIN"],"time":"20:00","timeZone":"America/Sao_Paulo"}
                        """, true, 200);

                String futureCare = "{\"title\":\"Cuidado de domingo\",\"plannedDate\":\"2026-11-15\"}";
                long future = JSON.readTree(saoPaulo.post(SKIN, futureCare, true, 201)).path("id").asLong();
                long moved = JSON.readTree(saoPaulo.post(SKIN, futureCare, true, 201)).path("id").asLong();
                long utcCare = JSON.readTree(utc.post(SKIN, futureCare, true, 201)).path("id").asLong();
                long overdue = JSON.readTree(saoPaulo.post(SKIN, """
                        {"title":"Cuidado de sexta","plannedDate":"2026-11-13"}
                        """, true, 201)).path("id").asLong();
                var original = saoPaulo.week("");
                assertEquals(friday, original.path("today").asString());
                assertEquals("PLANNED", item(original, future).path("status").asString());
                assertEquals("20:00", item(original, future).path("reminderTime").asString());
                assertEquals(0, original.path("completedCount").asInt(-1));
                assertTrue(JSON.readTree(saoPaulo.action(future, "complete", fridayCompletion, true, 409))
                        .at("/errors/status").isString());
                assertTrue(JSON.readTree(saoPaulo.action(future, "complete", sundayCompletion, true, 400))
                        .at("/errors/completedOn").isString());
                utc.action(future, "complete", fridayCompletion, true, 404);
                assertTrue(JSON.readTree(saoPaulo.post(HISTORY + "/makeup", """
                        {"title":"Maquiagem direta","completedOn":"2026-11-15"}
                        """, true, 400)).at("/errors/completedOn").isString());
                assertEquals(original, saoPaulo.week(""));
                assertTrue(JSON.readTree(saoPaulo.get(HISTORY, 200)).isEmpty());

                saoPaulo.action(moved, "reschedule", plannedOn(friday), true, 204);
                saoPaulo.action(moved, "complete", fridayCompletion, true, 204);
                assertEquals(friday, item(saoPaulo.week(""), moved).path("completedOn").asString());
                saoPaulo.post(HISTORY + "/makeup", """
                        {"title":"Maquiagem direta","completedOn":"2026-11-13"}
                        """, true, 201);

                time.set(Instant.parse("2026-11-15T02:59:59Z"));
                assertEquals("2026-11-14", saoPaulo.week("").path("today").asString());
                assertEquals("2026-11-14", utc.week("").path("today").asString());
                saoPaulo.action(future, "complete", fridayCompletion, true, 409);
                utc.action(utcCare, "complete", sundayCompletion, true, 400);
                assertEquals("PLANNED", item(saoPaulo.week(""), "overdue", overdue).path("status").asString());
                saoPaulo.action(overdue, "complete", fridayCompletion, true, 204);

                time.set(Instant.parse("2026-11-15T03:00:00Z"));
                assertEquals(sunday, saoPaulo.week("").path("today").asString());
                assertEquals(sunday, utc.week("").path("today").asString());
                utc.action(utcCare, "complete", sundayCompletion, true, 204);
                saoPaulo.action(future, "complete", sundayCompletion, true, 204);
                saoPaulo.action(future, "complete", fridayCompletion, true, 204);
                var completed = saoPaulo.week("");
                assertEquals(sunday, item(completed, future).path("completedOn").asString());
                assertTrue(item(completed, future).path("reminderTime").isNull());
                assertEquals(4, completed.path("completedCount").asInt(-1));
                assertEquals(4, JSON.readTree(saoPaulo.get(HISTORY, 200)).size());
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void recordsCompletedBeautyCareWithoutSchedulingAndCountsByCompletionWeek() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "fsd07d_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                long owner = insertAccount(jdbc, "owner_direct", hash, "America/Sao_Paulo");
                long other = insertAccount(jdbc, "other_direct", hash, "America/Sao_Paulo");
                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                var otherSession = new Session(origin);
                String makeupPath = HISTORY + "/makeup";
                String nailsPath = HISTORY + "/nails";
                String makeup = """
                        {"title":"Maquiagem leve","completedOn":"2026-11-09","note":" Máscara marrom "}
                        """;
                String nails = """
                        {"title":"Esmaltar","completedOn":"2026-11-08","polishColor":"Lavanda","polishProduct":"Base"}
                        """;
                session.post(makeupPath, makeup, true, 401);
                session.login("owner_direct");
                otherSession.login("other_direct");
                session.post(nailsPath, nails, false, 403);
                session.post(HISTORY + "/skin", nails, true, 404);
                var invalid = JSON.readTree(session.post(makeupPath, """
                        {"title":" ","note":false,"plannedDate":"2026-11-09","accountId":%d}
                        """.formatted(other), true, 400));
                for (String field : List.of("title", "note", "plannedDate", "completedOn", "body")) {
                    assertTrue(invalid.at("/errors/" + field).isString(), field);
                }
                var impossible = JSON.readTree(session.post(nailsPath, """
                        {"title":"Esmaltar","completedOn":"2026-11-31"}
                        """, true, 400));
                assertTrue(impossible.at("/errors/completedOn").isString());
                for (String path : List.of(makeupPath, nailsPath)) {
                    var future = JSON.readTree(session.post(path, """
                            {"title":"Teste","completedOn":"2026-11-10"}
                            """, true, 400));
                    assertTrue(future.at("/errors/completedOn").isString());
                }
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM care_occurrences", Long.class));

                long makeupId = JSON.readTree(session.post(makeupPath, makeup, true, 201)).path("id").asLong();
                long nailsId = JSON.readTree(session.post(nailsPath, nails, true, 201)).path("id").asLong();
                var history = JSON.readTree(session.get(HISTORY, 200));
                assertEquals(2, history.size());
                assertEquals(makeupId, history.at("/0/id").asLong());
                assertEquals("MAKEUP", history.at("/0/category").asString());
                assertEquals("Máscara marrom", history.at("/0/note").asString());
                assertEquals("2026-11-09", history.at("/0/actionDate").asString());
                assertEquals(nailsId, history.at("/1/id").asLong());
                assertEquals("NAILS", history.at("/1/category").asString());
                assertEquals("Lavanda", history.at("/1/polishColor").asString());
                assertEquals("Base", history.at("/1/polishProduct").asString());
                assertEquals("2026-11-08", history.at("/1/actionDate").asString());
                for (JsonNode record : history) {
                    assertEquals("COMPLETED", record.path("status").asString());
                    assertTrue(record.path("canUndo").isBoolean());
                    assertFalse(record.path("canUndo").asBoolean());
                }
                assertEquals(2L, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM care_occurrences WHERE account_id = ? AND status = 'COMPLETED'
                            AND planned_date IS NULL AND plan_id IS NULL AND completed_on IS NOT NULL
                        """, Long.class, owner));
                for (String query : List.of("", "?date=2026-11-08")) {
                    var week = session.week(query);
                    assertEquals(1, week.path("completedCount").asInt());
                    assertTrue(week.path("careStreakDays").isNull());
                    assertTrue(week.path("items").isEmpty());
                    assertTrue(week.path("overdue").isEmpty());
                    assertTrue(week.path("nextCare").isNull());
                }
                session.action(makeupId, "undo", null, true, 409);
                session.action(nailsId, "reschedule", """
                        {"plannedDate":"2026-11-09"}
                        """, true, 409);
                otherSession.action(makeupId, "undo", null, true, 404);
                assertTrue(JSON.readTree(otherSession.get(HISTORY, 200)).isEmpty());
                assertEquals(0, otherSession.week("").path("completedCount").asInt());
                otherSession.post(makeupPath, """
                        {"title":"Praticar delineado","completedOn":"2026-11-09"}
                        """, true, 201);
                var otherHistory = JSON.readTree(otherSession.get(HISTORY, 200));
                assertEquals(1, otherHistory.size());
                assertEquals("2026-11-09", otherHistory.at("/0/actionDate").asString());
                assertTrue(otherHistory.at("/0/note").isNull());
                assertEquals(history, JSON.readTree(session.get(HISTORY, 200)));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void schedulesAllFourCategoriesOnTheSameDayAndUsesSharedActionsForMakeupAndNails() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "fsd07c_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                insertAccount(jdbc, "owner_beauty", hash, "America/Sao_Paulo");
                long other = insertAccount(jdbc, "other_beauty", hash, "America/Sao_Paulo");
                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                var otherSession = new Session(origin);

                String makeup = """
                        {"title":"  Maquiagem leve  ","plannedDate":"2026-11-09","note":"  Blush e máscara  "}
                        """;
                String nails = """
                        {"title":"Trocar esmalte","plannedDate":"2026-11-09","polishColor":" Lavanda ","polishProduct":" Base "}
                        """;
                session.post(MAKEUP, makeup, true, 401);
                session.post(NAILS, nails, true, 401);
                session.login("owner_beauty");
                otherSession.login("other_beauty");
                session.post(MAKEUP, makeup, false, 403);
                session.post(NAILS, nails, false, 403);

                var invalidMakeup = JSON.readTree(session.post(MAKEUP, """
                        {"title":" ","plannedDate":"2026-11-31","note":12}
                        """, true, 400));
                assertTrue(invalidMakeup.at("/errors/title").isString());
                assertTrue(invalidMakeup.at("/errors/plannedDate").isString());
                assertTrue(invalidMakeup.at("/errors/note").isString());
                var invalidNails = JSON.readTree(session.post(NAILS, """
                        {"title":"Esmaltar","plannedDate":"2026-11-09","polishColor":false,"polishProduct":{},"accountId":%d}
                        """.formatted(other), true, 400));
                assertTrue(invalidNails.at("/errors/polishColor").isString());
                assertTrue(invalidNails.at("/errors/polishProduct").isString());
                assertTrue(invalidNails.at("/errors/body").isString());
                assertTrue(JSON.readTree(otherSession.post(MAKEUP, makeup.replace("2026-11-09", "2026-11-08"), true, 400)).at("/errors/plannedDate").isString());
                assertTrue(JSON.readTree(otherSession.post(NAILS, nails.replace("2026-11-09", "2026-11-08"), true, 400)).at("/errors/plannedDate").isString());
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM care_occurrences", Long.class));

                long makeupId = JSON.readTree(session.post(MAKEUP, makeup, true, 201)).path("id").asLong();
                long nailsId = JSON.readTree(session.post(NAILS, nails, true, 201)).path("id").asLong();
                long plainMakeupId = JSON.readTree(session.post(MAKEUP, """
                        {"title":"Praticar delineado","plannedDate":"2026-11-09"}
                        """, true, 201)).path("id").asLong();
                long plainNailsId = JSON.readTree(session.post(NAILS, """
                        {"title":"Remover esmalte","plannedDate":"2026-11-09","polishColor":"  "}
                        """, true, 201)).path("id").asLong();
                long skinId = JSON.readTree(session.post(SKIN, """
                        {"title":"Rotina da manhã","period":"MORNING","plannedDate":"2026-11-09"}
                        """, true, 201)).path("id").asLong();
                long hairPlan = JSON.readTree(session.post("/api/hair-plans", """
                        {"requestId":"%s","startDate":"2026-11-09","weeks":1,"weekdays":["MONDAY"],
                         "occurrences":[{"date":"2026-11-09","stage":"HYDRATION"}]}
                        """.formatted(UUID.randomUUID()), true, 201)).path("planId").asLong();

                var week = session.week("");
                var items = week.path("items");
                assertEquals(6, items.size());
                assertEquals(Set.of("HAIR", "SKIN", "MAKEUP", "NAILS"), items.findValues("category").stream()
                        .map(JsonNode::asString).collect(Collectors.toSet()));
                assertTrue(items.findValues("date").stream().allMatch(date -> "2026-11-09".equals(date.asString())));
                long hairId = items.findValues("id").stream().mapToLong(JsonNode::asLong)
                        .filter(id -> item(week, id).path("planId").asLong() == hairPlan).findFirst().orElseThrow();
                assertEquals("HYDRATION", item(week, hairId).path("stage").asString());
                assertEquals(skinId, week.at("/nextCare/id").asLong());
                var makeupItem = item(week, "items", makeupId);
                assertEquals("Maquiagem leve", makeupItem.path("title").asString());
                assertEquals("MAKEUP", makeupItem.path("category").asString());
                assertEquals("Blush e máscara", makeupItem.path("note").asString());
                var nailsItem = item(week, "items", nailsId);
                assertEquals("NAILS", nailsItem.path("category").asString());
                assertEquals("Lavanda", nailsItem.path("polishColor").asString());
                assertEquals("Base", nailsItem.path("polishProduct").asString());
                assertTrue(item(week, "items", plainMakeupId).path("note").isNull());
                assertTrue(item(week, "items", plainNailsId).path("polishColor").isNull());
                assertTrue(item(week, "items", plainNailsId).path("polishProduct").isNull());
                assertTrue(otherSession.week("").path("items").isEmpty());

                for (long id : List.of(makeupId, nailsId)) {
                    var care = item(week, "items", id);
                    assertTrue(care.path("planId").isNull());
                    assertTrue(care.path("canReschedule").asBoolean());
                    otherSession.action(id, "skip", null, true, 404);
                    session.action(id, "reschedule", """
                            {"plannedDate":"2026-11-10"}
                            """, true, 204);
                    assertEquals("2026-11-10", item(session.week(""), "items", id).path("date").asString());
                    session.action(id, "skip", null, true, 204);
                    session.action(id, "undo", null, true, 204);
                    session.action(id, "reschedule", plannedOn("2026-11-09"), true, 204);
                    session.action(id, "complete", """
                            {"completedOn":"2026-11-09"}
                            """, true, 204);
                }
                var history = JSON.readTree(session.get(HISTORY, 200));
                assertEquals(2, history.size());
                assertEquals("Lavanda", history.at("/0/polishColor").asString());
                assertEquals("Base", history.at("/0/polishProduct").asString());
                assertEquals("Blush e máscara", history.at("/1/note").asString());
                assertTrue(JSON.readTree(otherSession.get(HISTORY, 200)).isEmpty());
                var updatedWeek = session.week("");
                assertEquals(2, updatedWeek.path("completedCount").asInt());
                for (long untouchedId : List.of(hairId, skinId)) {
                    assertEquals(item(week, untouchedId), item(updatedWeek, untouchedId));
                }
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void schedulesStandaloneSkinCareForTheAuthenticatedAccount() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "fsd07a_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                long owner = insertAccount(jdbc, "owner_skin", hash, "America/Sao_Paulo");
                long other = insertAccount(jdbc, "other_skin", hash, "America/Sao_Paulo");
                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                var otherSession = new Session(origin);
                String morning = """
                        {"title":"  Protetor solar  ","plannedDate":"2026-11-09","period":"MORNING","accountId":%d}
                        """.formatted(other);
                session.post(SKIN, morning, true, 401);
                session.login("owner_skin");
                otherSession.login("other_skin");
                assertEquals("2026-11-09", JSON.readTree(session.get("/api/agenda", 200)).path("today").asString());
                assertEquals("2026-11-09", JSON.readTree(otherSession.get("/api/agenda", 200)).path("today").asString());
                session.post(SKIN, morning, false, 403);

                var invalid = JSON.readTree(session.post(SKIN, """
                        {"title":"   ","plannedDate":"2026-02-30","period":"DAY"}
                        """, true, 400));
                assertTrue(invalid.at("/errors/title").isString());
                assertTrue(invalid.at("/errors/plannedDate").isString());
                assertTrue(invalid.at("/errors/period").isString());
                assertTrue(JSON.readTree(otherSession.post(SKIN, morning.replace("2026-11-09", "2026-11-08"), true, 400)).at("/errors/plannedDate").isString());
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM care_occurrences", Long.class));

                session.post("/api/hair-plans", """
                        {"requestId":"%s","startDate":"2026-11-09","weeks":1,"weekdays":["MONDAY"],
                         "occurrences":[{"date":"2026-11-09","stage":"WASH_ONLY"}]}
                        """.formatted(UUID.randomUUID()), true, 201);
                long morningId = JSON.readTree(session.post(SKIN, morning, true, 201)).path("id").asLong();
                session.post(SKIN, """
                        {"title":"Limpeza facial","plannedDate":"2026-11-09","period":"NIGHT"}
                        """, true, 201);
                session.post(SKIN, """
                        {"title":"Hidratação facial","plannedDate":"2026-11-09"}
                        """, true, 201);

                var week = session.week("");
                var items = week.path("items");
                assertEquals(JSON.readTree("""
                        ["Protetor solar","Limpeza facial","Lavar cabelo","Hidratação facial"]
                        """), titles(items));
                assertEquals(morningId, week.at("/nextCare/id").asLong());
                assertEquals("SKIN", items.get(0).path("category").asString());
                assertEquals("MORNING", items.get(0).path("period").asString());
                assertEquals("NIGHT", items.get(1).path("period").asString());
                assertEquals("HAIR", items.get(2).path("category").asString());
                assertTrue(items.get(3).path("period").isNull());
                assertTrue(items.get(0).path("planId").isNull());
                assertTrue(items.get(0).path("canReschedule").asBoolean());
                assertEquals(owner, jdbc.queryForObject("SELECT account_id FROM care_occurrences WHERE id = ?",
                        Long.class, morningId));
                assertTrue(otherSession.week("").path("items").isEmpty());
                otherSession.action(morningId, "skip", null, true, 404);
                session.action(morningId, "complete", """
                        {"completedOn":"2026-11-09"}
                        """, true, 204);
                assertEquals("Protetor solar", JSON.readTree(session.get(HISTORY, 200)).at("/0/title").asString());
                assertEquals(1, session.week("").path("completedCount").asInt());
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void createsRecurringSkinCareWithTheHairScheduleAndOrdersCoincidentPeriods() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "fsd07b_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                long owner = insertAccount(jdbc, "owner_routine", hash, "America/Sao_Paulo");
                insertAccount(jdbc, "other_routine", hash, "America/Sao_Paulo");
                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                var otherSession = new Session(origin);
                String schedule = """
                        "startDate":"2026-11-11","weeks":2,"weekdays":["MONDAY","THURSDAY"]
                        """;
                String night = """
                        {"requestId":"%s","title":"Limpeza facial","period":"NIGHT",%s}
                        """.formatted(UUID.randomUUID(), schedule);
                String morning = """
                        {"requestId":"%s","title":"Protetor solar","period":"MORNING",%s}
                        """.formatted(UUID.randomUUID(), schedule);
                session.post(SKIN_PLANS, night, true, 401);
                session.login("owner_routine");
                otherSession.login("other_routine");
                session.post(SKIN_PLANS, night, false, 403);
                var invalidWeeks = session.post(SKIN_PLANS, night.replace("\"weeks\":2", "\"weeks\":13"), true, 400);
                assertTrue(JSON.readTree(invalidWeeks).at("/errors/weeks").isString());
                var invalidDays = session.post(SKIN_PLANS,
                        night.replace("[\"MONDAY\",\"THURSDAY\"]", "[]"), true, 400);
                assertTrue(JSON.readTree(invalidDays).at("/errors/weekdays").isString());
                var past = otherSession.post(SKIN_PLANS, morning.replace("2026-11-11", "2026-11-08"), true, 400);
                assertTrue(JSON.readTree(past).at("/errors/startDate").isString());
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recurring_plans", Long.class));

                long nightId = JSON.readTree(session.post(SKIN_PLANS, night, true, 201)).path("planId").asLong();
                long morningId = JSON.readTree(session.post(SKIN_PLANS, morning, true, 201)).path("planId").asLong();
                assertNotEquals(morningId, nightId);
                String retry = night.replace("[\"MONDAY\",\"THURSDAY\"]", "[\"THURSDAY\",\"MONDAY\",\"MONDAY\"]");
                assertEquals(nightId, JSON.readTree(session.post(SKIN_PLANS, retry, true, 201)).path("planId").asLong());
                session.post(SKIN_PLANS, night.replace("NIGHT", "MORNING"), true, 409);
                assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM recurring_plans", Long.class));

                var preview = JSON.readTree(session.post("/api/hair-plans/preview", "{" + schedule + "}", false, 200));
                var dates = preview.path("occurrences").findValues("date").stream().map(JsonNode::asString).toList();
                assertEquals(List.of("2026-11-12", "2026-11-16", "2026-11-19", "2026-11-23"), dates);
                for (long planId : List.of(nightId, morningId)) {
                    assertEquals(dates, jdbc.queryForList("""
                            SELECT planned_date::text FROM care_occurrences
                            WHERE plan_id = ? AND account_id = ? AND period IS NULL ORDER BY planned_date
                            """, String.class, planId, owner));
                }
                var week = session.week("");
                assertEquals(JSON.readTree("[\"Protetor solar\",\"Limpeza facial\"]"), titles(week.path("items")));
                assertEquals("MORNING", week.at("/items/0/period").asString());
                assertEquals("NIGHT", week.at("/items/1/period").asString());
                assertEquals(morningId, week.at("/nextCare/planId").asLong());
                assertTrue(otherSession.week("").path("items").isEmpty());

                String closeNight = SKIN_PLANS + "/" + nightId + "/close";
                assertEquals(otherSession.post(SKIN_PLANS + "/0/close", null, true, 404),
                        otherSession.post(closeNight, null, true, 404));
                session.post(closeNight, null, false, 403);
                session.post(closeNight, null, true, 204);
                session.post(closeNight, null, true, 204);
                assertEquals(JSON.readTree("[\"Protetor solar\"]"), titles(session.week("").path("items")));
                assertEquals("2026-11-09", jdbc.queryForObject("""
                        SELECT closed_on::text FROM recurring_plans WHERE id = ?
                        """, String.class, nightId));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void savesTheConfirmedSkinRoutineAtomicallyAndReusesTheResultAcrossSessions() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "e306_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String endpoint = "/api/skin-routines";
        String morning = """
                {"period":"MORNING","title":"Manhã editada","weekdays":["MONDAY","FRIDAY"],
                 "steps":["Limpeza facial suave","Hidratação leve","Proteção solar"]}
                """;
        String night = """
                {"period":"NIGHT","title":"Noite editada","weekdays":["MONDAY","THURSDAY"],
                 "steps":["Remover a maquiagem","Hidratação"]}
                """;
        String requestId = UUID.randomUUID().toString();
        String format = """
                {"requestId":"%s","startDate":"2026-11-09","weeks":1,"periods":[%s]}
                """;
        String both = format.formatted(requestId, morning + "," + night);

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                long ownerId = insertAccount(jdbc, "skin_owner", hash, "America/Sao_Paulo");
                insertAccount(jdbc, "skin_other", hash, "America/Sao_Paulo");
                var owner = new Session(origin(app));
                var device = new Session(origin(app));
                var other = new Session(origin(app));
                owner.post(endpoint, both, true, 401);
                owner.login("skin_owner");
                device.login("skin_owner");
                other.login("skin_other");
                owner.post(endpoint, both, false, 403);
                owner.post(endpoint, both.replace("\"weeks\":1", "\"accountId\":" + ownerId + ",\"weeks\":1"), true, 400);
                var invalid = owner.post(endpoint, both.replace("\"Hidratação\"", "\" \""), true, 400);
                assertTrue(JSON.readTree(invalid).at("/errors/NIGHT.steps").isString());

                String original = """
                        {"title":"Rotina existente","period":"MORNING","startDate":"2026-11-09",
                         "weeks":1,"weekdays":["MONDAY"],"requestId":"%s"}
                        """.formatted(UUID.randomUUID());
                long originalId = JSON.readTree(owner.post(SKIN_PLANS, original, true, 201)).path("planId").asLong();

                // Fail after the morning plan and its occurrences have been inserted.
                jdbc.execute("""
                        ALTER TABLE care_occurrences ADD CONSTRAINT test_reject_night CHECK (title <> 'Noite editada')
                        """);
                owner.post(endpoint, both, true, 500);
                assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM recurring_plans", Long.class));
                assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM care_occurrences", Long.class));
                jdbc.execute("ALTER TABLE care_occurrences DROP CONSTRAINT test_reject_night");

                String reordered = format.formatted(requestId,
                        night + "," + morning.replace("[\"MONDAY\",\"FRIDAY\"]", "[\"FRIDAY\",\"MONDAY\"]"));
                var gate = new CyclicBarrier(2);
                JsonNode ids;
                try (var workers = Executors.newFixedThreadPool(2)) {
                    var first = workers.submit(() -> {
                        gate.await(5, TimeUnit.SECONDS);
                        return JSON.readTree(owner.post(endpoint, both, true, 201)).path("planIds");
                    });
                    var retry = workers.submit(() -> {
                        gate.await(5, TimeUnit.SECONDS);
                        return JSON.readTree(device.post(endpoint, reordered, true, 201)).path("planIds");
                    });
                    ids = first.get(15, TimeUnit.SECONDS);
                    assertEquals(ids, retry.get(15, TimeUnit.SECONDS));
                }
                assertEquals(2, ids.size());
                owner.post(endpoint, both.replace("Hidratação leve", "Hidratação alterada"), true, 409);
                assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM recurring_plans", Long.class));
                assertEquals(5L, jdbc.queryForObject("SELECT COUNT(*) FROM care_occurrences", Long.class));

                var week = owner.week("");
                assertEquals(JSON.readTree("""
                        ["Rotina existente","Manhã editada","Noite editada","Noite editada","Manhã editada"]
                        """), titles(week.path("items")));
                assertEquals(originalId, week.at("/nextCare/planId").asLong());
                assertEquals("2026-11-09", week.at("/items/1/date").asString());
                assertEquals("2026-11-09", week.at("/items/2/date").asString());
                assertEquals("2026-11-12", week.at("/items/3/date").asString());
                assertEquals("2026-11-13", week.at("/items/4/date").asString());
                assertEquals("MORNING", week.at("/items/1/period").asString());
                assertEquals("NIGHT", week.at("/items/2/period").asString());
                assertEquals(JSON.readTree("""
                        ["Limpeza facial suave","Hidratação leve","Proteção solar"]
                        """), week.at("/items/1/skinSteps"));
                var nightSteps = JSON.readTree("""
                        ["Remover a maquiagem","Hidratação"]
                        """);
                assertEquals(nightSteps, week.at("/items/2/skinSteps"));
                long nightOccurrence = week.at("/items/2/id").asLong();
                assertTrue(other.week("").path("items").isEmpty());
                other.action(nightOccurrence, "skip", null, true, 404);
                other.post(SKIN_PLANS + "/" + ids.get(1).asLong() + "/close", null, true, 404);
                owner.action(nightOccurrence, "complete", "{\"completedOn\":\"2026-11-09\"}", true, 204);
                assertEquals(nightSteps, JSON.readTree(owner.get(HISTORY, 200)).at("/0/skinSteps"));
                assertTrue(JSON.readTree(other.get(HISTORY, 200)).isEmpty());

                // Every account rejects dates before the current day in São Paulo.
                other.post(endpoint, format.formatted(requestId, night).replace("2026-11-09", "2026-11-08"), true, 400);
                var single = format.formatted(requestId, night).replace("2026-11-09", "2026-11-10");
                var otherIds = JSON.readTree(other.post(endpoint, single, true, 201)).path("planIds");
                assertEquals(1, otherIds.size());
                assertNotEquals(ids.get(1), otherIds.get(0));
                assertEquals(otherIds, JSON.readTree(other.post(endpoint, single, true, 201)).path("planIds"));
                assertEquals(JSON.readTree("[\"Noite editada\"]"), titles(other.week("").path("items")));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void loadsTheAccountWeekAndSeparatesOverdueFromUpcomingCare() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "fsd06a_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                long owner = insertAccount(jdbc, "owner_agenda", hash, "America/Sao_Paulo");
                long other = insertAccount(jdbc, "other_agenda", hash, "America/Sao_Paulo");
                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                session.get(AGENDA, 401);
                session.login("owner_agenda");
                var empty = session.week("");
                assertEquals("2026-11-09", empty.path("today").asString());
                assertEquals(JSON.readTree("""
                        ["2026-11-09","2026-11-10","2026-11-11","2026-11-12","2026-11-13","2026-11-14","2026-11-15"]
                        """), empty.path("days"));
                assertTrue(empty.path("items").isEmpty());
                assertTrue(empty.path("overdue").isEmpty());
                assertTrue(empty.path("nextCare").isNull());
                assertEquals(0, empty.path("completedCount").asInt());

                seedCare(jdbc, owner);
                var week = session.week("");
                assertEquals(JSON.readTree("""
                        ["Pele de manhã","Segunda rotina da manhã","Pele à noite","Lavagem","Segunda lavagem"]
                        """), titles(week.path("items")));
                assertEquals(JSON.readTree("[\"Lavagem em atraso\"]"), titles(week.path("overdue")));
                assertEquals("Pele de manhã", week.at("/nextCare/title").asString());
                assertEquals("MORNING", week.at("/nextCare/period").asString());
                assertEquals(2, week.path("completedCount").asInt());

                var previous = session.week("?date=" + week.path("previousWeek").asString());
                assertEquals("2026-11-02", previous.at("/days/0").asString());
                assertEquals("2026-11-08", previous.at("/days/6").asString());
                assertEquals(JSON.readTree("[\"Sessão concluída\"]"), titles(previous.path("items")));
                assertEquals(0, previous.path("completedCount").asInt());
                var next = session.week("?date=" + week.path("nextWeek").asString());
                assertEquals("2026-11-16", next.at("/days/0").asString());
                assertEquals("2026-11-22", next.at("/days/6").asString());
                assertEquals(JSON.readTree("[\"Lavagem da próxima semana\"]"), titles(next.path("items")));
                assertEquals(week.path("nextCare"), next.path("nextCare"));
                assertEquals(week.path("overdue"), next.path("overdue"));
                assertEquals(0, next.path("completedCount").asInt());

                jdbc.update("""
                        INSERT INTO care_occurrences (account_id, category, title, planned_date, status)
                        VALUES (?, 'NAILS', 'Cuidado de outra conta', DATE '2026-11-10', 'PLANNED')
                        """, other);
                var otherSession = new Session(origin);
                otherSession.login("other_agenda");
                var otherWeek = otherSession.week("");
                assertEquals("2026-11-09", otherWeek.path("today").asString());
                assertEquals(JSON.readTree("[\"Cuidado de outra conta\"]"), titles(otherWeek.path("items")));
                assertTrue(otherWeek.path("overdue").isEmpty());
                assertEquals(0, otherWeek.path("completedCount").asInt());
                assertEquals(week, session.week(""));

                assertTrue(JSON.readTree(session.get(AGENDA + "?date=2026-02-30", 400)).at("/errors/date").isString());
                assertEquals("9999-12-31", session.week("?date=9999-12-31").at("/days/4").asString());
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void completesSkipsAndUndoesOnlyOwnedScheduledCare() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "fsd06b_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                long owner = insertAccount(jdbc, "owner_actions", hash, "America/Sao_Paulo");
                insertAccount(jdbc, "other_actions", hash, "America/Sao_Paulo");
                seedCare(jdbc, owner);
                long overdue = careId(jdbc, "Lavagem em atraso");
                long today = careId(jdbc, "Lavagem");
                long hidden = careId(jdbc, "Ocorrência retirada");
                long direct = careId(jdbc, "Registro direto");
                jdbc.update("UPDATE care_occurrences SET planned_date = DATE '2026-11-08' WHERE id = ?", overdue);
                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                String monday = """
                        {"completedOn":"2026-11-09"}
                        """;
                String sunday = """
                        {"completedOn":"2026-11-08"}
                        """;

                session.action(overdue, "complete", monday, true, 401);
                session.login("owner_actions");
                session.action(overdue, "complete", monday, false, 403);
                for (String body : new String[] {
                        "{}", "{\"completedOn\":20261109}", "{\"completedOn\":\"2026-02-30\"}",
                        "{\"completedOn\":\"0000-01-01\"}", "{\"completedOn\":\"2026-11-10\"}"
                }) {
                    String response = session.action(overdue, "complete", body, true, 400);
                    assertTrue(JSON.readTree(response).at("/errors/completedOn").isString());
                }
                assertEquals(2, session.week("").path("completedCount").asInt());
                session.action(overdue, "undo", null, true, 409);
                session.action(hidden, "skip", null, true, 404);
                session.action(direct, "undo", null, true, 409);

                var other = new Session(origin);
                other.login("other_actions");
                for (String action : new String[] {"complete", "skip", "undo"}) {
                    String body = action.equals("complete") ? monday : null;
                    assertEquals(other.action(Long.MAX_VALUE, action, body, true, 404),
                            other.action(overdue, action, body, true, 404));
                }

                session.action(overdue, "complete", monday, true, 204);
                session.action(overdue, "complete", sunday, true, 204);
                session.action(overdue, "skip", null, true, 409);
                var currentWeek = session.week("");
                assertTrue(currentWeek.path("overdue").isEmpty());
                assertEquals(3, currentWeek.path("completedCount").asInt());
                var previousWeek = session.week("?date=2026-11-08");
                var completed = item(previousWeek, overdue);
                assertEquals("COMPLETED", completed.path("status").asString());
                assertEquals("2026-11-08", completed.path("date").asString());
                assertEquals("2026-11-09", completed.path("completedOn").asString());
                assertEquals(0, previousWeek.path("completedCount").asInt());
                session.action(overdue, "undo", null, true, 204);
                assertEquals(2, session.week("").path("completedCount").asInt());
                assertEquals(overdue, session.week("").at("/overdue/0/id").asLong());

                session.action(today, "skip", null, true, 204);
                session.action(today, "skip", null, true, 204);
                var skipped = item(session.week(""), today);
                assertEquals("SKIPPED", skipped.path("status").asString());
                assertEquals("2026-11-09", skipped.path("skippedOn").asString());
                session.action(today, "undo", null, true, 204);
                var restored = item(session.week(""), today);
                assertEquals("PLANNED", restored.path("status").asString());
                assertTrue(restored.path("skippedOn").isNull());

                var secondSession = new Session(origin);
                secondSession.login("owner_actions");
                var firstCompletion = session.actionRequest(today, "complete", sunday, true);
                var secondCompletion = secondSession.actionRequest(today, "complete", sunday, true);
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var first = executor.submit(() -> session.send(firstCompletion));
                    var second = executor.submit(() -> secondSession.send(secondCompletion));
                    var responses = List.of(first.get(), second.get());
                    assertTrue(responses.stream().allMatch(response -> response.statusCode() == 204));
                    assertEquals(List.of("applied", "unchanged"), responses.stream()
                            .map(response -> response.headers().firstValue("X-Care-Completion").orElseThrow())
                            .sorted().toList());
                }
                assertEquals(1, session.week("?date=2026-11-08").path("completedCount").asInt());
                assertEquals(2, session.week("").path("completedCount").asInt());

                jdbc.update("""
                        UPDATE care_occurrences SET status = 'COMPLETED', completed_on = DATE '2026-11-09'
                        WHERE id = ?
                        """, hidden);
                session.action(hidden, "undo", null, true, 204);
                assertEquals("PLANNED", jdbc.queryForObject(
                        "SELECT status FROM care_occurrences WHERE id = ?", String.class, hidden));
                session.action(hidden, "skip", null, true, 404);
                assertTrue(session.week("").path("items").findValues("id").stream().noneMatch(id -> id.asLong() == hidden));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void reschedulesOwnedPlannedCareAndAllowsOnlyOneConcurrentMoveToTheSamePlanDate() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "fsd06c_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                long owner = insertAccount(jdbc, "owner_reschedule", hash, "America/Sao_Paulo");
                insertAccount(jdbc, "other_reschedule", hash, "America/Sao_Paulo");
                seedCare(jdbc, owner);
                long today = careId(jdbc, "Lavagem");
                long upcoming = careId(jdbc, "Lavagem da próxima semana");
                long otherPlan = careId(jdbc, "Segunda lavagem");
                long closed = careId(jdbc, "Lavagem em atraso");
                long hidden = careId(jdbc, "Ocorrência retirada");
                long completed = careId(jdbc, "Sessão concluída");
                long direct = careId(jdbc, "Registro direto");
                long standalone = careId(jdbc, "Pele à noite");
                jdbc.update("UPDATE care_occurrences SET planned_date = DATE '2026-11-12' WHERE id = ?", standalone);
                long overdue = jdbc.queryForObject("""
                        INSERT INTO care_occurrences
                            (account_id, plan_id, category, title, planned_date, status, hair_stage)
                        SELECT account_id, plan_id, category, 'Rotina aberta em atraso',
                               DATE '2026-11-07', 'PLANNED', 'RECONSTRUCTION'
                        FROM care_occurrences WHERE id = ? RETURNING id
                        """, Long.class, today);

                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                String beyondPlanEnd = plannedOn("2026-11-23");
                session.action(today, "reschedule", beyondPlanEnd, true, 401);
                session.login("owner_reschedule");
                session.action(today, "reschedule", beyondPlanEnd, false, 403);

                var other = new Session(origin);
                other.login("other_reschedule");
                assertEquals(other.action(Long.MAX_VALUE, "reschedule", beyondPlanEnd, true, 404),
                        other.action(today, "reschedule", beyondPlanEnd, true, 404));

                for (String body : new String[] {"{}", "{\"plannedDate\":20261109}", plannedOn("2026-02-30"),
                        plannedOn("2026-11-08")}) {
                    assertDateError(session.action(today, "reschedule", body, true, 400));
                }
                assertDateError(session.action(today, "reschedule", plannedOn("2026-11-16"), true, 409));
                for (long id : new long[] {closed, completed, direct}) {
                    var response = session.action(id, "reschedule", beyondPlanEnd, true, 409);
                    assertTrue(JSON.readTree(response).at("/errors/status").isString());
                }
                session.action(hidden, "reschedule", beyondPlanEnd, true, 404);
                session.action(otherPlan, "skip", null, true, 204);
                session.action(otherPlan, "reschedule", beyondPlanEnd, true, 409);
                session.action(otherPlan, "undo", null, true, 204);

                var current = session.week("");
                assertEquals("2026-11-09", item(current, today).path("date").asString());
                assertTrue(item(current, today).path("canReschedule").asBoolean());
                assertFalse(item(current, "overdue", closed).path("canReschedule").asBoolean());
                assertTrue(item(current, "overdue", overdue).path("canReschedule").asBoolean());
                session.action(today, "reschedule", beyondPlanEnd, true, 204);
                session.action(otherPlan, "reschedule", beyondPlanEnd, true, 204);
                // An item without a plan may share today's date with other occurrences.
                session.action(standalone, "reschedule", plannedOn("2026-11-09"), true, 204);
                assertEquals("2026-11-09", item(session.week(""), standalone).path("date").asString());
                assertTrue(session.week("").path("items").findValues("id").stream().noneMatch(id -> id.asLong() == today));

                var movedWeek = session.week("?date=2026-11-23");
                var moved = item(movedWeek, today);
                assertEquals("2026-11-23", moved.path("date").asString());
                assertEquals("2026-11-23", item(movedWeek, otherPlan).path("date").asString());
                assertEquals("Lavagem", moved.path("title").asString());
                assertEquals("WASH_ONLY", moved.path("stage").asString());
                assertEquals("PLANNED", moved.path("status").asString());
                assertTrue(moved.path("completedOn").isNull());
                assertTrue(moved.path("skippedOn").isNull());

                var secondSession = new Session(origin);
                secondSession.login("owner_reschedule");
                String sameTarget = plannedOn("2026-11-24");
                var firstRequest = session.actionRequest(overdue, "reschedule", sameTarget, true);
                var secondRequest = secondSession.actionRequest(upcoming, "reschedule", sameTarget, true);
                var gate = new CyclicBarrier(2);
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var first = executor.submit(() -> {
                        gate.await(5, TimeUnit.SECONDS);
                        return session.send(firstRequest);
                    });
                    var second = executor.submit(() -> {
                        gate.await(5, TimeUnit.SECONDS);
                        return secondSession.send(secondRequest);
                    });
                    var a = first.get(15, TimeUnit.SECONDS);
                    var b = second.get(15, TimeUnit.SECONDS);
                    assertEquals(List.of(204, 409),
                            IntStream.of(a.statusCode(), b.statusCode()).sorted().boxed().toList());
                    boolean overdueWon = a.statusCode() == 204;
                    assertDateError(overdueWon ? b.body() : a.body());
                    assertEquals(overdueWon ? overdue : upcoming, jdbc.queryForObject("""
                            SELECT id FROM care_occurrences WHERE planned_date = DATE '2026-11-24'
                            """, Long.class));
                    assertEquals(overdueWon ? "2026-11-16" : "2026-11-07", jdbc.queryForObject("""
                            SELECT planned_date::text FROM care_occurrences WHERE id = ?
                            """, String.class, overdueWon ? upcoming : overdue));
                }
                session.action(overdue, "reschedule", plannedOn("2026-11-09"), true, 204);
                assertEquals("RECONSTRUCTION", item(session.week(""), overdue).path("stage").asString());
                assertEquals(JSON.readTree("[\"Lavagem em atraso\"]"), titles(session.week("").path("overdue")));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void boundsHistoryAndOverduePagesWithoutLosingTotalsOrLeakingAnotherAccount() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database);
        String schema = "pages_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                long owner = insertAccount(jdbc, "page_owner", hash, "America/Sao_Paulo");
                long other = insertAccount(jdbc, "page_other", hash, "America/Sao_Paulo");
                var today = java.time.LocalDate.now(app.getBean(Clock.class).withZone(ZoneId.of("America/Sao_Paulo")));
                for (long account : new long[] {owner, other}) {
                    jdbc.update("""
                            INSERT INTO care_occurrences (account_id, category, title, status, completed_on)
                            SELECT ?, 'MAKEUP', 'Synthetic history ' || n, 'COMPLETED', ?::date
                            FROM generate_series(1, 123) n
                            """, account, today);
                    jdbc.update("""
                            INSERT INTO care_occurrences (account_id, category, title, status, planned_date)
                            SELECT ?, 'MAKEUP', 'Synthetic overdue ' || n, 'PLANNED', ?::date
                            FROM generate_series(1, 123) n
                            """, account, today.minusDays(1));
                }
                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                session.login("page_owner");
                var ids = new java.util.HashSet<Long>();
                for (int page = 0; page < 3; page++) {
                    var response = session.client.send(session.request(HISTORY + "?page=" + page).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode());
                    var items = JSON.readTree(response.body());
                    assertEquals(page < 2 ? 50 : 23, items.size());
                    assertEquals("123", response.headers().firstValue("X-History-Completed").orElseThrow());
                    assertEquals(page < 2 ? Integer.toString(page + 1) : null,
                            response.headers().firstValue("X-Next-Page").orElse(null));
                    items.forEach(item -> assertTrue(ids.add(item.path("id").asLong())));
                    var week = session.week("?overduePage=" + page);
                    assertEquals(page < 2 ? 50 : 23, week.path("overdue").size());
                    if (page < 2) assertEquals(page + 1, week.path("overdueNextPage").asInt());
                    else assertTrue(week.path("overdueNextPage").isNull());
                }
                assertEquals(123, ids.size());
                Long foreignId = jdbc.queryForObject("SELECT min(id) FROM care_occurrences WHERE account_id = ?", Long.class, other);
                assertTrue(ids.stream().allMatch(id -> id < foreignId));
                session.get(HISTORY + "?page=-1", 400);
                session.get(HISTORY + "?page=10001", 400);
                session.get(AGENDA + "?overduePage=-1", 400);
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void listsOwnedHistoryByActionDateAndRemovesUndoneCare() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "fsd06d_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                long owner = insertAccount(jdbc, "owner_history", hash, "America/Sao_Paulo");
                long other = insertAccount(jdbc, "other_history", hash, "America/Sao_Paulo");
                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                session.get(HISTORY, 401);
                session.login("owner_history");
                assertEquals(JSON.readTree("[]"), JSON.readTree(session.get(HISTORY, 200)));

                seedCare(jdbc, owner);
                jdbc.update("""
                        INSERT INTO care_occurrences (account_id, category, title, status, completed_on)
                        VALUES (?, 'NAILS', 'Registro da outra conta', 'COMPLETED', DATE '2026-11-09')
                        """, other);
                long skipped = careId(jdbc, "Lavagem em atraso");
                long completed = careId(jdbc, "Lavagem");
                long closed = careId(jdbc, "Ocorrência retirada");
                jdbc.update("""
                        UPDATE care_occurrences SET status = 'COMPLETED', completed_on = DATE '2026-11-07'
                        WHERE id = ?
                        """, closed);
                session.action(skipped, "skip", null, true, 204);
                session.action(completed, "complete", "{\"completedOn\":\"2026-11-08\"}", true, 204);

                var history = JSON.readTree(session.get(HISTORY, 200));
                assertEquals(JSON.readTree("""
                        ["Registro direto","Lavagem em atraso","Sessão concluída","Lavagem","Ocorrência retirada"]
                        """), titles(history));
                assertFalse(history.at("/0/canUndo").asBoolean(true));
                assertEquals(skipped, history.at("/1/id").asLong());
                assertEquals("SKIPPED", history.at("/1/status").asString());
                assertEquals("2026-11-09", history.at("/1/actionDate").asString());
                assertEquals("HYDRATION", history.at("/1/stage").asString());
                assertTrue(history.at("/1/canUndo").asBoolean());
                assertEquals(completed, history.at("/3/id").asLong());
                assertEquals("HAIR", history.at("/3/category").asString());
                assertEquals("COMPLETED", history.at("/3/status").asString());
                assertEquals("2026-11-08", history.at("/3/actionDate").asString());
                assertTrue(history.at("/3/canUndo").asBoolean());

                var otherSession = new Session(origin);
                otherSession.login("other_history");
                assertEquals(JSON.readTree("[\"Registro da outra conta\"]"),
                        titles(JSON.readTree(otherSession.get(HISTORY, 200))));
                assertEquals(otherSession.action(Long.MAX_VALUE, "undo", null, true, 404),
                        otherSession.action(completed, "undo", null, true, 404));
                assertEquals(history, JSON.readTree(session.get(HISTORY, 200)));
                session.action(completed, "undo", null, false, 403);
                assertEquals(2, session.week("?date=2026-11-08").path("completedCount").asInt());

                session.action(completed, "undo", null, true, 204);
                session.action(skipped, "undo", null, true, 204);
                session.action(closed, "undo", null, true, 204);
                assertEquals(JSON.readTree("[\"Registro direto\",\"Sessão concluída\"]"),
                        titles(JSON.readTree(session.get(HISTORY, 200))));
                var currentWeek = session.week("");
                assertEquals("PLANNED", item(currentWeek, completed).path("status").asString());
                assertEquals(skipped, currentWeek.at("/overdue/0/id").asLong());
                assertTrue(currentWeek.path("items").findValues("id").stream().noneMatch(id -> id.asLong() == closed));
                assertEquals(0, session.week("?date=2026-11-08").path("completedCount").asInt());
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void closesTheOwnedPlanAndKeepsPastAndRecordedCareAvailable() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "fsd06e_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                String hash = app.getBean(PasswordEncoder.class).encode(PASSWORD);
                long owner = insertAccount(jdbc, "owner_closure", hash, "America/Sao_Paulo");
                insertAccount(jdbc, "other_closure", hash, "America/Sao_Paulo");
                seedCare(jdbc, owner);
                long today = careId(jdbc, "Lavagem");
                long nextWeek = careId(jdbc, "Lavagem da próxima semana");
                long pastCompleted = careId(jdbc, "Sessão concluída");
                long planId = jdbc.queryForObject("SELECT plan_id FROM care_occurrences WHERE id = ?", Long.class, today);
                String futureSession = """
                        INSERT INTO care_occurrences (account_id, plan_id, category, title, planned_date, status, hair_stage)
                        VALUES (?, ?, 'HAIR', ?, CAST(? AS date), 'PLANNED', 'WASH_ONLY') RETURNING id
                        """;
                long futureCompleted = jdbc.queryForObject(futureSession, Long.class, owner, planId,
                        "Lavagem concluída antes do previsto", "2026-11-11");
                // Preserve a completion recorded by a version that allowed it before the planned date.
                jdbc.update("UPDATE care_occurrences SET status = 'COMPLETED', completed_on = DATE '2026-11-09' WHERE id = ?",
                        futureCompleted);
                long futureSkipped = jdbc.queryForObject(futureSession, Long.class, owner, planId,
                        "Lavagem pulada antes do previsto", "2026-11-12");

                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                String planPath = "/api/hair-plans/" + planId;
                String closePath = planPath + "/close";
                session.post(closePath, null, true, 401);
                session.login("owner_closure");
                assertTrue(JSON.readTree(session.get(planPath, 200)).path("closedOn").isNull());
                assertEquals(planId, item(session.week(""), today).path("planId").asLong());
                assertTrue(item(session.week("?date=2026-11-16"), nextWeek).path("canReschedule").asBoolean());
                session.action(futureCompleted, "complete", "{\"completedOn\":\"2026-11-09\"}", true, 204);
                session.action(futureSkipped, "skip", null, true, 204);
                var history = JSON.readTree(session.get(HISTORY, 200));

                var other = new Session(origin);
                other.login("other_closure");
                assertEquals(other.post("/api/hair-plans/" + Long.MAX_VALUE + "/close", null, true, 404),
                        other.post(closePath, null, true, 404));
                session.post(closePath, null, false, 403);
                assertTrue(JSON.readTree(session.get(planPath, 200)).path("closedOn").isNull());

                var closed = JSON.readTree(session.post(closePath, null, true, 200));
                // It is already November 10 in UTC, but the account is still on November 9.
                assertEquals("2026-11-09", closed.path("closedOn").asString());
                assertEquals(closed, JSON.readTree(session.post(closePath, null, true, 200)));
                assertEquals(closed.path("closedOn"), JSON.readTree(session.get(planPath, 200)).path("closedOn"));
                var week = session.week("");
                assertTrue(week.path("items").findValues("id").stream().noneMatch(id -> id.asLong() == today));
                assertTrue(week.path("nextCare").isObject());
                assertNotEquals(planId, week.at("/nextCare/planId").asLong());
                assertTrue(session.week("?date=2026-11-16").path("items").isEmpty());
                assertEquals("COMPLETED", item(week, futureCompleted).path("status").asString());
                assertEquals("SKIPPED", item(week, futureSkipped).path("status").asString());
                assertEquals(history, JSON.readTree(session.get(HISTORY, 200)));
                session.action(today, "skip", null, true, 404);

                session.action(futureCompleted, "undo", null, true, 204);
                session.action(futureSkipped, "undo", null, true, 204);
                session.action(pastCompleted, "undo", null, true, 204);
                week = session.week("");
                assertTrue(week.path("items").findValues("id").stream()
                        .noneMatch(id -> id.asLong() == futureCompleted || id.asLong() == futureSkipped));
                var past = item(week, "overdue", pastCompleted);
                assertEquals("PLANNED", past.path("status").asString());
                assertFalse(past.path("canReschedule").asBoolean(true));
                session.action(pastCompleted, "reschedule", plannedOn("2026-11-10"), true, 409);
                session.action(pastCompleted, "skip", null, true, 204);
                assertEquals(JSON.readTree("[\"Registro direto\",\"Sessão concluída\"]"),
                        titles(JSON.readTree(session.get(HISTORY, 200))));
                assertEquals(5L, jdbc.queryForObject("SELECT COUNT(*) FROM care_occurrences WHERE plan_id = ?",
                        Long.class, planId));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static String plannedOn(String date) {
        return "{\"plannedDate\":\"" + date + "\"}";
    }

    private static void assertDateError(String response) {
        assertTrue(JSON.readTree(response).at("/errors/plannedDate").isString());
    }

    private static long careId(JdbcTemplate jdbc, String title) {
        return jdbc.queryForObject("SELECT id FROM care_occurrences WHERE title = ?", Long.class, title);
    }

    private static JsonNode item(JsonNode week, long id) {
        return item(week, "items", id);
    }

    private static JsonNode item(JsonNode week, String collection, long id) {
        for (JsonNode item : week.path(collection)) {
            if (item.path("id").asLong() == id) {
                return item;
            }
        }
        throw new AssertionError("Cuidado não encontrado na semana: " + id);
    }

    private static long insertAccount(JdbcTemplate jdbc, String username, String hash, String timeZone) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (username, password_hash, time_zone) VALUES (?, ?, ?) RETURNING id
                """, Long.class, username, hash, timeZone);
    }

    private static void seedCare(JdbcTemplate jdbc, long owner) {
        String insertHairPlan = """
                INSERT INTO recurring_plans (account_id, category, start_date, weeks, end_date, closed_on)
                VALUES (?, 'HAIR', DATE '2026-11-03', 2, DATE '2026-11-16', CAST(? AS date)) RETURNING id
                """;
        long hairPlan = jdbc.queryForObject(insertHairPlan, Long.class, owner, null);
        long secondHairPlan = jdbc.queryForObject(insertHairPlan, Long.class, owner, null);
        long closedPlan = jdbc.queryForObject(insertHairPlan, Long.class, owner, "2026-11-09");
        long morningPlan = jdbc.queryForObject("""
                INSERT INTO recurring_plans (account_id, category, start_date, weeks, end_date, title, period)
                VALUES (?, 'SKIN', DATE '2026-11-09', 1, DATE '2026-11-15', 'Pele de manhã', 'MORNING') RETURNING id
                """, Long.class, owner);
        jdbc.update("""
                INSERT INTO care_occurrences (account_id, plan_id, category, title, planned_date, status, hair_stage, completed_on)
                VALUES (?, ?, 'HAIR', 'Lavagem', DATE '2026-11-09', 'PLANNED', 'WASH_ONLY', NULL),
                       (?, ?, 'HAIR', 'Lavagem da próxima semana', DATE '2026-11-16', 'PLANNED', 'NUTRITION', NULL),
                       (?, ?, 'HAIR', 'Sessão concluída', DATE '2026-11-08', 'COMPLETED', 'WASH_ONLY', DATE '2026-11-09'),
                       (?, ?, 'HAIR', 'Lavagem em atraso', DATE '2026-11-07', 'PLANNED', 'HYDRATION', NULL),
                       (?, ?, 'HAIR', 'Ocorrência retirada', DATE '2026-11-10', 'PLANNED', 'WASH_ONLY', NULL)
                """, owner, hairPlan, owner, hairPlan, owner, hairPlan, owner, closedPlan, owner, closedPlan);
        jdbc.update("""
                INSERT INTO care_occurrences (account_id, plan_id, category, title, planned_date, status, hair_stage)
                VALUES (?, ?, 'HAIR', 'Segunda lavagem', DATE '2026-11-09', 'PLANNED', 'NUTRITION'),
                       (?, ?, 'SKIN', 'Pele de manhã', DATE '2026-11-09', 'PLANNED', NULL)
                """, owner, secondHairPlan, owner, morningPlan);
        jdbc.update("""
                INSERT INTO care_occurrences (account_id, category, title, planned_date, status, period)
                VALUES (?, 'SKIN', 'Pele à noite', DATE '2026-11-09', 'PLANNED', 'NIGHT'),
                       (?, 'SKIN', 'Segunda rotina da manhã', DATE '2026-11-09', 'PLANNED', 'MORNING')
                """, owner, owner);
        jdbc.update("""
                INSERT INTO care_occurrences (account_id, category, title, status, completed_on)
                VALUES (?, 'MAKEUP', 'Registro direto', 'COMPLETED', DATE '2026-11-09')
                """, owner);
    }

    private static JsonNode titles(JsonNode items) {
        var titles = JSON.createArrayNode();
        items.forEach(item -> titles.add(item.path("title").asString()));
        return titles;
    }

    private static class Session {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        private final String origin;

        Session(String origin) {
            this.origin = origin;
        }

        void login(String username) throws Exception {
            String token = JSON.readTree(get("/api/auth/csrf", 200)).path("token").asString();
            send(request("/api/auth/login")
                    .header("X-CSRF-TOKEN", token)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + PASSWORD))
                    .build(), 204);
        }

        JsonNode week(String query) throws Exception {
            return JSON.readTree(get(AGENDA + query, 200));
        }

        JsonNode dailyMessage() throws Exception {
            return JSON.readTree(get(DAILY_MESSAGE, 200));
        }

        String get(String path, int status) throws Exception {
            return send(request(path).GET().build(), status);
        }

        String action(long id, String action, String body, boolean csrf, int status) throws Exception {
            return send(actionRequest(id, action, body, csrf), status);
        }

        HttpRequest actionRequest(long id, String action, String body, boolean csrf) throws Exception {
            return postRequest("/api/occurrences/" + id + "/" + action, body, csrf);
        }

        String post(String path, String body, boolean csrf, int status) throws Exception {
            return send(postRequest(path, body, csrf), status);
        }

        JsonNode put(String path, String body, boolean csrf, int status) throws Exception {
            var request = request(path).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body));
            if (csrf) {
                String token = JSON.readTree(get("/api/auth/csrf", 200)).path("token").asString();
                request.header("X-CSRF-TOKEN", token);
            }
            String result = send(request.build(), status);
            return result.isEmpty() ? JSON.nullNode() : JSON.readTree(result);
        }

        private HttpRequest postRequest(String path, String body, boolean csrf) throws Exception {
            var request = request(path);
            if (csrf) {
                String token = JSON.readTree(get("/api/auth/csrf", 200)).path("token").asString();
                request.header("X-CSRF-TOKEN", token);
            }
            if (body != null) {
                request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                request.POST(HttpRequest.BodyPublishers.noBody());
            }
            return request.build();
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10));
        }

        private String send(HttpRequest request, int status) throws Exception {
            return expectStatus(send(request), status);
        }

        private HttpResponse<String> send(HttpRequest request) throws Exception {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        private static String expectStatus(HttpResponse<String> response, int status) {
            assertEquals(status, response.statusCode(), response::body);
            return response.body();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"), ZoneOffset.UTC);
        }
    }

    record MutableTime(AtomicReference<Instant> instant) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {
        @Bean
        MutableTime mutableTime() {
            return new MutableTime(new AtomicReference<>(Instant.EPOCH));
        }

        @Bean
        @Primary
        Clock mutableClock(MutableTime time) {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return Clock.fixed(instant(), zone);
                }

                @Override
                public Instant instant() {
                    return time.instant().get();
                }
            };
        }
    }
}
