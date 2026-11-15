package br.com.autocuidado;

import java.sql.SQLException;

import org.flywaydb.core.Flyway;

final class DatabaseMigration {

    private DatabaseMigration() {
    }

    static void run() throws SQLException {
        var flyway = Flyway.configure()
                .dataSource(System.getenv("SPRING_DATASOURCE_URL"),
                        System.getenv("SPRING_DATASOURCE_USERNAME"),
                        System.getenv("SPRING_DATASOURCE_PASSWORD"))
                .defaultSchema("public")
                .load();
        flyway.migrate();

        try (var connection = flyway.getConfiguration().getDataSource().getConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO autocuidado_runtime");
            statement.execute("REVOKE ALL ON TABLE public.flyway_schema_history FROM autocuidado_runtime");
            statement.execute("REVOKE ALL ON TABLE public.app_owner FROM autocuidado_runtime");
            statement.execute("GRANT SELECT ON TABLE public.app_owner TO autocuidado_runtime");
            statement.execute("GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO autocuidado_runtime");
            connection.commit();
        }
    }
}
