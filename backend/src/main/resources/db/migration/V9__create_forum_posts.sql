CREATE TABLE forum_posts (
    account_id bigint NOT NULL REFERENCES accounts (id),
    id uuid NOT NULL,
    kind varchar(8) NOT NULL CHECK (kind IN ('STORY', 'REVIEW')),
    category varchar(16) CHECK (category IN ('HAIR', 'SKIN', 'MAKEUP', 'NAILS')),
    body varchar(2000) NOT NULL,
    product varchar(120),
    rating smallint CHECK (rating BETWEEN 1 AND 5),
    status varchar(12) NOT NULL CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'REJECTED', 'REMOVED')),
    accepted_at timestamptz,
    accepted_week date,
    PRIMARY KEY (account_id, id),
    CHECK (kind = 'REVIEW' OR (product IS NULL AND rating IS NULL)),
    CHECK (
        (status = 'DRAFT' AND accepted_at IS NULL AND accepted_week IS NULL)
        OR (status <> 'DRAFT' AND accepted_at IS NOT NULL AND accepted_week IS NOT NULL
            AND category IS NOT NULL AND btrim(body) <> ''
            AND (kind <> 'REVIEW' OR (product IS NOT NULL AND btrim(product) <> '')))
    )
);

CREATE UNIQUE INDEX forum_posts_weekly_quota ON forum_posts (account_id, accepted_week)
    WHERE accepted_week IS NOT NULL;
