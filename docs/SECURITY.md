# Security, configuration, and documentation maintenance

This document explains configuration handling and public documentation boundaries. It describes controls and verification steps, not a guarantee that a repository or deployment is free of vulnerabilities or secrets.

## Public and private files

The repository contains application code, versioned migrations, tests with synthetic data, workflows, and configuration examples with empty secret fields or explicit placeholders.

The public documentation allowlist is:

- `README.md`
- `docs/PROJECT.md`
- `docs/DEVELOPMENT.md`
- `docs/SECURITY.md`
- `docs/LESSONS_LEARNED.md`
- `deploy/README.md`

Other local documentation, acceptance records, incident histories, operational settings, and evidence are excluded by `.gitignore`. New documentation is private by default until reviewed and explicitly allowed. Do not copy internal records wholesale into public documents.

Keep `.env`, effective deployment settings, credentials, private keys, cookies, CSRF/session tokens, MFA setup/recovery keys, database dumps, browser storage state, HAR files, and private photos outside version control. Do not attach them to issues, pull requests, screenshots, CI artifacts, or public logs.

`.gitignore` only affects untracked files; it cannot remove data already committed. Review the actual diff and use a secret scanner before publication.

## Configuration boundaries

| Context | Versioned input | Private values |
|---|---|---|
| Local Compose | [`.env.example`](../.env.example), [`compose.yaml`](../compose.yaml) | Distinct database passwords, local MFA key, optional integration keys in `.env` |
| Direct development | Environment-variable names in the development guide | Database credentials, MFA key, local SMTP configuration |
| Cloudflare | [`wrangler.example.json`](../deploy/wrangler.example.json), [workflow](../.github/workflows/cloudflare.yml) | Per-environment GitHub/Worker secrets and effective deployment configuration |
| Compose/SSH deployment | [`publicacao.env.example`](../deploy/publicacao.env.example), [`release.example.json`](../deploy/release.example.json) | Protected host environment/configuration, SSH material, registry access, and runtime secrets |

Variable names document the contract; live values must not be published. Keep secret fields empty in `.example` files. Enter values through protected prompts, local secret files, or a secret store. Avoid command-line literals, shell tracing, and commands that print entire environment/configuration objects.

Each environment needs separate accounts, databases, secrets, and integrations. Preserve the MFA encryption key independently of database backups: replacing or losing it while retaining encrypted factors can make them unreadable. Rotate it through an explicit recovery/migration procedure.

## Application controls

- Server-side sessions and cookies authenticate private access; state changes require CSRF protection.
- Private reads/writes validate the authenticated owner. The client cannot choose another owner by sending an account ID.
- Passwords use PBKDF2 hashing and the server policy, including compromised-password checks. Password/MFA changes revoke sessions.
- Ordinary accounts may enable TOTP; the administrative owner must retain it. Setup and recovery material are credentials.
- Request limits and session expiry reduce abuse. Check deployed HTTPS, proxy trust, cookie flags, CSP, and origin protection in the actual environment.
- Migration and runtime database roles are separate. Runtime access does not need administrative/schema-changing privileges.
- Uploads are validated and rasterized; private photos remain protected by account ownership.
- Recovery reconciles current credentials/revocations before reopening access. Image rollback does not reverse database migrations.

Implementation references: [authentication](../backend/src/main/java/br/com/autocuidado/auth), [database roles](../backend/db-roles.sql), [Nginx](../frontend/nginx.conf), and [deployment](../deploy/README.md).

## Review before publication

1. Review the intended paths and exact diff. Do not stage an entire local documentation/evidence directory.
2. Check `git status --short`, `git diff --check`, `git diff --cached --stat`, and `git diff --cached --name-only`. Review content locally; never paste a sensitive diff into a public channel.
3. Confirm private environment/deployment settings, internal documents, keys, dumps, and browser state remain ignored and absent from staged files.
4. Scan candidate files with Gitleaks and redaction enabled. Use the scanner image pinned in [CI](../.github/workflows/ci.yml). A directory scan inspects uncommitted candidates; a Git scan inspects history.
5. Require the CI secret-history scan to pass on the published commit. A clean scan is one check, not proof that every possible secret format was recognized.
6. Verify relative links against files actually included in the repository, commands against current scripts/configuration, and module status against the accepted release scope.

## If a secret is exposed

Stop publishing affected material. Revoke/rotate the credential at its provider, invalidate affected sessions where applicable, and assess the exposure privately. Remove it from files, logs, artifacts, and history through coordinated cleanup; deleting it from the latest README is insufficient. Use redacted identifiers in public follow-up. Do not post the secret to demonstrate the issue.

## Keeping documentation current

Update the relevant document in the same change as a behavior, configuration, schema, or deployment change:

| Change | Documentation to review |
|---|---|
| Features, availability, or flags | README and `docs/PROJECT.md` |
| Business rules, ownership, or architecture | `docs/PROJECT.md` |
| Setup, commands, tests, variable names, or ports | `docs/DEVELOPMENT.md` and relevant examples |
| Identity, credentials, permissions, or publication boundaries | `docs/SECURITY.md` and `deploy/README.md` |
| Deployment, migrations, rollback, or recovery | `deploy/README.md` |
| A useful verified defect/fix or measurement | `docs/LESSONS_LEARNED.md`, with scope and limits |

Keep prose in English, preserve code/API/configuration identifiers, and use synthetic examples. Link exact versions to their maintained source files rather than repeating every patch version. Date release-status claims; distinguish implemented, tested, and publicly enabled behavior. Measurements are historical results for named scenarios, not permanent performance promises.
