# Self-Care Planner

Agenda de autocuidado is a web app for planning and tracking hair care, skin care, makeup, and nail care. It includes hair care schedules, reminders, and a history of completed care.

Preview a hair care schedule without an account. Sign in to save sessions to your planner, log completed care, and view your history. The app also offers a hair profile, guides, and planner reminders.

The web interface uses Angular and Nginx in `frontend/`. The API uses Spring Boot and Flyway in `backend/`, with PostgreSQL 18.7. Docker Compose serves the web interface and the API from the same origin.

The app is in pre-release. Products, Web Push, billing, the hair care journal, and the forum are disabled by default in the local environment.

To review the forum in a dedicated local Compose project, set `LOCAL_FORUM_PUBLIC_READING_ENABLED=true` and open `/forum`. The public list, search and direct links show approved content only. Set `LOCAL_FORUM_ENABLED=true` as well to enable participation for accounts with a current subscription confirmed by the server. Subscribers can submit one topic per day in the America/Sao_Paulo time zone. Comments have a separate rate limit. Both enter moderation before they appear publicly. Keep both settings off on the release candidate until reading, participation and the billing integration have passed acceptance.

## Run locally with Docker Compose

With Docker running, copy `.env.example` to `.env` if you do not already have a local environment file. Set `LOCAL_DB_PASSWORD`, `LOCAL_DB_MIGRATION_PASSWORD`, and `LOCAL_DB_RUNTIME_PASSWORD` to three distinct test passwords. Run `openssl rand -hex 32` to generate a dedicated key for `LOCAL_AUTH_MFA_KEY` and keep it unchanged while using the same volume. From the repository root, run:

```sh
docker compose --profile mail up --build --wait
```

Open the app on port 4222. The frontend proxies `/api/` on the same origin. The API and PostgreSQL are also available on `127.0.0.1` at ports 8102 and 56422, respectively. The `mail` profile starts Mailpit on port 8025, where you can retrieve registration, authenticator setup, and password reset codes for test accounts. [Local app](http://127.0.0.1:4222/) · [Mailpit](http://127.0.0.1:8025/)

You can configure the ports, database name, and credentials in `.env`. Keep the `postgres-data` volume separate from the pilot environment. If you are reusing an existing volume, keep the database name and administrator credentials and add the two role passwords. The `db-roles` service sets up the dedicated schema and database roles. The `migrate` service runs Flyway as `autocuidado_migration`. The API runs as `autocuidado_runtime`, with read and write access to the application tables. Both setup services exit with code 0 on success.

To check persistence, generate a preview, verify a test account through Mailpit, sign in, and save the schedule. Run `docker compose --profile mail down`, then `docker compose --profile mail up --wait`. Sign in again and open the saved schedule. Flyway applies pending migrations and validates existing ones each time the migration service runs. Use `down` to preserve the volume during the test.

## Local development

Use JDK 25.0.4.1 and Node.js 22.23.5, as specified in `.java-version` and `.nvmrc`. The backend uses the Gradle Wrapper. The frontend uses npm with dependency versions locked in `package-lock.json`.

Create a local PostgreSQL database and make the application user its owner, with permission to create tables in the `public` schema. At startup, Flyway applies migrations from `backend/src/main/resources/db/migration/` and Hibernate validates the mappings.

In a Bash terminal, enter the connection details and test MFA key before starting the backend. Generate the key with `openssl rand -hex 32` and reuse it for the same database. Adjust the URL if your database uses a different name or port.

```sh
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/autocuidado
read -r -p 'PostgreSQL username: ' SPRING_DATASOURCE_USERNAME
read -r -s -p 'PostgreSQL password: ' SPRING_DATASOURCE_PASSWORD
printf '\n'
read -r -s -p 'Local MFA key in hexadecimal: ' APP_AUTH_MFA_KEY
printf '\n'
export SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD APP_AUTH_MFA_KEY
cd backend
./gradlew bootRun --args='--server.address=127.0.0.1'
```

In another terminal, start the frontend:

```sh
cd frontend
npm ci
npm start -- --host 127.0.0.1
```

Open the [local app](http://127.0.0.1:4200/). The home screen requests `/api/health` from the same origin. Angular forwards the request to Spring Boot on port 8080.

## Create a test account and sign in

To send verification, authenticator setup, and recovery codes, set `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, and `APP_AUTH_EMAIL_FROM` before starting the backend. When running the API directly, use `APP_AUTH_MFA_KEY` for the hex-encoded 32-byte key. In development, use a local SMTP server that captures outgoing mail. The `publicacao` profile configures STARTTLS and authentication. Activate `publicacao,cloudflare-mail` to use Cloudflare Email Sending over implicit TLS with a verified sender and `SPRING_MAIL_PASSWORD` provided by the secret store. Keep credentials outside the repository.

1. In the planner (**Agenda**), select **Quero criar uma conta** (Create an account), enter a test email address, and request a code. Copy the code from Mailpit and choose a password of 8–128 characters with an uppercase letter, a lowercase letter, a digit, and a symbol. Spaces alone do not satisfy the symbol requirement. The API applies the same policy when changing or resetting a password and checks the Pwned Passwords service for compromised passwords.
2. Sign in with your email and password. Ordinary accounts can use the planner without an authenticator.
3. To enable two-factor authentication, open **Perfil → Verificação em duas etapas**, confirm your current password and the new email code, and add the setup key to a time-based one-time password (TOTP) authenticator app. Save the recovery key and confirm with an authenticator code. Enabling or disabling this protection ends existing sessions. Once enabled, sign-in requires your password and an authenticator code; disabling it requires your current password and a fresh authenticator code. The administrative owner must keep two-factor authentication enabled.

Registration is closed by default in the `publicacao` profile.

## Test the hair care schedule preview (FSD-03A)

With the app running through Compose, send a start date, a duration in weeks, and the selected weekdays to `POST /api/hair-plans/preview`. All accounts use `America/Sao_Paulo`; omitting `timeZone` uses this default, and other zones are rejected. Use port 4200 for the Angular development server. This public endpoint returns a preview without saving the plan.

```sh
start_date=$(TZ=America/Sao_Paulo date +%F)
curl -sS http://127.0.0.1:4222/api/hair-plans/preview \
  -H 'Content-Type: application/json' \
  --data "{\"startDate\":\"${start_date}\",\"weeks\":2,\"weekdays\":[\"MONDAY\",\"THURSDAY\"],\"timeZone\":\"America/Sao_Paulo\"}"
```

The HTTP 200 response contains `occurrences`, with each `date` in `YYYY-MM-DD` format and `stage` set to `WASH_ONLY` (Washing and conditioning). Invalid inputs return HTTP 400 with an `errors` object keyed by field name. Use uppercase weekday values from `MONDAY` to `SUNDAY`.

## Resume a preview after signing in (FSD-04C)

Generate a preview, customize a treatment stage, and select **Entrar para salvar na agenda** (Sign in to save to your planner). After you sign in, the form restores the date, duration, weekdays, and stages you selected. The API uses `America/Sao_Paulo` for both public previews and signed-in accounts. If the start date has already passed in São Paulo, adjust it and generate the preview again. The selected stages carry over for dates that appear in both previews.

## Build and test

- In `backend/`, run `./gradlew clean build` to run the unit and API tests without a database.
- To verify FSD-04A, run `APP_AUTH_MFA_REQUIRED=false ./gradlew postgresqlTest` in `backend/` with the `SPRING_DATASOURCE_*` variables pointing to an empty PostgreSQL database dedicated to that run. The suite signs in to test accounts using passwords. It applies the migrations, writes test data, verifies the date constraint for each plan, and restarts the app to validate the schema and read the data back. Use a fresh, empty database for each run.
- In `frontend/`, run `npm ci`, `npm run build`, and `npm test`.

GitHub Actions scans the Git history with Gitleaks, audits npm dependencies, builds Angular and Spring Boot, and runs tests, including integration tests against a disposable PostgreSQL database, simulated Compose deployment and rollback tests using Python's standard library, and Cloudflare origin and lifecycle tests using Node. The manual release workflow uses Docker Compose on a prepared host. The deployment guide also documents the Cloudflare Containers candidate and its remaining provider checks. [CI workflow](.github/workflows/ci.yml) · [Deployment and rollback guide](deploy/README.md)

## Test hair profile persistence (E2-04)

Sign in to the local app with a test account. `GET /api/hair-profile` returns the signed-in account's answers, or `{}` if there are none. `PUT /api/hair-profile` requires the token from `/api/auth/csrf` and replaces all answers with the submitted object. For example, `{"rootOiliness":"RARELY"}` saves only the answer about oily roots, and `{}` clears the profile. An invalid answer returns HTTP 400 with field-level `errors` and leaves the saved profile unchanged. The planner, schedule, and history remain available with an empty profile.

Compose module settings apply to the entire instance and preserve the database.

## Test the private account profile and suggested routine

In **Perfil**, save an optional name and upload, replace, or remove a JPEG/PNG photo of up to 5 MiB. The server normalizes photos to JPEG with a maximum edge of 512 pixels and strips metadata. Name and photo are private to the signed-in account. **Alterar senha** opens `/perfil/alterar-senha` and keeps the existing password/MFA checks and session revocation.

In the planner, select **Receber sugestão** and use the saved hair profile. Each chosen date gets a complete washing, conditioning, detangling, and drying routine, adapted to supported profile answers. Saving a suggested plan preserves its instructions even after the profile changes. Hydration, nutrition, and reconstruction remain optional manual choices; profile answers do not establish validated treatment intervals. Suggestions organize care without selecting products.

## Test browser notifications (E2-10)

On a test instance, use HTTPS or the local origin `http://127.0.0.1:4222`. Generate a dedicated VAPID P-256 key pair. Set `LOCAL_WEB_PUSH_PUBLIC_KEY` to the 65-byte public point and `LOCAL_WEB_PUSH_PRIVATE_KEY` to the 32-byte private scalar, both encoded as Base64url without padding. Keep the private key in the local environment file. Start Compose with `LOCAL_WEB_PUSH_ENABLED=true` and keep `LOCAL_AGENDA_REMINDERS_ENABLED=true`.

Using a test account, go to **Configurações → Lembretes** (Settings → Reminders) and save a category and a future time in the account's time zone. Schedule a care session in that category and select **Ativar notificações** (Enable notifications) in a supported browser. At the scheduled time, the generic notification should open the planner. Repeat with two subscriptions, deny permission in another browser, and try signing out before the next reminder. Signing in again clears the previous local subscription so you can explicitly enable notifications again.

To suspend delivery in Compose, restart it with `LOCAL_WEB_PUSH_ENABLED=false`. The app uses VAPID to send a request with no payload. The browser looks up the subscription by its opaque address and displays the generic message. An `ACCEPTED` record means the provider accepted the request. The dispatch and retry window closes ten minutes after the scheduled time or at midnight in the local time zone, whichever comes first. [RFC 8030](https://www.rfc-editor.org/rfc/rfc8030) · [RFC 8292](https://www.rfc-editor.org/rfc/rfc8292)

To reconcile delivery attempts, query `web_push_attempts` for `state = 'UNCERTAIN'`. Use the ID, timestamp, and recorded HTTP status, if available, to compare against the provider's records. Keep the attempt in the uncertain state if you cannot confirm the outcome. The automatic queue never retries an uncertain attempt. Keep subscription addresses out of support records.
