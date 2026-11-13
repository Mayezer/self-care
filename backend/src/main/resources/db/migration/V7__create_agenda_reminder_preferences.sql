CREATE TABLE agenda_reminder_preferences (
    account_id bigint PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    enabled boolean NOT NULL,
    categories text[] NOT NULL CHECK (categories <@ ARRAY['HAIR', 'SKIN', 'MAKEUP', 'NAILS']::text[]),
    reminder_time time(0),
    CHECK (reminder_time IS NULL OR
        (EXTRACT(SECOND FROM reminder_time) = 0 AND reminder_time < TIME '24:00')),
    CHECK (NOT enabled OR (cardinality(categories) > 0 AND reminder_time IS NOT NULL))
);
