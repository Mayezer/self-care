# Lessons learned

These cases summarize verified implementation and integration issues, without credentials, account information, internal infrastructure identifiers, or raw operational logs. Measurements describe historical synthetic tests, not current production guarantees.

## Completion before the planned date

**Problem:** future care could be completed by submitting today's completion date. The backend validated the completion date without checking whether the planned date had arrived.

**Fix:** validate both dates on the server and disable early completion in the UI. Rescheduling to today remains the explicit way to bring eligible care forward.

**Verification:** API, browser, and controlled-clock PostgreSQL checks covered future appointments, overdue care, direct history entries, rescheduling, and day boundaries. See [AgendaService](../backend/src/main/java/br/com/autocuidado/persistence/AgendaService.java) and [AgendaPostgresqlTest](../backend/src/test/java/br/com/autocuidado/persistence/AgendaPostgresqlTest.java).

## Slow agenda query

**Problem:** an account/date query scanned a synthetic table with 720,000 occurrences. Measured p95 was 417.08 ms against a 300 ms query budget.

**Fix:** add indexes beginning with account ID, followed by planned/completed date, in [Flyway V13](../backend/src/main/resources/db/migration/V13__index_agenda_occurrences_by_account.sql).

**Result:** the same local scenario measured p95 of 42.83 ms, about a 90% reduction. This query result applies to that dataset and environment, not end-to-end production latency or capacity.

## JDBC concurrency failures through transaction pooling

**Problem:** concurrent profile writes returned HTTP 500 through the tested Neon transaction-pool connection. Diagnosis found transaction-isolation behavior incompatible with this application's JDBC/session assumptions.

**Fix:** use a direct TLS-verified JDBC connection and retain the application's Hikari pool, limited to five connections. The deployment controller rejects the pooled hostname for this runtime.

**Verification:** repeat concurrent writes locally and in provider QA, check isolation behavior, and confirm that the reproduced 500 errors disappear. This decision applies to the tested application path, not every use of transaction pooling. See [deployment configuration](../deploy/README.md).

## Accessible names diverged from visible labels

**Problem:** calendar/preview buttons showed text that differed from their accessible names, making label-based voice interaction harder.

**Fix:** retain visible labels in accessible names, move extra date details into descriptions, and adjust contrast where needed.

**Verification:** repeat accessibility checks in Chromium/Firefox at mobile/desktop sizes, keyboard checks, and targeted Orca announcements. These validate exercised flows rather than certify the application. See the [calendar template](../frontend/src/agenda-week.html).

## Sign-in restoration overwrote suggested stages

**Problem:** a restored preview reapplied all old stages, including generated washing-only defaults, over a new authenticated suggestion.

**Fix:** distinguish user edits from generated defaults and restore only edited dates, retaining compatibility with older drafts. Date changes preserve edits for sessions that remain present.

**Verification:** the [preview-flow regression](../frontend/src/hair-preview-flow.spec.ts) covers preserving intentional edits and accepting fresh suggestions elsewhere. Server tests also cover stale previews, account isolation, and idempotent saving.

## Deployment sources missing from the public checkout

**Problem:** deployment files were ignored, leaving a fresh checkout without files needed by the documented procedure.

**Fix:** explicitly allow reviewed deployment sources and empty examples through `.gitignore`, keep effective settings/state private, and add permanent deployment/rollback tests to CI.

**Verification:** inspect a clean public file set, resolve its documentation links, and run deployment simulations from that set. The same allowlist approach now separates public documentation from internal evidence. See [Security](SECURITY.md) and [CI](../.github/workflows/ci.yml).
