package br.com.autocuidado.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class HairProfileStore {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    HairProfileStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public JsonNode find(long accountId) {
        return jdbc.query("SELECT answers FROM hair_profiles WHERE account_id = ?",
                (rows, index) -> json.readTree(rows.getString(1)), accountId)
                .stream().findFirst().orElseGet(json::createObjectNode);
    }

    @Transactional
    public void replace(long accountId, JsonNode answers) {
        jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, accountId);
        if (answers.isEmpty()) {
            jdbc.update("DELETE FROM hair_profiles WHERE account_id = ?", accountId);
            return;
        }
        jdbc.update("""
                INSERT INTO hair_profiles (account_id, answers) VALUES (?, CAST(? AS jsonb))
                ON CONFLICT (account_id) DO UPDATE SET answers = EXCLUDED.answers
                """, accountId, json.writeValueAsString(answers));
    }
}
