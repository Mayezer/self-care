ALTER TABLE accounts
    ADD COLUMN username varchar(32) UNIQUE,
    ADD COLUMN password_hash varchar(255),
    ADD CONSTRAINT accounts_credentials_pair CHECK ((username IS NULL) = (password_hash IS NULL));
