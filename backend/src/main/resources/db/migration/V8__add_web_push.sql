ALTER TABLE accounts ADD COLUMN push_zone_since timestamptz NOT NULL DEFAULT statement_timestamp();
ALTER TABLE care_occurrences ADD COLUMN push_planned_since timestamptz NOT NULL DEFAULT statement_timestamp();
ALTER TABLE agenda_reminder_preferences ADD COLUMN push_preferences_since timestamptz NOT NULL DEFAULT statement_timestamp();

CREATE FUNCTION track_push_eligibility() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    CASE TG_TABLE_NAME
        WHEN 'accounts' THEN
            IF OLD.time_zone IS DISTINCT FROM NEW.time_zone THEN
                NEW.push_zone_since := clock_timestamp();
            END IF;
        WHEN 'care_occurrences' THEN
            IF (OLD.status, OLD.planned_date) IS DISTINCT FROM (NEW.status, NEW.planned_date) THEN
                NEW.push_planned_since := clock_timestamp();
            END IF;
        WHEN 'agenda_reminder_preferences' THEN
            IF (OLD.enabled, OLD.categories, OLD.reminder_time)
                IS DISTINCT FROM (NEW.enabled, NEW.categories, NEW.reminder_time) THEN
                NEW.push_preferences_since := clock_timestamp();
            END IF;
    END CASE;
    RETURN NEW;
END
$$;

CREATE TRIGGER accounts_push_eligibility BEFORE UPDATE OF time_zone ON accounts
    FOR EACH ROW EXECUTE FUNCTION track_push_eligibility();
CREATE TRIGGER occurrences_push_eligibility BEFORE UPDATE OF status, planned_date ON care_occurrences
    FOR EACH ROW EXECUTE FUNCTION track_push_eligibility();
CREATE TRIGGER preferences_push_eligibility BEFORE UPDATE OF enabled, categories, reminder_time ON agenda_reminder_preferences
    FOR EACH ROW EXECUTE FUNCTION track_push_eligibility();

CREATE TABLE web_push_subscriptions (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    endpoint varchar(2048) NOT NULL UNIQUE,
    session_hash char(64) NOT NULL,
    active boolean NOT NULL,
    enabled_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX web_push_active_subscriptions ON web_push_subscriptions (account_id, session_hash) WHERE active;
CREATE INDEX web_push_planned_occurrences ON care_occurrences (planned_date) WHERE status = 'PLANNED';

-- An eligible occurrence is one logical reminder, independent of the number of subscriptions.
CREATE VIEW web_push_reminders AS
SELECT o.id AS occurrence_id, o.account_id, o.planned_date, a.time_zone, p.reminder_time,
       GREATEST(o.push_planned_since, a.push_zone_since, p.push_preferences_since) AS eligible_since
FROM care_occurrences o
JOIN accounts a ON a.id = o.account_id
JOIN agenda_reminder_preferences p ON p.account_id = o.account_id
WHERE o.status = 'PLANNED' AND p.enabled AND o.category = ANY (p.categories)
  AND NOT EXISTS (
      SELECT 1 FROM recurring_plans plan WHERE plan.id = o.plan_id AND plan.closed_on <= o.planned_date
  );

CREATE TABLE web_push_attempts (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscription_id bigint NOT NULL REFERENCES web_push_subscriptions (id) ON DELETE CASCADE,
    occurrence_id bigint NOT NULL REFERENCES care_occurrences (id) ON DELETE CASCADE,
    scheduled_at timestamptz NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    state varchar(12) NOT NULL CHECK (state IN ('SENDING', 'ACCEPTED', 'RETRY', 'UNCERTAIN', 'REJECTED', 'CANCELED')),
    retry_at timestamptz,
    provider_status integer,
    CHECK ((state = 'RETRY') = (retry_at IS NOT NULL)),
    CHECK ((state = 'SENDING') = (finished_at IS NULL))
);

CREATE INDEX web_push_attempts_by_delivery ON web_push_attempts (subscription_id, occurrence_id);
CREATE UNIQUE INDEX web_push_exclusive_delivery ON web_push_attempts (subscription_id, occurrence_id)
    WHERE state IN ('SENDING', 'ACCEPTED', 'UNCERTAIN');
