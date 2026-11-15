package br.com.autocuidado.forum;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ForumModeration {
    private static final String TARGETS = """
            SELECT t.*, p.product, p.rating, p.category
            FROM forum_moderation_targets t JOIN forum_posts p ON p.public_id = t.topic_id
            """;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    ForumModeration(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Queue queue() {
        var pending = jdbc.query(TARGETS + """
                WHERE t.status = 'IN_REVIEW' AND (t.kind = 'TOPIC' OR t.topic_status = 'PUBLISHED')
                ORDER BY t.submitted_at, t.id LIMIT 50
                """, ForumModeration::target);
        var reports = jdbc.query("""
                SELECT t.*, r.id AS report_id, r.reason
                FROM forum_reports r JOIN (
                """ + TARGETS + """
                ) t ON t.topic_id = r.topic_id
                    AND ((t.kind = 'TOPIC' AND r.comment_id IS NULL AND r.image_id IS NULL)
                       OR (t.kind = 'COMMENT' AND t.id = r.comment_id)
                       OR (t.kind = 'IMAGE' AND t.id = r.image_id))
                WHERE r.resolved_at IS NULL
                ORDER BY r.created_at, r.id LIMIT 50
                """, (row, index) -> new Report(row.getObject("report_id", UUID.class), row.getString("reason"),
                target(row, index)));
        return new Queue(pending, reports);
    }

    @Transactional
    boolean report(long accountId, UUID topicId, Kind kind, UUID targetId, String reason) {
        if (!lockPublishedTopic(topicId)) return false;
        if (kind == Kind.TOPIC ? !topicId.equals(targetId) : !lockPublishedChild(topicId, kind, targetId)) return false;
        jdbc.update("""
                INSERT INTO forum_reports (reporter_id, topic_id, comment_id, image_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (reporter_id, topic_id, comment_id, image_id) DO NOTHING
                """, accountId, topicId, kind == Kind.COMMENT ? targetId : null, kind == Kind.IMAGE ? targetId : null,
                reason, OffsetDateTime.now(clock));
        return true;
    }

    @Transactional
    boolean decide(long accountId, UUID topicId, Kind kind, UUID targetId, Decision decision) {
        int changed;
        if (kind == Kind.TOPIC) {
            if (!topicId.equals(targetId)) return false;
            changed = jdbc.update("UPDATE forum_posts SET status = ? WHERE public_id = ? AND status = ?",
                    decision.next.name(), targetId, decision.previous.name());
        } else {
            if (!lockPublishedTopic(topicId)) return false;
            changed = jdbc.update("UPDATE " + kind.table + " SET status = ? WHERE topic_id = ? AND id = ? AND status = ?",
                    decision.next.name(), topicId, targetId, decision.previous.name());
        }
        if (changed == 1 && decision == Decision.REMOVE) {
            String filter = switch (kind) {
                case TOPIC -> "topic_id = ?";
                case COMMENT -> "comment_id = ?";
                case IMAGE -> "image_id = ?";
            };
            jdbc.update("UPDATE forum_reports SET resolved_at = ?, resolved_by = ? WHERE resolved_at IS NULL AND " + filter,
                    OffsetDateTime.now(clock), accountId, targetId);
        }
        return changed == 1;
    }

    void closeReport(long accountId, UUID id) {
        jdbc.update("""
                UPDATE forum_reports SET resolved_at = ?, resolved_by = ? WHERE id = ? AND resolved_at IS NULL
                """, OffsetDateTime.now(clock), accountId, id);
    }

    byte[] image(UUID topicId, UUID imageId) {
        return jdbc.query("""
                SELECT i.jpeg_bytes FROM forum_images i JOIN forum_posts t ON t.public_id = i.topic_id
                WHERE t.public_id = ? AND i.id = ? AND t.status = 'PUBLISHED'
                    AND i.status IN ('IN_REVIEW', 'PUBLISHED')
                """, (row, index) -> row.getBytes("jpeg_bytes"), topicId, imageId).stream().findFirst().orElse(null);
    }

    private boolean lockPublishedTopic(UUID topicId) {
        return !jdbc.queryForList("SELECT public_id FROM forum_posts WHERE public_id = ? AND status = 'PUBLISHED' FOR SHARE",
                UUID.class, topicId).isEmpty();
    }

    private boolean lockPublishedChild(UUID topicId, Kind kind, UUID targetId) {
        return !jdbc.queryForList("SELECT id FROM " + kind.table + """
                 WHERE topic_id = ? AND id = ? AND status = 'PUBLISHED' FOR SHARE
                """, UUID.class, topicId, targetId).isEmpty();
    }

    private static Target target(ResultSet row, int index) throws SQLException {
        return new Target(Kind.valueOf(row.getString("kind")), row.getObject("id", UUID.class),
                row.getObject("topic_id", UUID.class), row.getString("title"), row.getString("product"),
                row.getObject("rating", Integer.class), row.getString("category"), row.getString("preview"),
                ForumPosts.Status.valueOf(row.getString("status")),
                "PUBLISHED".equals(row.getString("topic_status")));
    }

    enum Kind {
        TOPIC(null), COMMENT("forum_comments"), IMAGE("forum_images");

        private final String table;

        Kind(String table) { this.table = table; }
    }

    enum Decision {
        APPROVE(ForumPosts.Status.IN_REVIEW, ForumPosts.Status.PUBLISHED),
        REJECT(ForumPosts.Status.IN_REVIEW, ForumPosts.Status.REJECTED),
        REMOVE(ForumPosts.Status.PUBLISHED, ForumPosts.Status.REMOVED);

        private final ForumPosts.Status previous;
        private final ForumPosts.Status next;

        Decision(ForumPosts.Status previous, ForumPosts.Status next) {
            this.previous = previous;
            this.next = next;
        }
    }

    record Target(Kind kind, UUID id, UUID topicId, String title, String product, Integer rating, String category,
                  String preview, ForumPosts.Status status, boolean publicTopic) {}
    record Report(UUID id, String reason, Target target) {}
    record Queue(List<Target> pending, List<Report> reports) {}
}
