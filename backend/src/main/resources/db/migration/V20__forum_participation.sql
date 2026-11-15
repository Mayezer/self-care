ALTER TABLE forum_posts
    ADD COLUMN accepted_day date GENERATED ALWAYS AS ((accepted_at AT TIME ZONE 'America/Sao_Paulo')::date) STORED,
    ADD CONSTRAINT forum_posts_accepted_content CHECK (
        (status = 'DRAFT' AND accepted_at IS NULL)
        OR (status <> 'DRAFT' AND accepted_at IS NOT NULL AND category IS NOT NULL AND btrim(body) <> ''
            AND (kind <> 'REVIEW' OR (product IS NOT NULL AND btrim(product) <> '')))
    );

-- Dropping the column also removes the weekly index and its acceptance constraint.
ALTER TABLE forum_posts DROP COLUMN accepted_week;
CREATE UNIQUE INDEX forum_posts_daily_quota ON forum_posts (account_id, accepted_day)
    WHERE accepted_day IS NOT NULL;

ALTER TABLE forum_comments ADD COLUMN request_id uuid;
CREATE UNIQUE INDEX forum_comments_request ON forum_comments (account_id, request_id)
    WHERE request_id IS NOT NULL;
CREATE INDEX forum_comments_account_time ON forum_comments (account_id, created_at DESC);
