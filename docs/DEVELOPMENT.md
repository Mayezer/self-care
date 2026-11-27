
# Development and verification

Use a dedicated Compose project, database, test accounts, and integrations. Never point integration tests at a production database. Keep configuration values in private local files or a secret store; see [Security](SECURITY.md).

## Docker Compose

1. Install Docker with Compose.
2. If `.env` does not exist, copy [.env.example](../.env.example) to it. Do not overwrite an existing environment file.
3. Set distinct local passwords for `LOCAL_DB_PASSWORD`, `LOCAL_DB_MIGRATION_PASSWORD`, and `LOCAL_DB_RUNTIME_PASSWORD`.
4. Set `LOCAL_AUTH_MFA_KEY` to a dedicated random 32-byte key encoded as 64 hexadecimal characters. Generate it locally with a cryptographic generator and preserve it while reusing the volume. Do not copy it into issues, screenshots, logs, or commits.
5. Run from the repository root:

```sh
docker compose -p autocuidado-dev --profile mail up --build --wait
```

| Service | Local address | Purpose |
|---|---|---|
| Web | [http://127.0.0.1:4222/](http://127.0.0.1:4222/) | Browser entry point; proxies `/api/` |
| API | `http://127.0.0.1:8102` | Direct local API access |
| PostgreSQL | `127.0.0.1:56422` | Local database |
| Mailpit | [http://127.0.0.1:8025/](http://127.0.0.1:8025/) | Captured test emails |

Change `LOCAL_*_PORT` values if ports are occupied. Keep the same `-p` project name throughout an environment's lifecycle. A distinct project separates volumes/networks but does not avoid host-port collisions by itself.

The `db-roles` job configures database roles. The `migrate` job runs Flyway as `autocuidado_migration`; the API uses `autocuidado_runtime`. Both setup jobs exit successfully after completing their work. The API waits for the database and migration job.

When reusing a volume, preserve its database name and credentials and the MFA key. Use separate project names and configuration for QA and the published environment.

### Verify persistence

Create a test account, save a schedule, and restart the environment:

```sh
docker compose -p autocuidado-dev --profile mail down
docker compose -p autocuidado-dev --profile mail up --wait
```

Sign in and read the saved schedule. `down` preserves named volumes; adding `--volumes` deletes their data. Flyway applies pending migrations and validates existing ones when the migration job runs.

## Run Java and Angular directly

Use the JDK in [.java-version](../.java-version), Node.js in [.nvmrc](../.nvmrc), the Gradle Wrapper, and the committed npm lockfile. Container builds use the versions pinned in their Dockerfiles.

Create a dedicated local PostgreSQL database. For this direct-development path, its application user needs permission to apply migrations. Deployed runtime credentials have a narrower role and do not apply migrations.

In Bash, enter local connection details and your locally generated MFA key through prompts rather than secret literals in shell history:

```sh
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/autocuidado_dev
read -r -p 'Local PostgreSQL username: ' SPRING_DATASOURCE_USERNAME
read -r -s -p 'Local PostgreSQL password: ' SPRING_DATASOURCE_PASSWORD
printf '\n'
read -r -s -p 'Local MFA key, 64 hexadecimal characters: ' APP_AUTH_MFA_KEY
printf '\n'
export SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD APP_AUTH_MFA_KEY
cd backend
./gradlew bootRun --args='--server.address=127.0.0.1'
```

Flyway applies migrations from `backend/src/main/resources/db/migration/`; Hibernate validates the mappings. To capture emails, configure `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, and `APP_AUTH_EMAIL_FROM` for a local SMTP catcher. The default Compose publishes Mailpit's web interface only; its SMTP port is internal. Publish a dedicated loopback SMTP port in your private local setup before connecting a directly started API, or use the full Compose stack.

In another terminal:

```sh
cd frontend
npm ci
npm start -- --host 127.0.0.1
```

Open [http://127.0.0.1:4200/](http://127.0.0.1:4200/). Angular's development proxy forwards `/api/` to Spring Boot on port 8080.

## Test accounts and MFA

1. Choose account creation in the planner and enter a disposable test email. Retrieve the confirmation code from Mailpit.
2. Choose a password of 8–128 characters with an uppercase letter, a lowercase letter, a digit, and a symbol. Spaces do not count as the symbol. Changes/resets use the same policy; the server also checks compromised passwords through Pwned Passwords.
3. Sign in with email/password. Ordinary accounts do not need an authenticator until they enable MFA.
4. To enable MFA, open the profile (`/perfil`), confirm the current password and a new email code, add the TOTP setup key to an authenticator, save the recovery key privately, and confirm an authenticator code.
5. Disabling MFA requires the current password and a fresh authenticator code. Enabling/disabling revokes existing sessions. The administrative owner must retain MFA.

Registration is enabled in the default local profile and closed by default in the `publicacao` profile. Deployment explicitly controls registration. Keep `APP_AUTH_MFA_REQUIRED=true` in publication settings; it makes account-level MFA controls available and enforces the owner's protection.

## Public preview API

From Bash, with Compose running:

```sh
start_date=$(TZ=America/Sao_Paulo date +%F)
curl -sS http://127.0.0.1:4222/api/hair-plans/preview \
  -H 'Content-Type: application/json' \
  --data "{\"startDate\":\"${start_date}\",\"weeks\":2,\"weekdays\":[\"MONDAY\",\"THURSDAY\"],\"timeZone\":\"America/Sao_Paulo\"}"
```

This public request returns HTTP 200 with `occurrences`, civil `date` values, and `WASH_ONLY` stages without saving a plan. Weekdays use uppercase names from `MONDAY` to `SUNDAY`. Duration is 1–12 weeks. Invalid fields return HTTP 400 with an `errors` object. Omitting `timeZone` uses São Paulo; other zones are rejected.

Generate a preview, edit a stage, and select sign-in to save. Confirm that dates/days and edits return after sign-in. If the start date has passed in São Paulo, adjust it and regenerate; edits survive for dates present in both previews.

## Private profiles and suggested routines

`GET /api/hair-profile` returns the current account's answers, or `{}`. `PUT /api/hair-profile` requires the CSRF token from `/api/auth/csrf` and replaces all answers: `{"rootOiliness":"RARELY"}` saves only that answer; `{}` clears the profile. Invalid input returns field errors and leaves stored data unchanged. An empty profile does not block the manual planner.

In the account profile, upload/replace/remove a JPEG/PNG photo up to 5 MiB. The server converts it to JPEG, caps the longest edge at 512 pixels, and strips metadata. Name/photo are private. Password changes use `/perfil/alterar-senha`.

In the hair profile (`/meu-cabelo`), optionally provide comfortable wash frequency, warning signs, and known/tolerated extra-care intervals with application dates or a known first-use plan. Intervals retain their units in days, weeks, or calendar months; instructions counted in washes are not converted to days.

Request a suggested routine and optionally balanced weekdays. Check reasons, missing-data explanations, and editability. Nutrition/reconstruction use supplied known intervals, without inferring deficiencies from curl pattern. Relevant profile/history/reservation changes invalidate the preview. Saved plans retain versioned reasons and original dates; moved sessions ask the user to review spacing. Old plans and matching retries remain readable.

## Tests

From `backend/`, run unit/API tests and build:

```sh
./gradlew clean build
```

For PostgreSQL tests, prepare a **fresh, empty, dedicated database** and set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` using the protected input pattern above. From `backend/`:

```sh
APP_AUTH_MFA_REQUIRED=false ./gradlew postgresqlTest
```

The suite uses fictitious password-based accounts, applies migrations, writes data, checks persistence/constraints/concurrency, and validates the schema after restart. Use a fresh database for every run. The MFA override is for this isolated suite only.

From `frontend/`:

```sh
npm ci
npm test
npm run build
```

From the repository root, test deployment decisions without deploying:

```sh
python3 -B -m unittest discover -s deploy -p 'test_*.py' -v
node deploy/test_cloudflare.mjs
```

[CI](../.github/workflows/ci.yml) also runs npm auditing and Gitleaks against Git history. Deployment simulations do not replace provider, SMTP, native-notification, accessibility, or real-user checks on an identified release.

## Optional modules in isolated tests

Settings apply to the instance and preserve its database. Keep optional modules disabled in the public release until accepted.

### Forum

Set `LOCAL_FORUM_PUBLIC_READING_ENABLED=true` to inspect approved content at `/forum`. Also set `LOCAL_FORUM_ENABLED=true` to test participation for accounts with server-confirmed active subscriptions. Topics share a daily quota; comments have a separate rate limit. New content enters moderation. Local fixtures do not constitute Stripe integration acceptance.

### Browser Web Push

Use HTTPS or `http://127.0.0.1:4222`. Generate a dedicated VAPID P-256 pair locally. `LOCAL_WEB_PUSH_PUBLIC_KEY` is the 65-byte public point; `LOCAL_WEB_PUSH_PRIVATE_KEY` is the 32-byte private scalar. Both use unpadded Base64url. Keep the private key in the private environment file.

Enable `LOCAL_WEB_PUSH_ENABLED=true` and keep `LOCAL_AGENDA_REMINDERS_ENABLED=true`. With a test account, save a category/future time at `/configuracoes/lembretes`, schedule matching care, and allow notifications explicitly. Verify delivery/click, multiple subscriptions, denied permission, and sign-out. Signing in again clears the previous local subscription so notifications can be explicitly enabled again.

Set `LOCAL_WEB_PUSH_ENABLED=false` and restart to suspend delivery. VAPID requests have no payload; the browser retrieves a generic notification through an opaque subscription address. `ACCEPTED` means provider acceptance, not confirmed display. The retry window ends ten minutes after the scheduled time or at local midnight, whichever comes first. `UNCERTAIN` attempts are not retried automatically; reconcile them privately without publishing subscription addresses.

See [Deployment](../deploy/README.md) for external-service configuration, publication, rollback, and recovery.
