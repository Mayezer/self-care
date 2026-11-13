package br.com.autocuidado.forum;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import br.com.autocuidado.persistence.CareCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ForumPosts {
    private static final ZoneId FORUM_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final String SAVE = """
            INSERT INTO forum_posts (account_id, id, kind, category, body, product, rating,
                                     status, accepted_at, accepted_week)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id, id) DO UPDATE SET kind = EXCLUDED.kind, category = EXCLUDED.category,
                body = EXCLUDED.body, product = EXCLUDED.product, rating = EXCLUDED.rating,
                status = EXCLUDED.status, accepted_at = EXCLUDED.accepted_at, accepted_week = EXCLUDED.accepted_week
            WHERE forum_posts.status = 'DRAFT'
            RETURNING *
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    ForumPosts(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Page list(long accountId) {
        var posts = jdbc.query("""
                SELECT * FROM forum_posts WHERE account_id = ?
                ORDER BY accepted_at DESC NULLS FIRST, id
                """, ForumPosts::mapPost, accountId);
        var week = weekStart(OffsetDateTime.now(clock.withZone(FORUM_ZONE)).toLocalDate());
        boolean used = posts.stream().anyMatch(post -> post.acceptedAt() != null
                && week.equals(weekStart(post.acceptedAt().atZoneSameInstant(FORUM_ZONE).toLocalDate())));
        return new Page(posts, used ? nextWindow(week) : null);
    }

    Post draft(long accountId, UUID id, Content content) {
        return save(accountId, id, content, Status.DRAFT, null, null);
    }

    @Transactional
    Submission submit(long accountId, UUID id, Content content) {
        // Serialize submissions for this account before reading the quota or choosing the week.
        jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", Long.class, accountId);
        var existing = jdbc.query("SELECT * FROM forum_posts WHERE account_id = ? AND id = ?",
                ForumPosts::mapPost, accountId, id).stream().findFirst().orElse(null);
        if (existing != null && existing.acceptedAt() != null) {
            return new Submission(new Post(id, existing.content(), Status.IN_REVIEW, existing.acceptedAt()), null);
        }
        var now = OffsetDateTime.now(clock.withZone(FORUM_ZONE));
        var week = weekStart(now.toLocalDate());
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM forum_posts WHERE account_id = ? AND accepted_week = ?)
                """, Boolean.class, accountId, week))) {
            return new Submission(null, nextWindow(week));
        }
        return new Submission(save(accountId, id, content, Status.IN_REVIEW, now, week), null);
    }

    boolean withdraw(long accountId, UUID id) {
        return jdbc.update("""
                UPDATE forum_posts SET status = 'REMOVED'
                WHERE account_id = ? AND id = ? AND status IN ('IN_REVIEW', 'PUBLISHED', 'REMOVED')
                """, accountId, id) == 1;
    }

    private Post save(long accountId, UUID id, Content content, Status status,
                      OffsetDateTime acceptedAt, LocalDate week) {
        return jdbc.query(SAVE, ForumPosts::mapPost, accountId, id, content.kind().name(),
                content.category() == null ? null : content.category().name(), content.body(), content.product(),
                content.rating(), status.name(), acceptedAt, week).stream().findFirst().orElse(null);
    }

    private static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static OffsetDateTime nextWindow(LocalDate week) {
        return week.plusWeeks(1).atStartOfDay(FORUM_ZONE).toOffsetDateTime();
    }

    private static Post mapPost(ResultSet row, int index) throws SQLException {
        String category = row.getString("category");
        return new Post(row.getObject("id", UUID.class), new Content(Kind.valueOf(row.getString("kind")),
                category == null ? null : CareCategory.valueOf(category), row.getString("body"),
                row.getString("product"), row.getObject("rating", Integer.class)),
                Status.valueOf(row.getString("status")), row.getObject("accepted_at", OffsetDateTime.class));
    }

    enum Kind { STORY, REVIEW }
    enum Status { DRAFT, IN_REVIEW, PUBLISHED, REJECTED, REMOVED }

    record Content(Kind kind, CareCategory category, String body, String product, Integer rating) {}
    record Post(UUID id, Content content, Status status, OffsetDateTime acceptedAt) {}
    record Page(List<Post> posts, OffsetDateTime nextAvailableAt) {}
    record Submission(Post post, OffsetDateTime nextAvailableAt) {}
}
