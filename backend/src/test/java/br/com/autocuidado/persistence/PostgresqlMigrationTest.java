package br.com.autocuidado.persistence;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import br.com.autocuidado.SelfCareApplication;
import br.com.autocuidado.hair.HairStage;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgresql")
class PostgresqlMigrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 12);
    private static final Set<DayOfWeek> WEEKDAYS = Set.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY);

    @Test
    void migratesAnEmptyDatabaseValidatesAfterRestartAndEnforcesUniqueDatesPerPlan() throws SQLException {
        assertEmptyDatabase();
        migrateToPreviousVersionWithALegacyAccount();
        Long planId;
        Long occurrenceId;
        try (var application = startApplication()) {
            var jdbc = application.getBean(JdbcTemplate.class);
            assertMigrationsAppliedOnce(jdbc);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE username = 'legacy_test'", Integer.class));

            var factory = application.getBean(EntityManagerFactory.class);
            var saved = factory.callInTransaction(manager -> {
                var account = new Account("America/Sao_Paulo");
                manager.persist(account);
                var firstPlan = hairPlan(account.id());
                var secondPlan = hairPlan(account.id());
                manager.persist(firstPlan);
                manager.persist(secondPlan);

                var firstOccurrence = hairOccurrence(account.id(), firstPlan.id());
                manager.persist(firstOccurrence);
                manager.persist(hairOccurrence(account.id(), secondPlan.id()));
                manager.persist(new CareOccurrence(account.id(), null, CareCategory.NAILS,
                        "Trocar esmalte", DATE, null));
                manager.persist(new CareOccurrence(account.id(), null, CareCategory.NAILS,
                        "Remover esmalte", DATE, null));
                return new SavedOccurrence(account.id(), firstPlan.id(), firstOccurrence.id());
            });

            var duplicate = assertThrows(DuplicateKeyException.class, () -> jdbc.update("""
                    INSERT INTO care_occurrences (account_id, plan_id, category, title, planned_date, status, hair_stage)
                    VALUES (?, ?, 'HAIR', 'Lavar cabelo', ?, 'PLANNED', 'WASH_ONLY')
                    """, saved.accountId(), saved.planId(), DATE));
            var cause = (SQLException) duplicate.getMostSpecificCause();
            assertEquals("23505", cause.getSQLState());
            assertTrue(cause.getMessage().contains("care_occurrences_plan_date_key"));
            planId = saved.planId();
            occurrenceId = saved.occurrenceId();
        }

        try (var restarted = startApplication()) {
            var jdbc = restarted.getBean(JdbcTemplate.class);
            assertMigrationsAppliedOnce(jdbc);
            assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM care_occurrences", Integer.class));
            restarted.getBean(EntityManagerFactory.class).runInTransaction(manager -> {
                assertEquals(WEEKDAYS, manager.find(RecurringPlan.class, planId).weekdays());
                assertEquals(DATE, manager.find(CareOccurrence.class, occurrenceId).plannedDate());
            });
        }
    }

    private static void assertEmptyDatabase() throws SQLException {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(url, "Configure SPRING_DATASOURCE_URL com um banco PostgreSQL vazio, exclusivo para o teste.");
        try (var connection = DriverManager.getConnection(url, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD"));
                var statement = connection.createStatement();
                var tables = statement.executeQuery("""
                        SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema()
                        """)) {
            assertFalse(tables.next(), "Use um banco vazio e exclusivo para testar as migrações.");
        }
    }

    private static ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(SelfCareApplication.class)
                .web(WebApplicationType.NONE)
                .run();
    }

    private static void migrateToPreviousVersionWithALegacyAccount() throws SQLException {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        String username = System.getenv("SPRING_DATASOURCE_USERNAME");
        String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        Flyway.configure().dataSource(url, username, password).target("4").load().migrate();
        try (var connection = DriverManager.getConnection(url, username, password);
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO accounts (username, password_hash, time_zone)
                    VALUES ('legacy_test', 'test-hash', 'America/Sao_Paulo')
                    """);
        }
    }

    private static void assertMigrationsAppliedOnce(JdbcTemplate jdbc) {
        for (String version : Set.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14")) {
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success
                    """, Integer.class, version));
        }
    }

    private static RecurringPlan hairPlan(Long accountId) {
        return new RecurringPlan(accountId, CareCategory.HAIR, DATE, 1, DATE.plusDays(6), WEEKDAYS, null, null);
    }

    private static CareOccurrence hairOccurrence(Long accountId, Long planId) {
        return new CareOccurrence(accountId, planId, CareCategory.HAIR, "Lavar cabelo", DATE, HairStage.WASH_ONLY);
    }

    private record SavedOccurrence(Long accountId, Long planId, Long occurrenceId) {}
}
