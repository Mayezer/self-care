CREATE TABLE billing_accounts (
    account_id bigint PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    customer_request_id uuid NOT NULL UNIQUE,
    stripe_customer_id text UNIQUE,
    subscription_exists boolean NOT NULL DEFAULT false,
    access_until timestamptz,
    cancel_at_period_end boolean NOT NULL DEFAULT false
);

CREATE TABLE stripe_webhook_events (
    event_id text PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES billing_accounts (account_id) ON DELETE CASCADE
);
