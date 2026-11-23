CREATE INDEX accounts_pending_registration_expiry
    ON accounts (confirmation_expires_at, id)
    WHERE password_hash IS NULL AND confirmation_expires_at IS NOT NULL;
