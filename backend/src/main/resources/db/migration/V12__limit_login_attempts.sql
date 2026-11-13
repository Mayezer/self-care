CREATE TABLE login_rate_limits (
    identifier_hash bytea PRIMARY KEY CHECK (octet_length(identifier_hash) = 32),
    expires_at timestamp with time zone NOT NULL,
    attempts integer NOT NULL CHECK (attempts > 0)
);

CREATE INDEX login_rate_limits_expiration ON login_rate_limits (expires_at);
