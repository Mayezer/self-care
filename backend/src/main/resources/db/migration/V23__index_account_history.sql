CREATE INDEX care_occurrences_history_page
    ON care_occurrences (account_id, (COALESCE(completed_on, skipped_on)) DESC, id DESC)
    WHERE status IN ('COMPLETED', 'SKIPPED');
