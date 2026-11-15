ALTER TABLE forum_posts
    ADD COLUMN public_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    ADD COLUMN title varchar(160) CHECK (btrim(title) <> ''),
    DROP CONSTRAINT forum_posts_kind_check,
    ADD CONSTRAINT forum_posts_kind_check CHECK (kind IN ('STORY', 'REVIEW', 'QUESTION')),
    ADD CONSTRAINT forum_questions_require_title CHECK (kind <> 'QUESTION' OR title IS NOT NULL);

CREATE INDEX forum_posts_public_order ON forum_posts (accepted_at DESC, public_id DESC)
    WHERE status = 'PUBLISHED';

CREATE TABLE forum_comments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    topic_id uuid NOT NULL REFERENCES forum_posts (public_id),
    account_id bigint NOT NULL REFERENCES accounts (id),
    body varchar(2000) NOT NULL CHECK (btrim(body) <> ''),
    status varchar(12) NOT NULL CHECK (status IN ('IN_REVIEW', 'PUBLISHED', 'REJECTED', 'REMOVED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX forum_comments_public_order ON forum_comments (topic_id, created_at, id)
    WHERE status = 'PUBLISHED';

-- Contains normalized forum JPEGs only. Private diary media is stored and served separately.
CREATE TABLE forum_images (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    topic_id uuid NOT NULL REFERENCES forum_posts (public_id),
    alt_text varchar(240) NOT NULL CHECK (btrim(alt_text) <> ''),
    jpeg_bytes bytea NOT NULL CHECK (octet_length(jpeg_bytes) BETWEEN 4 AND 2000000
        AND substring(jpeg_bytes FROM 1 FOR 3) = decode('ffd8ff', 'hex')),
    status varchar(12) NOT NULL CHECK (status IN ('IN_REVIEW', 'PUBLISHED', 'REJECTED', 'REMOVED'))
);

CREATE INDEX forum_images_public_topic ON forum_images (topic_id, id)
    WHERE status = 'PUBLISHED';
