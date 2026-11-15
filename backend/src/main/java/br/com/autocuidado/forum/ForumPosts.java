package br.com.autocuidado.forum;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import br.com.autocuidado.billing.SubscriptionAccess;
import br.com.autocuidado.persistence.CareCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ForumPosts {
    private static final ZoneId FORUM_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final String SUBSCRIPTION_REQUIRED = "Uma assinatura ativa é necessária para participar do fórum.";
    private static final String SAVE = """
            INSERT INTO forum_posts (account_id, id, kind, category, title, body, product, rating,
                                     status, accepted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id, id) DO UPDATE SET kind = EXCLUDED.kind, category = EXCLUDED.category,
                title = EXCLUDED.title, body = EXCLUDED.body, product = EXCLUDED.product, rating = EXCLUDED.rating,
                status = EXCLUDED.status, accepted_at = EXCLUDED.accepted_at
            WHERE forum_posts.status = 'DRAFT'
            RETURNING *
            """;

    private final JdbcTemplate jdbc;
    private final SubscriptionAccess access;
    private final Clock clock;

    ForumPosts(JdbcTemplate jdbc, SubscriptionAccess access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    Page list(long accountId) {
        var posts = jdbc.query("""
                SELECT * FROM forum_posts WHERE account_id = ?
                ORDER BY accepted_at DESC NULLS FIRST, id
                """, ForumPosts::mapPost, accountId);
        var today = LocalDate.now(clock.withZone(FORUM_ZONE));
        boolean used = posts.stream().anyMatch(post -> post.acceptedAt() != null
                && today.equals(post.acceptedAt().atZoneSameInstant(FORUM_ZONE).toLocalDate()));
        var comments = jdbc.query("""
                SELECT c.id, c.body, c.status, t.kind,
                    CASE WHEN t.status = 'PUBLISHED' THEN t.public_id END AS public_topic_id
                FROM forum_comments c JOIN forum_posts t ON t.public_id = c.topic_id
                WHERE c.account_id = ? ORDER BY c.created_at DESC, c.id
                """, (row, index) -> new OwnComment(row.getObject("id", UUID.class), row.getString("body"),
                Status.valueOf(row.getString("status")), Kind.valueOf(row.getString("kind")),
                row.getObject("public_topic_id", UUID.class)), accountId);
        return new Page(posts, used ? nextWindow(today) : null, comments);
    }

    @Transactional
    Post draft(long accountId, UUID id, Content content) {
        access.requireActive(accountId, SUBSCRIPTION_REQUIRED);
        return save(accountId, id, content, Status.DRAFT, null);
    }

    @Transactional
    Submission submit(long accountId, UUID id, Content content) {
        var now = beginParticipation(accountId).atZone(FORUM_ZONE).toOffsetDateTime();
        var existing = jdbc.query("SELECT * FROM forum_posts WHERE account_id = ? AND id = ?",
                ForumPosts::mapPost, accountId, id).stream().findFirst().orElse(null);
        if (existing != null && existing.acceptedAt() != null) {
            return new Submission(new Post(id, existing.content(), Status.IN_REVIEW, existing.acceptedAt()), null);
        }
        var day = now.toLocalDate();
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM forum_posts WHERE account_id = ? AND accepted_day = ?)
                """, Boolean.class, accountId, day))) {
            return new Submission(null, nextWindow(day));
        }
        return new Submission(save(accountId, id, content, Status.IN_REVIEW, now), null);
    }

    boolean withdraw(long accountId, UUID id) {
        return jdbc.update("""
                UPDATE forum_posts SET status = 'REMOVED'
                WHERE account_id = ? AND id = ? AND status IN ('IN_REVIEW', 'PUBLISHED', 'REMOVED')
                """, accountId, id) == 1;
    }

    boolean withdrawComment(long accountId, UUID id) {
        return jdbc.update("""
                UPDATE forum_comments SET status = 'REMOVED'
                WHERE account_id = ? AND id = ? AND status IN ('IN_REVIEW', 'PUBLISHED', 'REMOVED')
                """, accountId, id) == 1;
    }

    private Post save(long accountId, UUID id, Content content, Status status,
                      OffsetDateTime acceptedAt) {
        return jdbc.query(SAVE, ForumPosts::mapPost, accountId, id, content.kind().name(),
                content.category() == null ? null : content.category().name(), content.title(), content.body(), content.product(),
                content.rating(), status.name(), acceptedAt).stream().findFirst().orElse(null);
    }

    @Transactional
    public CommentSubmission comment(long accountId, UUID topicId, UUID requestId, String body) {
        var now = beginParticipation(accountId).atZone(FORUM_ZONE).toOffsetDateTime();
        var existing = jdbc.query("""
                SELECT id, topic_id FROM forum_comments WHERE account_id = ? AND request_id = ?
                """, (row, index) -> new CommentReceipt(row.getObject("id", UUID.class),
                row.getObject("topic_id", UUID.class)), accountId, requestId).stream().findFirst().orElse(null);
        if (existing != null) {
            return new CommentSubmission(topicId.equals(existing.topicId()) ? CommentResult.ACCEPTED : CommentResult.CONFLICT,
                    existing.id());
        }

        var topic = jdbc.queryForList("""
                SELECT public_id FROM forum_posts WHERE public_id = ? AND status = 'PUBLISHED' FOR SHARE
                """, UUID.class, topicId);
        if (topic.isEmpty()) return new CommentSubmission(CommentResult.MISSING, null);

        int recent = jdbc.queryForObject("""
                SELECT count(*) FROM forum_comments WHERE account_id = ? AND created_at > ?
                """, Integer.class, accountId, now.minusMinutes(1));
        if (recent >= 10) return new CommentSubmission(CommentResult.LIMITED, null);

        var id = jdbc.queryForObject("""
                INSERT INTO forum_comments (topic_id, account_id, request_id, body, status, created_at)
                VALUES (?, ?, ?, ?, 'IN_REVIEW', ?) RETURNING id
                """, UUID.class, topicId, accountId, requestId, body, now);
        return new CommentSubmission(CommentResult.ACCEPTED, id);
    }

    private Instant beginParticipation(long accountId) {
        // Serialize quota and retry decisions across devices before taking the subscription snapshot.
        jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", Long.class, accountId);
        return access.requireActive(accountId, SUBSCRIPTION_REQUIRED);
    }

    private static OffsetDateTime nextWindow(LocalDate day) {
        return day.plusDays(1).atStartOfDay(FORUM_ZONE).toOffsetDateTime();
    }

    private static Post mapPost(ResultSet row, int index) throws SQLException {
        String category = row.getString("category");
        return new Post(row.getObject("id", UUID.class), new Content(Kind.valueOf(row.getString("kind")),
                category == null ? null : CareCategory.valueOf(category), row.getString("title"), row.getString("body"),
                row.getString("product"), row.getObject("rating", Integer.class)),
                Status.valueOf(row.getString("status")), row.getObject("accepted_at", OffsetDateTime.class));
    }

    enum Kind { STORY, REVIEW, QUESTION }
    enum Status { DRAFT, IN_REVIEW, PUBLISHED, REJECTED, REMOVED }

    record Content(Kind kind, CareCategory category, String title, String body, String product, Integer rating) {}
    record Post(UUID id, Content content, Status status, OffsetDateTime acceptedAt) {}
    record OwnComment(UUID id, String body, Status status, Kind topicKind, UUID publicTopicId) {}
    record Page(List<Post> posts, OffsetDateTime nextAvailableAt, List<OwnComment> comments) {}
    record Submission(Post post, OffsetDateTime nextAvailableAt) {}
    record CommentSubmission(CommentResult result, UUID id) {}
    private record CommentReceipt(UUID id, UUID topicId) {}
    enum CommentResult { ACCEPTED, CONFLICT, MISSING, LIMITED }
}
