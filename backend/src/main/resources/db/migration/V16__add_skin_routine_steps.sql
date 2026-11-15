ALTER TABLE recurring_plans
    ADD COLUMN skin_steps text[],
    ADD COLUMN routine_request_plan_id bigint,
    ADD CONSTRAINT recurring_plans_skin_steps_check CHECK (
        skin_steps IS NULL OR (
            category = 'SKIN' AND period IS NOT NULL
            AND array_ndims(skin_steps) = 1 AND cardinality(skin_steps) BETWEEN 1 AND 8
            AND array_position(skin_steps, NULL) IS NULL
        )
    ),
    ADD CONSTRAINT recurring_plans_routine_request_check CHECK (
        routine_request_plan_id IS NULL OR (
            skin_steps IS NOT NULL AND routine_request_plan_id <> id AND creation_request_id IS NULL
        )
    ),
    ADD CONSTRAINT recurring_plans_routine_request_owner_fk
        FOREIGN KEY (routine_request_plan_id, account_id, category)
        REFERENCES recurring_plans (id, account_id, category),
    ADD CONSTRAINT recurring_plans_routine_request_companion_key UNIQUE (routine_request_plan_id);
