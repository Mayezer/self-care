CREATE INDEX care_occurrences_by_account_planned_date
    ON care_occurrences (account_id, planned_date);

CREATE INDEX care_occurrences_completed_by_account_date
    ON care_occurrences (account_id, completed_on)
    WHERE status = 'COMPLETED';
