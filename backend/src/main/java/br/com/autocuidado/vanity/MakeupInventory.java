package br.com.autocuidado.vanity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class MakeupInventory {
    enum Status { OWNED, RUNNING_LOW, TO_BUY }

    record Fields(String name, String category, Status status) {}
    record Item(UUID id, String name, String category, Status status) {
        boolean matches(Fields fields) {
            return new Fields(name, category, status).equals(fields);
        }
    }

    private static final RowMapper<Item> ITEM_ROW = (row, index) -> new Item(row.getObject("id", UUID.class),
            row.getString("name"), row.getString("category"), Status.valueOf(row.getString("status")));

    private final JdbcTemplate jdbc;

    MakeupInventory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Item> list(long accountId) {
        return jdbc.query("""
                SELECT id, name, category, status FROM makeup_items
                WHERE account_id = ? ORDER BY lower(name), id
                """, ITEM_ROW, accountId);
    }

    @Transactional
    public Optional<Item> add(long accountId, UUID id, Fields fields) {
        var inserted = jdbc.query("""
                INSERT INTO makeup_items (account_id, id, name, category, status) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (account_id, id) DO NOTHING RETURNING id, name, category, status
                """, ITEM_ROW, accountId, id, fields.name(), fields.category(), fields.status().name()).stream().findFirst();
        return inserted.or(() -> jdbc.query("""
                SELECT id, name, category, status FROM makeup_items WHERE account_id = ? AND id = ?
                """, ITEM_ROW, accountId, id).stream().findFirst().filter(item -> item.matches(fields)));
    }

    Optional<Item> update(long accountId, UUID id, Fields fields) {
        return jdbc.query("""
                UPDATE makeup_items SET name = ?, category = ?, status = ? WHERE account_id = ? AND id = ?
                RETURNING id, name, category, status
                """, ITEM_ROW, fields.name(), fields.category(), fields.status().name(), accountId, id).stream().findFirst();
    }

    Optional<Item> markPurchased(long accountId, UUID id) {
        return jdbc.query("""
                UPDATE makeup_items SET status = 'OWNED' WHERE account_id = ? AND id = ?
                RETURNING id, name, category, status
                """, ITEM_ROW, accountId, id).stream().findFirst();
    }

    boolean delete(long accountId, UUID id) {
        return jdbc.update("DELETE FROM makeup_items WHERE account_id = ? AND id = ?", accountId, id) == 1;
    }
}
