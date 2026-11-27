# Project specification and architecture

This document describes the implementation and release scope recorded on November 26, 2026. The [README](../README.md) is the entry point; the [development guide](DEVELOPMENT.md) explains how to run it.

## Purpose

Self-Care Planner helps people schedule and track hair, skin, makeup, and nail care in one agenda. Users can preview a hair schedule before creating an account, then save plans, adjust individual sessions, and record completed care.

The application organizes cosmetic care. Suggestions use deterministic server-side rules and declared information. They do not infer a diagnosis, validate a universal hydration/nutrition/reconstruction rotation, or guarantee an individual result.

## Functional requirements

| ID | Requirement |
|---|---|
| FR-01 | Generate a public hair-schedule preview from a start date, a duration in weeks, and selected weekdays. |
| FR-02 | Register through email confirmation; support sign-in, sign-out, password changes/recovery, and session revocation. |
| FR-03 | Offer optional TOTP authentication for ordinary accounts and enforce it for the administrative owner. |
| FR-04 | Save recurring hair/skin plans and individual skin, makeup, and nail-care appointments. Preserve the preview through sign-in. |
| FR-05 | Display the current week, a selected day's care, upcoming care, overdue items, category filters, and weekly progress. |
| FR-06 | Complete, skip, reschedule, and undo eligible occurrences; close recurring plans without deleting history. |
| FR-07 | Record completed makeup/nail care directly in history and browse completed/skipped care. |
| FR-08 | Store an optional private hair profile and generate editable schedule suggestions with reasons and missing-data explanations. |
| FR-09 | Store a private account name/photo; normalize uploaded photos and remove their metadata. |
| FR-10 | Provide educational guides, introductory skin-routine suggestions, in-app reminders, daily messages, and a care streak. |
| FR-11 | Manage a private makeup inventory and recurring brush/sponge cleaning reminders. |

## Non-functional requirements

| ID | Requirement and implementation |
|---|---|
| NFR-01 | Security: authenticate private routes, validate resource ownership on the server, protect state-changing requests with CSRF, hash passwords, and apply session lifetimes and request limits. |
| NFR-02 | Integrity: use transactions, unique/check/foreign-key constraints, and account-level locking where needed to preserve state under retries and concurrency. |
| NFR-03 | Privacy: separate accounts' data, avoid private content in public logs/evidence, and keep credentials outside version control. |
| NFR-04 | Usability/accessibility: support mobile layouts, keyboard navigation, visible focus, field-level errors, assistive announcements, and reduced motion. WCAG 2.2 AA guides verification; this is not a blanket certification. |
| NFR-05 | Performance: index account/date queries, bound/paginate reads, and validate capacity against an identified environment and workload. |
| NFR-06 | Maintainability: use versioned Flyway migrations, automated tests, locked dependencies, and CI. Hibernate validates mappings rather than silently changing the schema. |
| NFR-07 | Recoverability: keep approved image references, apply migrations separately in deployment, and exercise rollback and database recovery in isolated environments. |
| NFR-08 | Public delivery: prerender public pages, provide canonical metadata/sitemap, exclude private pages from indexing, and return HTTP 404 for unknown routes. Authentication remains the access boundary. |

These are engineering requirements and implemented controls, not an availability SLA, a security certification, or evidence of real-user retention. Historical measurements are described in [Lessons learned](LESSONS_LEARNED.md).

## Architecture

The system has three logical layers: Angular presentation, a Spring Boot REST application, and PostgreSQL persistence. The backend is a monolith organized into domain packages, with controllers, services, persistence components, and shared transactions.

| Area | Source | Responsibility |
|---|---|---|
| Presentation | [`frontend/src`](../frontend/src) | Routes, forms, preview drafts, agenda, profiles, themes, and accessible UI states |
| Authentication | [`auth`](../backend/src/main/java/br/com/autocuidado/auth) | Accounts, credentials, MFA, sessions, authenticated identity, and request protection |
| Scheduling | [`recurrence`](../backend/src/main/java/br/com/autocuidado/recurrence), [`agenda`](../backend/src/main/java/br/com/autocuidado/agenda), [`persistence`](../backend/src/main/java/br/com/autocuidado/persistence) | Date generation, actions, transactions, queries, and persistence |
| Hair care | [`hair`](../backend/src/main/java/br/com/autocuidado/hair) | Profiles, editable routine suggestions, and optional products/diary |
| Other modules | [`vanity`](../backend/src/main/java/br/com/autocuidado/vanity), [`push`](../backend/src/main/java/br/com/autocuidado/push), [`billing`](../backend/src/main/java/br/com/autocuidado/billing), [`forum`](../backend/src/main/java/br/com/autocuidado/forum) | Inventory, cleaning, notification delivery, subscriptions, and moderated discussion |
| Schema | [`db/migration`](../backend/src/main/resources/db/migration) | Ordered Flyway migrations |
| Delivery | [`compose.yaml`](../compose.yaml), [`deploy`](../deploy), [workflows](../.github/workflows) | Local services, deployment, CI, and recovery procedures |

### Local topology

Docker Compose runs Nginx/Angular, the API, PostgreSQL, role setup, and a separate migration job. The optional `mail` profile starts Mailpit. Nginx forwards `/api/` to Spring Boot so the browser uses one origin for UI and API.

### Published topology

A Cloudflare Worker forwards requests to a Durable Object controlling a container with Nginx and Java. The database is PostgreSQL on Neon. The runtime uses a direct TLS-verified JDBC connection and its own limited Hikari pool. Migration credentials are separate from runtime credentials. The [deployment guide](../deploy/README.md) describes this path and the separate Compose/SSH workflow.

### Consistency decisions

- The server determines the account from the authenticated session rather than accepting a client-supplied owner.
- Preview and save share the recurrence/suggestion rules. Saving revalidates dates and stores the plan and occurrences transactionally.
- Plan creation associates a request ID with a canonical request hash. A matching retry returns the original result; reusing the ID with different input is a conflict.
- Account-level locking serializes relevant changes. A stale suggested preview must be regenerated after relevant profile, history, or reservation changes.
- Saved suggestions preserve versioned instructions, reasons, intervals, and original dates. Later profile edits do not rewrite existing plans.
- SQL constraints protect ownership/category relationships, valid states, and uniqueness independently of the UI.

## Business rules

| ID | Rule |
|---|---|
| BR-01 | A recurring plan lasts 1–12 weeks, with at least one distinct weekday and at most 84 occurrences. |
| BR-02 | Initial appointments start today or later. Dates are civil dates (`YYYY-MM-DD`); the current account/preview time zone is `America/Sao_Paulo`. Weeks run Monday–Sunday. |
| BR-03 | Recurrence includes the start date and ends the day before the start plus the selected number of weeks. Only selected weekdays appear, in order, without duplicates. |
| BR-04 | An occurrence planned for a future date cannot be completed, even if the request supplies today's completion date. Eligible care may first be rescheduled to today. |
| BR-05 | Completion dates cannot be in the future. Direct history entries are available for completed makeup/nail care without a planned date. |
| BR-06 | A plan has at most one occurrence on a planned date. Different plans and standalone care may share a date. Rescheduling targets today or later and requires an eligible planned item. |
| BR-07 | Closing a plan hides still-planned occurrences from the closing date onward and prevents rescheduling. Earlier pending care and completed/skipped history are preserved. Undo respects the closure cutoff. |
| BR-08 | Weekly completed totals use the date of completion. Skipped history uses the action date. Overdue is a display condition of a planned item, not a separate persisted status. |
| BR-09 | Upcoming care excludes overdue items. Same-day ties put morning before night, then items without a period; remaining ties use creation order. |
| BR-10 | Each civil day with completed care counts once toward the streak, across all categories. Skipping, visiting, and reading messages do not count. Undo/backdated completion recalculates it. |
| BR-11 | An empty or incomplete hair profile does not block the manual planner, agenda, or history. Suggestions remain editable. |
| BR-12 | Automatic wash days require a declared comfortable weekly frequency. Additional hair stages require sufficient information; hydration has contextual conditions, while nutrition/reconstruction use known intervals and application history. Positive/unknown warning signs prevent automatic extras. |
| BR-13 | Suggested care respects calendar-day/week/month intervals, completed history, and active reservations. It does not stack two extra stages in one session or derive nutrient deficiencies from curl pattern. |
| BR-14 | Account names/photos, profiles, inventory, plans, and history are private to their owner. Resources belonging to another account receive the same missing-resource response as nonexistent resources. |
| BR-15 | Account photos accept JPEG/PNG up to 5 MiB and are normalized to JPEG with a maximum edge of 512 pixels and no original metadata. |

See the [recurrence generator](../backend/src/main/java/br/com/autocuidado/recurrence/RecurrenceDateGenerator.java), [agenda service](../backend/src/main/java/br/com/autocuidado/persistence/AgendaService.java), and [hair-plan service](../backend/src/main/java/br/com/autocuidado/persistence/HairPlanService.java) for the implementation.

## Optional modules and release boundaries

| Module | Recorded public-release status | Activation dependency |
|---|---|---|
| Product suggestions | Disabled | Catalog/editorial and accessibility acceptance |
| Browser Web Push | Disabled | Explicit permission, dedicated keys, and delivery/click verification on the release origin/browser |
| Billing | Disabled | Stripe sandbox acceptance for Checkout, portal, and signed webhooks |
| Private hair diary | Disabled | Subscription integration and its own acceptance |
| Forum | Disabled | Public-reading/moderation acceptance and subscription integration before participation |

When enabled in an isolated test instance, forum reading exposes approved content only. Participation requires a server-confirmed active subscription. Topics share a limit of one per day in `America/Sao_Paulo`; comments have a separate rate limit, and new content enters moderation. Repeated or out-of-order billing events must not duplicate entitlements.

Feature flags apply to the instance. Local test activation is not authorization to enable a module in the published environment. Defaults are defined in [.env.example](../.env.example) and [compose.yaml](../compose.yaml); actual deployed settings remain private.
