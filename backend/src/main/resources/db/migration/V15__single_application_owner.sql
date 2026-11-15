CREATE TABLE app_owner (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    account_id bigint NOT NULL UNIQUE REFERENCES accounts (id)
);
