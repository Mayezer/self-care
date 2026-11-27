# Deployment and recovery

This directory versions deployment sources, tests, and configuration examples without credentials. Effective configuration, certificates, keys, and operational state belong in protected storage. See [Security](../docs/SECURITY.md) for publication boundaries.

The pilot release recorded on November 26, 2026 uses Cloudflare Workers/Containers with PostgreSQL on Neon. This guide documents that deployment path and the separate Compose/SSH workflow. Examples are templates, not the active environment's resource names or credentials. Deploying documentation changes is unnecessary.

## Cloudflare Containers

A Worker forwards the canonical domain to a Durable Object managing the container. The container preserves Spring Boot, JDBC, Flyway, and Nginx. Java listens on loopback; Nginx checks a separate origin credential. The Worker replaces forwarded headers and the proxy applies request limits using the trusted client address. See [Containers architecture](https://developers.cloudflare.com/containers/concepts/architecture/).

[`wrangler.example.json`](wrangler.example.json) is a QA template without public routes, with `workers.dev` and previews disabled. A dry run verifies the bundle/configuration model, not container startup, TLS, SMTP, or provider rollback.

The manual [Cloudflare workflow](../.github/workflows/cloudflare.yml) uses GitHub environments `homologacao` and `producao`. Configure these independently:

| Protected name | Purpose |
|---|---|
| `CLOUDFLARE_API_TOKEN` | Provider access limited to required account/Workers/Containers permissions |
| `CLOUDFLARE_DEPLOY_CONFIG` | Effective Wrangler JSON, without runtime secret values |
| `MIGRATION_JDBC_URL`, `MIGRATION_PASSWORD` | Direct migration connection and credentials |
| `CLOUDFLARE_QA_KEY` | QA access, matching the QA Worker's private `RP03_QA_KEY` |

Runtime credentials stay in Worker secrets. QA uses the `QaAgendaContainer` class and corresponding bindings; production uses `AgendaContainer`. Keep each environment's effective names, databases, and values private. The workflow resolves source paths from the checkout and applies immediate code updates.

Registration is closed by default. Set `AUTOCUIDADO_REGISTRATION_ENABLED` to `true` in the protected environment's `vars` only after email confirmation, sign-in, and account-level MFA checks pass. Ordinary accounts may opt into TOTP through their profile; previously enrolled accounts retain protection until safely disabled. The owner must retain MFA. Keep `APP_AUTH_MFA_REQUIRED=true` in publication settings. Registration does not activate optional modules.

### Build, migrate, and promote

1. Use a dedicated Neon project/environment and separate migration/runtime database roles. Runtime credentials must not administer roles, create schema, change Flyway history, truncate application tables, or provision the owner. The grants are defined in [db-roles.sql](../backend/db-roles.sql).
2. Use direct JDBC connections for migrations and this runtime. Keep Hikari bounded. The controller rejects the transaction-pool hostname because this application's concurrency tests reproduced isolation-related failures on that connection path. Verify both the certificate and hostname with `sslmode=verify-full` and `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`. See [Neon connection pooling](https://neon.com/docs/connect/connection-pooling).
3. Build API/frontend from the same CI-approved revision. Set `PUBLIC_SITE_ORIGIN` during the Angular build. Supply immutable `WEB_IMAGE` and `API_IMAGE` references to [Dockerfile.cloudflare](Dockerfile.cloudflare). Scan the combined image before promotion.
4. Run Flyway through the API image's `--migrate` entry point using migration credentials in a separate job before activation. Flyway remains disabled in the deployed API runtime.
5. Configure the verified sender through `AUTOCUIDADO_SMTP_FROM`. Supply `AUTOCUIDADO_DB_RUNTIME_URL`, `AUTOCUIDADO_DB_RUNTIME_PASSWORD`, `AUTOCUIDADO_SMTP_PASSWORD`, `AUTOCUIDADO_MFA_KEY`, and `AUTOCUIDADO_EDGE_TOKEN` through protected Worker secrets, using the private configuration with `wrangler secret put --config`. Enter values through prompts; do not include them in command-line literals or logs. The JDBC URL contains no username/password. MFA and origin keys are distinct random 32-byte hexadecimal values.
6. Keep QA access-controlled. Verify identity, email delivery, effective client address, origin protection, HTTPS, CSP, cookies, request limits, alerting, database recovery, and SEO on the actual release candidate before exposing a new environment or module.

In `deploy` mode, CI must pass; the workflow builds images, applies migrations, and activates the combined container. In `rollback` mode, supply an approved immutable image compatible with the current schema; the workflow does not rebuild or migrate. Both paths check five probes through a private binding. That smoke does not replace edge-TLS and authenticated-flow checks on the canonical origin.

[The supervisor](start-cloudflare.sh) forwards logs to the platform without accumulating local log files, propagates shutdown, and stops the instance if Java, Nginx, or log forwarding fails. Resource/lifecycle settings are versioned in the controller; recheck startup latency, scheduled work, sessions, and cost after changes. See [resource limits](https://developers.cloudflare.com/containers/platform/limits/) and [container lifecycle](https://developers.cloudflare.com/containers/api/durable-object-container/).

Under the platform's `durable_object` policy, a local container UID does not prove a Linux privilege boundary in the provider VM. The Worker does not expose remote command execution, and migration credentials remain outside the application runtime.

### Validate without deployment

With the Node.js version in `.nvmrc`, run from the repository root:

```sh
node deploy/test_cloudflare.mjs
npx --yes wrangler@4.165.0 deploy --config deploy/wrangler.example.json --dry-run
```

The Node suite checks proxy trust, host/HTTPS enforcement, protected configuration, runtime privileges, concurrent startup, promotion probes, and failure recovery. The controller stores image references and a configuration fingerprint rather than credentials in Durable Object state. Failed activation restores a previous image only when the protected configuration matches. A failed first deployment or incompatible credential change leaves the application stopped. Verify these behaviors on the provider before release.

## Deployment simulations

From the repository root, with Python 3.10+ on Linux:

```sh
python3 -B -m unittest discover -s deploy -p 'test_*.py' -v
```

CI runs both Python and Node suites. Python uses the standard library to test Compose deployment, rollback, failed updates/migrations/startup, and invalid-image handling. Docker/HTTPS are simulated; state files are isolated and removed afterward. State advances only after the probes pass, and image rollback does not migrate. Simulations do not validate real infrastructure.

## Separate Compose/SSH path

The manual [Compose release workflow](../.github/workflows/release.yml) requires a prepared Linux host with Docker Compose, Python 3.10+, `curl`, and SSH. It does not deploy Cloudflare Containers.

1. Install `release.py`, `compose.yaml`, and `nginx.publicacao-tls.conf` under the host's deployment directory. Restrict write access to configuration/state. Treat Docker access as privileged. Use separate SSH keys without forwarding and verify host keys independently.
2. Derive protected host configuration from [release.example.json](release.example.json) and [publicacao.env.example](publicacao.env.example). Configure GitHub variables `DEPLOY_SSH_HOST`/`DEPLOY_SSH_USER` and secrets `DEPLOY_SSH_KEY`/`DEPLOY_SSH_KNOWN_HOSTS` per environment. Give registry credentials only the required package-read access.
3. Use a dedicated database, separated migration/runtime roles, verified TLS, and appropriate network access controls. Store the MFA encryption key independently of database backups.
4. Configure DNS, origin certificates, direct-origin protection, trusted proxy forwarding, and alerts. Private responses and nonce-bearing HTML retain `no-store`. Verify certificates when using a private CA; never substitute an insecure TLS bypass.
5. Run `deploy` in QA first. The workflow requires CI, builds/publishes immutable images, applies migrations, and activates the application. Check HTTPS, readiness, anonymous-access rejection, preview, and module flags, then exercise private flows with fictitious accounts.
6. Use `rollback` to restore the approved previous image pair. Failed probes trigger attempted restoration; a failed first deployment stops the application. If rollback also fails, keep access restricted while recovering.

Image rollback preserves the database and Flyway history. Test previous-image compatibility with the new schema before promoting migrations. Preserve approved images and protected operation state. Schema V24 adds private account profiles and saved hair-routine data and accepts only `America/Sao_Paulo`; reverting to an older client that sends another zone requires a specific compatibility check.

## Database/configuration cutovers

Keep access restricted and stop application writes before changing database targets or credentials. Use immediate Durable Object updates. After changing a Worker secret, explicitly deploy the intended version at 100% using `--durable-objects-code-update-mode immediate`; an already-running object can otherwise keep older configuration temporarily. Before reopening, verify effective database selection through a distinguishing synthetic write/read as well as readiness probes. HTTP 200 alone does not prove a cutover. See [Durable Object updates](https://developers.cloudflare.com/durable-objects/deployments/durable-objects-code-updates/).

## Recovery and alerting

1. Define retention and exercise point-in-time recovery into an isolated database. Write synthetic care before/after the target, verify which records survive, and check roles, Flyway, and integrity. Measure loss and time through an authenticated smoke. Neon snapshot-based workflows have branch prerequisites; verify the [documented recovery procedure](https://neon.com/docs/ai/ai-database-versioning) for the selected operation.
2. Reconcile current passwords, MFA, and revocations before reopening a restored database. Check that old credentials/sessions remain rejected and current factors work. Keep the current MFA key separate from backups. If no trusted current identity source is available, keep access closed until identity can be recovered independently; the application does not automatically reopen on stale credentials.
3. Recheck agenda, history, schedules, profiles, and reminders with synthetic accounts. Verify private profile and saved-routine data as well as legacy records.
4. Monitor process health at `/api/health`, database readiness at `/api/ready`, and an authenticated critical flow. Exercise a database outage only in QA, confirm alert delivery, restore access, and record recovery without private payloads.

## Email and owner provisioning

Use a verified sender and the protected SMTP configuration. The `publicacao` profile uses authenticated STARTTLS; add `cloudflare-mail` for the implicit-TLS Cloudflare SMTP configuration. Validate registration-code expiry/single use, delivery, password recovery, optional MFA, and the owner's mandatory MFA. See [Cloudflare SMTP](https://developers.cloudflare.com/email-service/api/send-emails/smtp/).

For the Compose path, after loading approved image references into `AUTOCUIDADO_WEB_IMAGE`/`AUTOCUIDADO_API_IMAGE`, use the protected host configuration and its project name. Example installation paths:

```sh
docker compose --env-file /etc/autocuidado/publicacao.env -p autocuidado-publicacao \
  -f /opt/autocuidado/deploy/compose.yaml run --rm --no-deps migrate \
  java -jar autocuidado.jar --provision-owner
```

Enter account credentials through protected prompts, never command-line literals. Verify that an owner session without MFA is rejected, an ordinary account cannot access administrative functions, the MFA-protected owner can, and a second owner cannot be provisioned.

## Public delivery and release checks

Verify canonical HTTPS, alias redirects, public metadata, enabled guides, `robots.txt`, sitemap, HTTP 404, private-page `noindex`, and anonymous API rejection. Search exclusion does not protect private data; authorization does. Check the public origin after promotion and make an explicit keep/pause/rollback decision based on release observations. Synthetic tests and short observation windows are not an uptime SLA or evidence of search indexing or real-user performance.

Keep Products, Web Push, billing, the diary, and forum disabled until their module-specific acceptance is complete. For the dated release scope and future documentation updates, see [Project](../docs/PROJECT.md) and [Security](../docs/SECURITY.md).
