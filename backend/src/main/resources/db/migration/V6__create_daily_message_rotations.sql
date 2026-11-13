CREATE TABLE daily_message_rotations (
    account_id bigint PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    started_on date NOT NULL,
    shuffle_seed bigint NOT NULL,
    catalog_version smallint NOT NULL CHECK (catalog_version > 0)
);
