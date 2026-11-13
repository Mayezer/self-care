ALTER TABLE billing_accounts ADD COLUMN access_lost_at timestamptz;

CREATE TABLE hair_diary_photos (
    account_id bigint NOT NULL REFERENCES billing_accounts (account_id) ON DELETE CASCADE,
    id uuid NOT NULL,
    captured_on date NOT NULL,
    created_at timestamptz NOT NULL,
    content bytea NOT NULL,
    PRIMARY KEY (account_id, id)
);

CREATE INDEX hair_diary_photos_by_date ON hair_diary_photos (account_id, captured_on, created_at, id);
