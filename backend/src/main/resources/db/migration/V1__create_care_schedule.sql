CREATE TABLE accounts (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    time_zone varchar(255) NOT NULL
);

CREATE TABLE recurring_plans (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES accounts (id),
    category varchar(16) NOT NULL CHECK (category IN ('HAIR', 'SKIN')),
    start_date date NOT NULL,
    weeks integer NOT NULL CHECK (weeks BETWEEN 1 AND 12),
    end_date date NOT NULL CHECK (end_date = start_date + (weeks * 7 - 1)),
    closed_on date,
    title text,
    period varchar(8),
    CONSTRAINT recurring_plans_skin_title CHECK (
        category <> 'SKIN' OR (title IS NOT NULL AND btrim(title) <> '')
    ),
    CONSTRAINT recurring_plans_period CHECK (
        period IS NULL OR (category = 'SKIN' AND period IN ('MORNING', 'NIGHT'))
    ),
    CONSTRAINT recurring_plans_owner_category_key UNIQUE (id, account_id, category)
);

CREATE TABLE recurring_plan_weekdays (
    plan_id bigint NOT NULL REFERENCES recurring_plans (id) ON DELETE CASCADE,
    weekday varchar(9) NOT NULL CHECK (weekday IN (
        'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'
    )),
    PRIMARY KEY (plan_id, weekday)
);

CREATE TABLE care_occurrences (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES accounts (id),
    plan_id bigint,
    category varchar(16) NOT NULL CHECK (category IN ('HAIR', 'SKIN', 'MAKEUP', 'NAILS')),
    title text NOT NULL CHECK (btrim(title) <> ''),
    planned_date date,
    status varchar(12) NOT NULL,
    completed_on date,
    skipped_on date,
    hair_stage varchar(16),
    period varchar(8),
    note text,
    polish_color text,
    polish_product text,
    CONSTRAINT care_occurrences_plan_owner_category_fk
        FOREIGN KEY (plan_id, account_id, category) REFERENCES recurring_plans (id, account_id, category),
    CONSTRAINT care_occurrences_plan_date_key UNIQUE (plan_id, planned_date),
    CONSTRAINT care_occurrences_state_dates CHECK (
        (status = 'PLANNED' AND planned_date IS NOT NULL AND completed_on IS NULL AND skipped_on IS NULL)
        OR (status = 'COMPLETED' AND completed_on IS NOT NULL AND skipped_on IS NULL)
        OR (status = 'SKIPPED' AND planned_date IS NOT NULL AND completed_on IS NULL AND skipped_on IS NOT NULL)
    ),
    CONSTRAINT care_occurrences_planned_or_direct CHECK (
        planned_date IS NOT NULL OR (plan_id IS NULL AND category IN ('MAKEUP', 'NAILS'))
    ),
    CONSTRAINT care_occurrences_hair_stage CHECK (
        (category = 'HAIR' AND plan_id IS NOT NULL AND hair_stage IS NOT NULL
            AND hair_stage IN ('WASH_ONLY', 'HYDRATION', 'NUTRITION', 'RECONSTRUCTION'))
        OR (category <> 'HAIR' AND hair_stage IS NULL)
    ),
    CONSTRAINT care_occurrences_period CHECK (
        period IS NULL OR (category = 'SKIN' AND plan_id IS NULL AND period IN ('MORNING', 'NIGHT'))
    )
);
