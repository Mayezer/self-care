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
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void keepsForumDraftsPrivateAndEnforcesTheSharedWeeklyQuotaAcrossSessions() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um banco PostgreSQL exclusivo para o teste.");
        String schema = "e212_" + UUID.randomUUID().toString().replace("-", "");
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String path = "/api/forum/posts";
        String review = """
                {"kind":"REVIEW","category":"SKIN","body":"Relato fictício","product":"Produto de teste","rating":5}
                """;
        String story = """
                {"kind":"STORY","category":"HAIR","body":"Rotina fictícia"}
                """;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                try (var previous = new SpringApplicationBuilder(SelfCareApplication.class).web(WebApplicationType.NONE)
                        .run("--spring.datasource.url=" + url, "--spring.flyway.target=8")) {
                    var jdbc = previous.getBean(JdbcTemplate.class);
                    var hash = previous.getBean(PasswordEncoder.class).encode(PASSWORD);
                    insertAccount(jdbc, "forum_owner", hash, "Asia/Tokyo");
                    insertAccount(jdbc, "forum_other", hash, "UTC");
                }
                try (var app = new SpringApplicationBuilder(SelfCareApplication.class, MutableClockConfiguration.class)
                        .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                                "--app.forum.enabled=true")) {
                    var time = app.getBean(MutableTime.class).instant();
                    time.set(Instant.parse("2026-11-09T02:59:59Z"));
                    var owner = new Session(origin(app));
                    var device = new Session(origin(app));
                    var other = new Session(origin(app));
                    var draft = path + "/" + UUID.randomUUID();
                    var second = path + "/" + UUID.randomUUID();
                    owner.get(path, 401);
                    owner.login("forum_owner");
                    device.login("forum_owner");
                    other.login("forum_other");
                    owner.put(draft, review, false, 403);
                    for (String invalid : List.of("0", "6", "1.5", "5.0", "\"5\"")) {
                        assertTrue(JSON.readTree(owner.post(draft + "/submit", review.replace("\"rating\":5",
                                "\"rating\":" + invalid), true, 400)).at("/errors/rating").isString());
                    }
                    assertEquals("DRAFT", owner.put(draft, "{\"kind\":\"REVIEW\"}", true, 200).path("status").asString());
                    assertEquals("DRAFT", owner.put(draft, review, true, 200).path("status").asString());
                    assertTrue(JSON.readTree(other.get(path, 200)).path("posts").isEmpty());
                    assertTrue(JSON.readTree(owner.get(path, 200)).path("nextAvailableAt").isNull());
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
                    assertEquals("2026-11-09T00:00:00-03:00", limited.path("nextAvailableAt").asString());
                    assertEquals("IN_REVIEW", accepted.path("status").asString());
                    assertTrue(JSON.readTree(other.get(path, 200)).path("posts").isEmpty());
                    other.post(winner + "/withdraw", null, true, 404);
                    owner.post(winner + "/withdraw", null, true, 204);
                    owner.post(loser + "/submit", losingText, true, 429);
                    time.set(Instant.parse("2026-11-09T03:00:00Z"));
                    assertEquals(accepted, JSON.readTree(owner.post(winner + "/submit", winningText, true, 200)));
                    assertTrue(JSON.readTree(owner.get(path, 200)).path("nextAvailableAt").isNull());
                    assertEquals("IN_REVIEW", JSON.readTree(owner.post(loser + "/submit", losingText, true, 200))
                            .path("status").asString());
                    var optionalRating = review.replace(",\"rating\":5", "");
                    assertTrue(JSON.readTree(other.post(second + "/submit", optionalRating, true, 200))
                            .at("/content/rating").isNull());
                    assertEquals(2, JSON.readTree(owner.get(path, 200)).path("posts").size());
                    assertEquals(200, owner.send(owner.request("/api/agenda").GET().build()).statusCode());
                }
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
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
                {"enabled":true,"categories":["SKIN"],"time":"19:15","timeZone":"UTC"}
                """;

        try (var connection = DriverManager.getConnection(database, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                long owner;
                try (var previous = new SpringApplicationBuilder(SelfCareApplication.class).web(WebApplicationType.NONE)
                        .run("--spring.datasource.url=" + url, "--spring.flyway.target=6")) {
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
                            {"accountId":%d,"enabled":false,"categories":[],"time":null,"timeZone":"UTC"}
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
                    session.action(skin, "complete", "{\"completedOn\":\"2026-11-09\"}", true, 204);
                    assertTrue(item(session.week(""), skin).path("reminderTime").isNull());
                    session.action(skin, "undo", null, true, 204);
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
                    var week = device.week("");
                    assertEquals("2026-11-10", week.path("today").asString());
                    assertEquals("UTC", JSON.readTree(device.get("/api/agenda", 200)).path("timeZone").asString());
                    assertEquals("2026-11-11", item(week, skin).path("date").asString());
                    assertEquals("19:15", item(week, skin).path("reminderTime").asString());
                    var overdue = item(week, "overdue", makeup);
                    assertEquals("2026-11-09", overdue.path("date").asString());
                    assertTrue(overdue.path("reminderTime").isNull());
                    assertEquals("2026-11-09", other.week("").path("today").asString());

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
                    assertEquals("UTC", JSON.readTree(session.get("/api/agenda", 200)).path("timeZone").asString());
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
        return new SpringApplicationBuilder(SelfCareApplication.class, FixedClock.class)
                .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
                        "--app.agenda.reminders-enabled=" + enabled);
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
                        .run("--spring.datasource.url=" + url, "--spring.flyway.target=5")) {
                    var jdbc = previous.getBean(JdbcTemplate.class);
                    var hash = previous.getBean(PasswordEncoder.class).encode(PASSWORD);
                    owner = insertAccount(jdbc, "daily_sp", hash, "America/Sao_Paulo");
                    other = insertAccount(jdbc, "daily_utc", hash, "UTC");
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
                    assertEquals(start.plusDays(1), jdbc.queryForObject(
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
                    jdbc.update("UPDATE accounts SET time_zone = 'Pacific/Honolulu' WHERE id = ?", owner);
                    clock.set(midnight);
                    var travel = new Session(origin);
                    travel.login("daily_sp");
                    assertEquals(first, travel.dailyMessage());
                    clock.set(midnight.plus(Duration.ofDays(1)));
                    assertEquals(first, travel.dailyMessage());
                    clock.set(midnight.plus(Duration.ofDays(2)));
                    assertEquals(next, travel.dailyMessage());
                    jdbc.update("UPDATE accounts SET time_zone = 'America/Sao_Paulo' WHERE id = ?", owner);
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
                insertAccount(jdbc, "streak_utc", hash, "UTC");
                var origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
                var session = new Session(origin);
                var utc = new Session(origin);
                session.get(AGENDA, 401);
                session.login("streak_sp");
                utc.login("streak_utc");
                assertEquals("2026-11-09", session.week("").path("today").asString());
                assertEquals("2026-11-10", utc.week("").path("today").asString());

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
                        {"title":"Esmalte","completedOn":"2026-11-08"}
                        """, true, 201);
                assertStreak(utc, "", 0);
                utc.post(HISTORY + "/makeup", """
                        {"title":"Maquiagem leve","completedOn":"2026-11-09"}
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
                long other = insertAccount(jdbc, "other_direct", hash, "UTC");
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
                        {"title":"Praticar delineado","completedOn":"2026-11-10"}
                        """, true, 201);
                var otherHistory = JSON.readTree(otherSession.get(HISTORY, 200));
                assertEquals(1, otherHistory.size());
                assertEquals("2026-11-10", otherHistory.at("/0/actionDate").asString());
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
                long other = insertAccount(jdbc, "other_beauty", hash, "UTC");
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
                assertTrue(JSON.readTree(otherSession.post(MAKEUP, makeup, true, 400)).at("/errors/plannedDate").isString());
                assertTrue(JSON.readTree(otherSession.post(NAILS, nails, true, 400)).at("/errors/plannedDate").isString());
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
                long other = insertAccount(jdbc, "other_skin", hash, "UTC");
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
                assertEquals("2026-11-10", JSON.readTree(otherSession.get("/api/agenda", 200)).path("today").asString());
                session.post(SKIN, morning, false, 403);

                var invalid = JSON.readTree(session.post(SKIN, """
                        {"title":"   ","plannedDate":"2026-02-30","period":"DAY"}
                        """, true, 400));
                assertTrue(invalid.at("/errors/title").isString());
                assertTrue(invalid.at("/errors/plannedDate").isString());
                assertTrue(invalid.at("/errors/period").isString());
                assertTrue(JSON.readTree(otherSession.post(SKIN, morning, true, 400)).at("/errors/plannedDate").isString());
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
                insertAccount(jdbc, "other_routine", hash, "UTC");
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
                var past = otherSession.post(SKIN_PLANS, morning.replace("2026-11-11", "2026-11-09"), true, 400);
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
                long other = insertAccount(jdbc, "other_agenda", hash, "UTC");
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
                assertEquals("2026-11-10", otherWeek.path("today").asString());
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
                insertAccount(jdbc, "other_actions", hash, "UTC");
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
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var first = executor.submit(() -> session.action(today, "complete", sunday, true, 204));
                    var second = executor.submit(() -> secondSession.action(today, "complete", sunday, true, 204));
                    first.get();
                    second.get();
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
                insertAccount(jdbc, "other_reschedule", hash, "UTC");
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
                long other = insertAccount(jdbc, "other_history", hash, "UTC");
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
                insertAccount(jdbc, "other_closure", hash, "UTC");
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
