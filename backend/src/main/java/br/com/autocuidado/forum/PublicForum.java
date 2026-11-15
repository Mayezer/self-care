package br.com.autocuidado.forum;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicForum {
    private static final int PAGE_SIZE = 20;
    private static final String APPROVED_TOPICS = """
            SELECT public_id, kind, category, title, body, product, rating, accepted_at
            FROM forum_posts WHERE status = 'PUBLISHED'
            """;
    private static final RowMapper<Topic> TOPIC = (row, index) -> new Topic(row.getObject("public_id", UUID.class),
            ForumPosts.Kind.valueOf(row.getString("kind")), row.getString("category"), row.getString("title"),
            row.getString("body"), row.getString("product"), row.getObject("rating", Integer.class),
            row.getObject("accepted_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    PublicForum(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Page list(String query, int page, ForumPosts.Kind kind) {
        String order = " ORDER BY accepted_at DESC, public_id DESC LIMIT ? OFFSET ?";
        long offset = (page - 1L) * PAGE_SIZE;
        var rows = jdbc.query(APPROVED_TOPICS + """
                AND (CAST(? AS varchar) IS NULL OR kind = ?)
                AND (? = '' OR strpos(lower(body), lower(?)) > 0 OR strpos(lower(title), lower(?)) > 0
                    OR strpos(lower(product), lower(?)) > 0)
                """ + order, TOPIC, kind == null ? null : kind.name(), kind == null ? null : kind.name(),
                query, query, query, query, PAGE_SIZE + 1, offset);
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new Page(hasNext ? rows.subList(0, PAGE_SIZE) : rows, hasNext);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Detail detail(UUID id) {
        var topic = jdbc.query(APPROVED_TOPICS + " AND public_id = ?", TOPIC, id).stream().findFirst().orElse(null);
        if (topic == null) return null;
        var comments = jdbc.query("""
                        SELECT id, body, created_at FROM forum_comments
                        WHERE topic_id = ? AND status = 'PUBLISHED' ORDER BY created_at, id
                        """, (row, index) -> new Comment(row.getObject("id", UUID.class), row.getString("body"),
                row.getObject("created_at", OffsetDateTime.class)), id);
        var images = jdbc.query("""
                        SELECT id, alt_text FROM forum_images
                        WHERE topic_id = ? AND status = 'PUBLISHED' ORDER BY id
                        """, (row, index) -> new Image(row.getObject("id", UUID.class), row.getString("alt_text")), id);
        return new Detail(topic, comments, images);
    }

    byte[] image(UUID topicId, UUID imageId) {
        return jdbc.query("""
                SELECT i.jpeg_bytes FROM forum_images i JOIN forum_posts t ON t.public_id = i.topic_id
                WHERE t.public_id = ? AND i.id = ? AND t.status = 'PUBLISHED' AND i.status = 'PUBLISHED'
                """, (row, index) -> row.getBytes("jpeg_bytes"), topicId, imageId).stream().findFirst().orElse(null);
    }

    record Topic(UUID id, ForumPosts.Kind kind, String category, String title, String body, String product,
                 Integer rating, OffsetDateTime acceptedAt) {}
    record Page(List<Topic> topics, boolean hasNext) {}
    record Detail(Topic topic, List<Comment> comments, List<Image> images) {}
    record Comment(UUID id, String body, OffsetDateTime createdAt) {}
    record Image(UUID id, String altText) {}
}
