ALTER TABLE accounts
    DROP CONSTRAINT accounts_credentials_pair,
    ADD COLUMN email varchar(254) UNIQUE,
    ADD COLUMN confirmation_digest char(64),
    ADD COLUMN confirmation_expires_at timestamp with time zone,
    ADD CONSTRAINT accounts_identity_state CHECK (
        (email IS NULL AND (username IS NULL) = (password_hash IS NULL)
            AND confirmation_digest IS NULL AND confirmation_expires_at IS NULL)
        OR (email IS NOT NULL AND username IS NULL AND (
            (password_hash IS NULL AND confirmation_digest IS NOT NULL AND confirmation_expires_at IS NOT NULL)
            OR (password_hash IS NOT NULL AND confirmation_digest IS NULL AND confirmation_expires_at IS NULL)
        ))
    );
