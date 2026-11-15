ALTER TABLE forum_posts
    DROP CONSTRAINT forum_questions_require_title,
    ADD CONSTRAINT forum_questions_require_title
        CHECK (kind <> 'QUESTION' OR status = 'DRAFT' OR title IS NOT NULL);

CREATE TABLE forum_reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id bigint NOT NULL REFERENCES accounts (id),
    topic_id uuid NOT NULL REFERENCES forum_posts (public_id),
    comment_id uuid REFERENCES forum_comments (id),
    image_id uuid REFERENCES forum_images (id),
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    created_at timestamptz NOT NULL,
    resolved_at timestamptz,
    resolved_by bigint REFERENCES accounts (id),
    CHECK (num_nonnulls(comment_id, image_id) <= 1),
    CHECK ((resolved_at IS NULL) = (resolved_by IS NULL)),
    UNIQUE NULLS NOT DISTINCT (reporter_id, topic_id, comment_id, image_id)
);

CREATE INDEX forum_reports_open ON forum_reports (created_at, id) WHERE resolved_at IS NULL;

CREATE VIEW forum_moderation_targets AS
SELECT 'TOPIC' AS kind, public_id AS id, public_id AS topic_id, title, body AS preview,
    status, status AS topic_status, accepted_at AS submitted_at
FROM forum_posts WHERE status <> 'DRAFT'
UNION ALL
SELECT 'COMMENT', c.id, c.topic_id, t.title, c.body, c.status, t.status, c.created_at
FROM forum_comments c JOIN forum_posts t ON t.public_id = c.topic_id
UNION ALL
SELECT 'IMAGE', i.id, i.topic_id, t.title, i.alt_text, i.status, t.status, t.accepted_at
FROM forum_images i JOIN forum_posts t ON t.public_id = i.topic_id;
