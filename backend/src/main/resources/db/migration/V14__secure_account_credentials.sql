ALTER TABLE accounts
    ADD COLUMN credential_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN totp_seed bytea,
    ADD COLUMN totp_last_step bigint,
    ADD COLUMN recovery_digest char(64),
    ADD COLUMN reset_digest char(64),
    ADD COLUMN reset_expires_at timestamptz,
    ADD COLUMN reset_requested_at timestamptz,
    ADD CONSTRAINT accounts_totp_state CHECK (
        (totp_seed IS NULL AND totp_last_step IS NULL AND recovery_digest IS NULL)
        OR (totp_seed IS NOT NULL AND totp_last_step IS NOT NULL AND recovery_digest IS NOT NULL
            AND password_hash IS NOT NULL)
    ),
    ADD CONSTRAINT accounts_reset_state CHECK ((reset_digest IS NULL) = (reset_expires_at IS NULL));

-- Preserve the login name of a legacy account when its owner verifies an email address.
ALTER TABLE accounts DROP CONSTRAINT accounts_identity_state;
ALTER TABLE accounts ADD CONSTRAINT accounts_identity_state CHECK (
    (email IS NULL AND (username IS NULL) = (password_hash IS NULL)
        AND confirmation_digest IS NULL AND confirmation_expires_at IS NULL)
    OR (email IS NOT NULL AND (
        (username IS NULL AND password_hash IS NULL
            AND confirmation_digest IS NOT NULL AND confirmation_expires_at IS NOT NULL)
        OR (password_hash IS NOT NULL AND confirmation_digest IS NULL AND confirmation_expires_at IS NULL)
    ))
);
