CREATE TABLE makeup_cleaning_series (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES accounts (id),
    accessory varchar(8) NOT NULL CHECK (accessory IN ('BRUSHES', 'SPONGES')),
    interval_days integer NOT NULL CHECK (interval_days BETWEEN 1 AND 365),
    start_date date NOT NULL,
    active boolean NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    generated_through date,
    UNIQUE (account_id, accessory),
    UNIQUE (id, account_id)
);

ALTER TABLE care_occurrences
    ADD COLUMN cleaning_series_id bigint,
    ADD COLUMN cleaning_canceled boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT clean_occurrence_owner_fk FOREIGN KEY (cleaning_series_id, account_id)
        REFERENCES makeup_cleaning_series (id, account_id),
    ADD CONSTRAINT clean_occurrence_date_key UNIQUE (cleaning_series_id, planned_date),
    ADD CONSTRAINT clean_occurrence_shape CHECK (
        (cleaning_series_id IS NULL AND NOT cleaning_canceled)
        OR (cleaning_series_id IS NOT NULL AND category = 'MAKEUP' AND planned_date IS NOT NULL AND plan_id IS NULL)
    );

CREATE INDEX makeup_cleaning_refresh ON makeup_cleaning_series (generated_through) WHERE active;

CREATE OR REPLACE VIEW web_push_reminders AS
SELECT o.id AS occurrence_id, o.account_id, o.planned_date, a.time_zone, p.reminder_time,
       GREATEST(o.push_planned_since, a.push_zone_since, p.push_preferences_since) AS eligible_since
FROM care_occurrences o
JOIN accounts a ON a.id = o.account_id
JOIN agenda_reminder_preferences p ON p.account_id = o.account_id
WHERE o.status = 'PLANNED' AND NOT o.cleaning_canceled AND p.enabled AND o.category = ANY (p.categories)
  AND NOT EXISTS (
      SELECT 1 FROM recurring_plans plan WHERE plan.id = o.plan_id AND plan.closed_on <= o.planned_date
  );
