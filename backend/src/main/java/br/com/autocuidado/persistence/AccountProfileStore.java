package br.com.autocuidado.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountProfileStore {
    private final JdbcTemplate jdbc;

    AccountProfileStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Profile find(long accountId) {
        return jdbc.query("""
                SELECT display_name, photo_jpeg IS NOT NULL, photo_version
                FROM account_profiles WHERE account_id = ?
                """, (rows, index) -> new Profile(rows.getString(1), rows.getBoolean(2), rows.getLong(3)), accountId)
                .stream().findFirst().orElse(new Profile(null, false, 0));
    }

    public void rename(long accountId, String name) {
        jdbc.update("""
                INSERT INTO account_profiles (account_id, display_name) VALUES (?, ?)
                ON CONFLICT (account_id) DO UPDATE SET display_name = EXCLUDED.display_name,
                    updated_at = CURRENT_TIMESTAMP
                """, accountId, name);
    }

    public void replacePhoto(long accountId, byte[] photo) {
        jdbc.update("""
                INSERT INTO account_profiles (account_id, photo_jpeg, photo_version) VALUES (?, ?, 1)
                ON CONFLICT (account_id) DO UPDATE SET photo_jpeg = EXCLUDED.photo_jpeg,
                    photo_version = account_profiles.photo_version + 1, updated_at = CURRENT_TIMESTAMP
                """, accountId, photo);
    }

    public byte[] photo(long accountId) {
        return jdbc.query("SELECT photo_jpeg FROM account_profiles WHERE account_id = ? AND photo_jpeg IS NOT NULL",
                (rows, index) -> rows.getBytes(1), accountId).stream().findFirst().orElse(null);
    }

    public record Profile(String displayName, boolean hasPhoto, long photoVersion) {}
}
