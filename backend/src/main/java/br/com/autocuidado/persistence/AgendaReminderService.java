package br.com.autocuidado.persistence;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import br.com.autocuidado.auth.AccountTimeZones;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgendaReminderService {
    private static final Preferences OFF = new Preferences(false, Set.of(), null);
    private final JdbcTemplate jdbc;
    private final AccountTimeZones timeZones;
    private final boolean available;

    AgendaReminderService(JdbcTemplate jdbc, AccountTimeZones timeZones,
                          @Value("${app.agenda.reminders-enabled:false}") boolean available) {
        this.jdbc = jdbc;
        this.timeZones = timeZones;
        this.available = available;
    }

    public boolean available() {
        return available;
    }

    Preferences preferences(long accountId) {
        if (!available) return OFF;
        return jdbc.query("""
                SELECT enabled, categories, reminder_time FROM agenda_reminder_preferences WHERE account_id = ?
                """, (rows, index) -> new Preferences(rows.getBoolean("enabled"),
                        Arrays.stream((String[]) rows.getArray("categories").getArray())
                                .map(CareCategory::valueOf).collect(Collectors.toUnmodifiableSet()),
                        rows.getObject("reminder_time", LocalTime.class)), accountId)
                .stream().findFirst().orElse(OFF);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Settings load(long accountId) {
        return new Settings(preferences(accountId), timeZones.current(accountId).getId());
    }

    @Transactional
    public Settings replace(long accountId, Preferences preferences, String timeZone) {
        timeZones.update(accountId, timeZone);
        String categories = preferences.categories().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        jdbc.update("""
                INSERT INTO agenda_reminder_preferences (account_id, enabled, categories, reminder_time)
                VALUES (?, ?, string_to_array(?, ','), CAST(? AS time))
                ON CONFLICT (account_id) DO UPDATE SET enabled = EXCLUDED.enabled,
                    categories = EXCLUDED.categories, reminder_time = EXCLUDED.reminder_time
                """, accountId, preferences.enabled(), categories,
                preferences.time() == null ? null : preferences.time().toString());
        return new Settings(preferences, timeZone);
    }

    public record Preferences(boolean enabled, Set<CareCategory> categories, LocalTime time) {}
    public record Settings(Preferences preferences, String timeZone) {}
}
