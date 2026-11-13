package br.com.autocuidado.hair;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import br.com.autocuidado.auth.AccountTimeZones;
import br.com.autocuidado.billing.BillingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = {"app.billing.enabled", "app.hair.diary-enabled"}, havingValue = "true")
class HairDiary {
    private final JdbcTemplate jdbc;
    private final BillingService billing;
    private final AccountTimeZones timeZones;
    private final Clock clock;

    HairDiary(JdbcTemplate jdbc, BillingService billing, AccountTimeZones timeZones, Clock clock) {
        this.jdbc = jdbc;
        this.billing = billing;
        this.timeZones = timeZones;
        this.clock = clock;
    }

    @Transactional
    public List<Photo> list(long accountId) {
        billing.requireActive(accountId);
        return jdbc.query("""
                SELECT id, captured_on FROM hair_diary_photos WHERE account_id = ?
                ORDER BY captured_on, created_at, id
                """, (rs, index) -> new Photo(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class)), accountId);
    }

    @Transactional
    public void save(long accountId, UUID id, LocalDate date, MultipartFile photo) {
        billing.requireActive(accountId);
        if (date.getYear() < 1 || date.isAfter(LocalDate.now(clock.withZone(timeZones.current(accountId))))) {
            throw new PhotoRaster.Rejected("Use a data de hoje ou uma data anterior.");
        }
        byte[] content = PhotoRaster.normalize(photo);
        int inserted = jdbc.update("""
                INSERT INTO hair_diary_photos (account_id, id, captured_on, created_at, content) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (account_id, id) DO NOTHING
                """, accountId, id, date, clock.instant().atOffset(ZoneOffset.UTC), content);
        if (inserted == 0 && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM hair_diary_photos
                WHERE account_id = ? AND id = ? AND captured_on = ? AND content = ?)
                """, Boolean.class, accountId, id, date, content))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A foto já foi registrada com dados diferentes.");
        }
    }

    @Transactional
    public byte[] photo(long accountId, UUID id) {
        billing.requireActive(accountId);
        return jdbc.query("SELECT content FROM hair_diary_photos WHERE account_id = ? AND id = ?",
                (rs, index) -> rs.getBytes(1), accountId, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public void delete(long accountId, UUID id) {
        billing.requireActive(accountId);
        if (jdbc.update("DELETE FROM hair_diary_photos WHERE account_id = ? AND id = ?", accountId, id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    record Photo(UUID id, LocalDate date) {}
}
