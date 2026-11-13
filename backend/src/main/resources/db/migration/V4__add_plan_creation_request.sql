ALTER TABLE recurring_plans
    ADD COLUMN creation_request_id uuid,
    ADD COLUMN creation_request_hash varchar(64),
    ADD CONSTRAINT recurring_plans_creation_request_key UNIQUE (account_id, creation_request_id),
    ADD CONSTRAINT recurring_plans_creation_request_pair CHECK (
        (creation_request_id IS NULL AND creation_request_hash IS NULL)
        OR (creation_request_id IS NOT NULL AND creation_request_hash IS NOT NULL)
    );
