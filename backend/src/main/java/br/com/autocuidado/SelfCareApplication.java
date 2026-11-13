package br.com.autocuidado;

import java.sql.SQLException;
import java.time.Clock;

import br.com.autocuidado.recurrence.RecurrenceDateGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SelfCareApplication {

    public static void main(String[] args) throws SQLException {
        if (args.length == 1 && args[0].equals("--migrate")) {
            DatabaseMigration.run();
            return;
        }
        SpringApplication.run(SelfCareApplication.class, args);
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    RecurrenceDateGenerator recurrenceDateGenerator(Clock clock) {
        return new RecurrenceDateGenerator(clock);
    }
}
