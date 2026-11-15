CREATE TABLE makeup_items (
    account_id bigint NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    id uuid NOT NULL,
    name varchar(100) NOT NULL CHECK (btrim(name) <> ''),
    category varchar(60) CHECK (btrim(category) <> ''),
    status varchar(12) NOT NULL CHECK (status IN ('OWNED', 'RUNNING_LOW', 'TO_BUY')),
    PRIMARY KEY (account_id, id)
);
