package br.com.autocuidado.auth;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

@Tag("postgresql")
class LoginRateLimitPostgresqlTest {
    @Test
    void concurrentAttemptsShareTheBudgetAcrossInstancesAndResetOnlyAfterExpiry() throws Exception {
        String database = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(database, "Configure um PostgreSQL exclusivo para o teste.");
        String username = System.getenv("SPRING_DATASOURCE_USERNAME");
        String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        String schema = "rate_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(database, username, password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
                var source = new DriverManagerDataSource(url, username, password);
                Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
                var jdbc = new JdbcTemplate(source);
                var first = new LoginRateLimit(jdbc);
                var second = new LoginRateLimit(jdbc);
                var attempts = new ArrayList<Callable<Boolean>>();
                for (int index = 0; index < 24; index++) {
                    var limiter = index % 2 == 0 ? first : second;
                    attempts.add(() -> limiter.tryAcquire("login:synthetic@example.test"));
                }
                int accepted = 0;
                try (var threads = Executors.newFixedThreadPool(8)) {
                    for (var result : threads.invokeAll(attempts)) {
                        if (result.get()) accepted++;
                    }
                }
                assertEquals(10, accepted);
                assertFalse(first.tryAcquire("login:synthetic@example.test"));
                assertTrue(second.tryAcquire("login:another@example.test"));
                assertTrue(first.tryAcquire("auth-ip:192.0.2.1", 2, 60));
                assertTrue(second.tryAcquire("auth-ip:192.0.2.1", 2, 60));
                assertFalse(first.tryAcquire("auth-ip:192.0.2.1", 2, 60));
                assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM login_rate_limits WHERE octet_length(identifier_hash) = 32", Integer.class));
                jdbc.update("UPDATE login_rate_limits SET expires_at = statement_timestamp() - interval '1 second'");
                assertTrue(second.tryAcquire("login:synthetic@example.test"));
                assertEquals(1, jdbc.queryForObject("SELECT attempts FROM login_rate_limits WHERE attempts = 1 AND expires_at > statement_timestamp()", Integer.class));
                first.removeExpired();
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM login_rate_limits", Integer.class));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
