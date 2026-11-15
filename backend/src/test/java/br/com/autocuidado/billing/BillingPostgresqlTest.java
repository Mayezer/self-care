package br.com.autocuidado.billing;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import br.com.autocuidado.SelfCareApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@Tag("postgresql")
class BillingPostgresqlTest {
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final String SECRET = "whsec_fixture";
    private static final String PRICE = "price_monthly";
    private static final String CUSTOMER = "cus_owner";
    private static final String STRIPE = "https://stripe.example.invalid/v1";
    private static final String RETURN = "http://127.0.0.1:4225/configuracoes/assinatura";
    private static final String CHECKOUT = "https://checkout.stripe.com/c/pay/cs_test_fixture";
    private static final String PORTAL = "https://billing.stripe.com/p/session/fixture";
    private static final String EMPTY = "{\"data\":[],\"has_more\":false}";
    private static final String SUBSCRIPTIONS = "/subscriptions?customer=cus_owner&status=all&limit=100";
    private static final String SESSIONS = "/checkout/sessions?customer=cus_owner&limit=1";
    private static final String STATE = "/api/subscription";
    private static final String FORUM = "/api/forum/participation";
    private static final String PASSWORD = "FicticiaE21642!";
    private static final JsonMapper JSON = new JsonMapper();

    @Test
    void reconcilesCheckoutAndSignedEventsWithoutLeakingOrRestoringOldAccess() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        String user = System.getenv("SPRING_DATASOURCE_USERNAME");
        String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        var source = new DriverManagerDataSource(database, user, password);
        var admin = new JdbcTemplate(source);
        String schema = "billing_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String schemaUrl = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try {
            Flyway.configure().dataSource(schemaUrl, user, password).schemas(schema).target("9").load().migrate();
            var previous = new JdbcTemplate(new DriverManagerDataSource(schemaUrl, user, password));
            previous.update("""
                    INSERT INTO accounts (username, password_hash, time_zone)
                    VALUES ('billing_owner', 'temporary', 'America/Sao_Paulo'), ('billing_other', 'temporary', 'America/Sao_Paulo')
                    """);
            previous.update("""
                    INSERT INTO care_occurrences (account_id, category, title, planned_date, status)
                    SELECT id, 'SKIN', 'Cuidado fictício', '2026-11-11', 'PLANNED' FROM accounts WHERE username = 'billing_owner'
                    """);

            try (var application = new SpringApplicationBuilder(SelfCareApplication.class, ProviderFixture.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + schemaUrl,
                            "--app.forum.enabled=true", "--app.forum.public-reading-enabled=true",
                            "--app.billing.enabled=true", "--app.billing.stripe.secret-key=sk_test_fixture",
                            "--app.billing.stripe.price-id=" + PRICE, "--app.billing.stripe.webhook-secret=" + SECRET,
                            "--app.billing.return-url=" + RETURN)) {
                var jdbc = application.getBean(JdbcTemplate.class);
                var fixture = application.getBean(Provider.class).server();
                var encoder = application.getBean(PasswordEncoder.class);
                jdbc.update("UPDATE accounts SET password_hash = ?", encoder.encode(PASSWORD));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM care_occurrences", Integer.class));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '10' AND success", Integer.class));
                String origin = "http://127.0.0.1:" + application.getEnvironment().getProperty("local.server.port");
                var anonymous = new Session(origin);
                var owner = new Session(origin);
                var other = new Session(origin);
                assertEquals(401, anonymous.get(STATE).statusCode());
                owner.login("billing_owner");
                other.login("billing_other");
                assertFalse(owner.state().path("active").asBoolean());
                assertEquals("INACTIVE", json(owner.get(FORUM), 200).path("state").asString());
                assertEquals(990, owner.state().path("monthlyPriceCents").asInt());
                assertEquals(403, owner.postWithoutCsrf(STATE + "/checkout").statusCode());

                fixture.expect(requestTo(STRIPE + "/customers")).andExpect(method(HttpMethod.POST))
                        .andExpect(header("Idempotency-Key", startsWith("customer-")))
                        .andRespond(withSuccess("{\"id\":\"cus_owner\"}", MediaType.APPLICATION_JSON));
                expectGet(fixture, SUBSCRIPTIONS, EMPTY);
                expectGet(fixture, SESSIONS, EMPTY);
                expectGet(fixture, "/prices/" + PRICE, price(1090));
                assertEquals(503, owner.post(STATE + "/checkout").statusCode());
                fixture.verify();
                fixture.reset();

                expectGet(fixture, SUBSCRIPTIONS, EMPTY);
                expectGet(fixture, SESSIONS, EMPTY);
                expectGet(fixture, "/prices/" + PRICE, price(990));
                fixture.expect(requestTo(STRIPE + "/checkout/sessions")).andExpect(method(HttpMethod.POST))
                        .andExpect(header("Idempotency-Key", "checkout-cus_owner-first"))
                        .andExpect(content().string(containsString("line_items%5B0%5D%5Bprice%5D=" + PRICE)))
                        .andExpect(content().string(containsString("mode=subscription")))
                        .andRespond(withSuccess("{\"url\":\"" + CHECKOUT + "\"}", MediaType.APPLICATION_JSON));
                expectGet(fixture, SUBSCRIPTIONS, EMPTY);
                expectGet(fixture, SESSIONS, """
                        {"data":[{"id":"cs_test_fixture","customer":"cus_owner","status":"open","url":"%s"}]}
                        """.formatted(CHECKOUT));
                expectGet(fixture, SUBSCRIPTIONS, EMPTY);
                expectGet(fixture, SESSIONS, """
                        {"data":[{"id":"cs_test_fixture","customer":"cus_owner","status":"complete","subscription":"sub_fixture"}]}
                        """);
                expectGet(fixture, "/subscriptions/sub_fixture", "{\"customer\":\"cus_owner\",\"status\":\"incomplete\"}");
                assertEquals(CHECKOUT, json(owner.post(STATE + "/checkout"), 200).path("url").asString());
                assertEquals(CHECKOUT, json(owner.post(STATE + "/checkout"), 200).path("url").asString());
                assertEquals(409, owner.post(STATE + "/checkout").statusCode());
                assertEquals(409, other.post(STATE + "/portal").statusCode());
                fixture.verify();
                fixture.reset();

                String activation = event("evt_activation", "customer.subscription.created", CUSTOMER, "active");
                assertEquals(400, anonymous.webhook(activation, "t=1,v1=bad").statusCode());
                assertEquals(400, anonymous.webhook(activation, signature(activation, NOW.minusSeconds(301))).statusCode());
                String foreign = event("evt_foreign", "customer.subscription.created", "cus_foreign", "active");
                assertEquals(204, anonymous.webhook(foreign).statusCode());
                fixture.expect(requestTo(STRIPE + SUBSCRIPTIONS)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
                expectGet(fixture, SUBSCRIPTIONS, subscription("active", PRICE, false, NOW.plusSeconds(3600)));
                assertEquals(503, anonymous.webhook(activation).statusCode());
                assertFalse(owner.state().path("active").asBoolean());
                try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                    var first = threads.submit(() -> anonymous.webhook(activation).statusCode());
                    var duplicate = threads.submit(() -> anonymous.webhook(activation).statusCode());
                    assertEquals(204, first.get(15, TimeUnit.SECONDS));
                    assertEquals(204, duplicate.get(15, TimeUnit.SECONDS));
                }
                assertTrue(owner.state().path("active").asBoolean());
                assertEquals("ACTIVE", json(owner.get(FORUM), 200).path("state").asString());
                assertFalse(other.state().path("active").asBoolean());
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM stripe_webhook_events", Integer.class));
                fixture.verify();
                fixture.reset();

                expectGet(fixture, SUBSCRIPTIONS, subscription("active", PRICE, false, NOW.plusSeconds(3600)));
                fixture.expect(requestTo(STRIPE + "/billing_portal/sessions")).andExpect(method(HttpMethod.POST))
                        .andExpect(content().string(containsString("customer=cus_owner")))
                        .andRespond(withSuccess("{\"url\":\"" + PORTAL + "\"}", MediaType.APPLICATION_JSON));
                expectGet(fixture, SUBSCRIPTIONS, subscription("active", PRICE, true, NOW.plusSeconds(3600)));
                expectGet(fixture, SUBSCRIPTIONS, subscription("active", "price_different", false, NOW.plusSeconds(3600)));
                expectGet(fixture, SUBSCRIPTIONS, subscription("active", PRICE, false, NOW.minusSeconds(1)));
                expectGet(fixture, SUBSCRIPTIONS, subscription("canceled", PRICE, false, NOW.plusSeconds(3600)));
                expectGet(fixture, SUBSCRIPTIONS, subscription("canceled", PRICE, false, NOW.plusSeconds(3600)));
                expectGet(fixture, SUBSCRIPTIONS, subscription("active", PRICE, false, NOW.plusSeconds(3600)));
                expectGet(fixture, SUBSCRIPTIONS, subscription("active", PRICE, false, NOW.plusSeconds(3600)));
                expectGet(fixture, SUBSCRIPTIONS, subscription("canceled", PRICE, false, NOW.plusSeconds(3600)));
                expectGet(fixture, SUBSCRIPTIONS, subscription("active", PRICE, false, NOW.plusSeconds(3600)));
                assertEquals(409, owner.post(STATE + "/checkout").statusCode());
                assertEquals(PORTAL, json(owner.post(STATE + "/portal"), 200).path("url").asString());
                assertTrue(json(owner.post(STATE + "/refresh"), 200).path("cancelAtPeriodEnd").asBoolean());
                assertEquals("ACTIVE", json(owner.get(FORUM), 200).path("state").asString());
                assertEquals(204, anonymous.webhook(event("evt_changed", "customer.subscription.updated", CUSTOMER, "active")).statusCode());
                assertFalse(owner.state().path("active").asBoolean());
                assertTrue(owner.state().path("canManage").asBoolean());
                assertFalse(json(owner.post(STATE + "/refresh"), 200).path("active").asBoolean());
                assertEquals(204, anonymous.webhook(event("evt_canceled", "customer.subscription.deleted", CUSTOMER, "canceled")).statusCode());
                assertEquals(204, anonymous.webhook(event("evt_older", "customer.subscription.updated", CUSTOMER, "active")).statusCode());
                assertFalse(owner.state().path("active").asBoolean());
                assertFalse(owner.state().path("canManage").asBoolean());
                assertEquals("INACTIVE", json(owner.get(FORUM), 200).path("state").asString());
                jdbc.update("""
                        INSERT INTO hair_diary_photos (account_id, id, captured_on, created_at, content)
                        SELECT account_id, ?, DATE '2026-10-01', CURRENT_TIMESTAMP, decode('00', 'hex')
                        FROM billing_accounts WHERE stripe_customer_id = 'cus_owner'
                        """, UUID.randomUUID());
                assertTrue(json(owner.post(STATE + "/refresh"), 200).path("active").asBoolean());
                assertEquals("ACTIVE", json(owner.get(FORUM), 200).path("state").asString());
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM hair_diary_photos", Integer.class));
                var maintenance = application.getBean(BillingDiaryMaintenance.class);
                for (boolean active : new boolean[] {true, false}) {
                    jdbc.update("UPDATE billing_accounts SET access_until = ? WHERE stripe_customer_id = 'cus_owner'",
                            NOW.minusSeconds(31L * 24 * 3600).atOffset(ZoneOffset.UTC));
                    maintenance.confirmExpiredAccess();
                    assertEquals(active, owner.state().path("active").asBoolean());
                    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM hair_diary_photos", Integer.class));
                }
                jdbc.update("""
                        UPDATE billing_accounts SET access_until = NULL, access_lost_at = ? WHERE stripe_customer_id = 'cus_owner'
                        """, NOW.minusSeconds(30L * 24 * 3600).atOffset(ZoneOffset.UTC));
                assertTrue(json(owner.post(STATE + "/refresh"), 200).path("active").asBoolean());
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM hair_diary_photos", Integer.class));
                fixture.verify();
            }
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static void expectGet(MockRestServiceServer fixture, String path, String body) {
        fixture.expect(requestTo(STRIPE + path)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String price(int amount) {
        return """
                {"id":"price_monthly","active":true,"currency":"brl","unit_amount":%s,
                 "recurring":{"interval":"month","interval_count":1}}
                """.formatted(amount);
    }

    private static String subscription(String state, String price, boolean ending, Instant until) {
        return """
                {"data":[{"id":"sub_fixture","customer":"cus_owner","status":"%s","cancel_at_period_end":%s,
                "items":{"data":[{"price":{"id":"%s"},"current_period_end":%s}],"has_more":false}}],"has_more":false}
                """.formatted(state, ending, price, until.getEpochSecond());
    }

    private static String event(String id, String type, String customer, String status) {
        return """
                {"id":"%s","type":"%s","livemode":false,"data":{"object":{"customer":"%s","status":"%s"}}}
                """.formatted(id, type, customer, status);
    }

    private static String signature(String body, Instant at) throws Exception {
        String time = Long.toString(at.getEpochSecond());
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + time + ",v1=" + HexFormat.of().formatHex(mac.doFinal((time + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonNode json(HttpResponse<String> result, int expectedStatus) {
        assertEquals(expectedStatus, result.statusCode(), result.body());
        return JSON.readTree(result.body());
    }

    private record Provider(StripeClient client, MockRestServiceServer server) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderFixture {
        @Bean
        Provider provider() {
            var builder = RestClient.builder().baseUrl(STRIPE);
            var server = MockRestServiceServer.bindTo(builder).build();
            return new Provider(new StripeClient(builder.build(), PRICE, RETURN, false), server);
        }

        @Bean
        @Primary
        StripeClient fixtureClient(Provider provider) {
            return provider.client();
        }

        @Bean
        @Primary
        Clock billingClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    private static final class Session {
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        private final String origin;

        Session(String origin) {
            this.origin = origin;
        }

        void login(String user) throws Exception {
            var csrf = json(get("/api/auth/csrf"), 200);
            assertEquals(204, send(request("/api/auth/login")
                    .header(csrf.path("headerName").asString(), csrf.path("token").asString())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=" + PASSWORD))
                    .build()).statusCode());
        }

        JsonNode state() throws Exception {
            return json(get(STATE), 200);
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(request(path).GET().build());
        }

        HttpResponse<String> post(String path) throws Exception {
            var csrf = json(get("/api/auth/csrf"), 200);
            return send(request(path).header(csrf.path("headerName").asString(), csrf.path("token").asString())
                    .POST(HttpRequest.BodyPublishers.noBody()).build());
        }

        HttpResponse<String> postWithoutCsrf(String path) throws Exception {
            return send(request(path).POST(HttpRequest.BodyPublishers.noBody()).build());
        }

        HttpResponse<String> webhook(String body) throws Exception {
            return webhook(body, signature(body, NOW));
        }

        HttpResponse<String> webhook(String body, String signature) throws Exception {
            return send(request(StripeWebhook.PATH).header("Stripe-Signature", signature)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create(origin + path));
        }

        private HttpResponse<String> send(HttpRequest request) throws Exception {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
