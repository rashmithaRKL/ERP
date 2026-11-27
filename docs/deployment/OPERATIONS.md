# Deployment and recovery

## Local development and demonstration

Run `npm ci` then `npm start` with Java 21. Data is persisted in `.local/postgres` by the explicit `demo` profile. Demo PostgreSQL is an embedded test distribution with local trust authentication; HTTP cookies are non-secure only in this loopback profile. The normal profile requires configured PostgreSQL credentials and secure cookies. Never bind the demo API/database to an untrusted network.

The first Maven download is verified against the committed official SHA-512 checksum. npm uses the committed lockfile. Java dependency versions follow the pinned Spring Boot BOM. Re-review security updates before any production release; reproducibility alone is not a security certification.

On macOS/Linux the launcher stops its owned child processes on interruption. Windows development requires Java 21 and Maven on PATH. A signed, commercial Windows installer is not included.

## Docker deployment candidate

Prerequisites: Docker Engine/Compose, reachable DNS name and ports 80/443, protected persistent disk and a secret manager. This configuration has **not been executed here** because Docker is unavailable. Validate it on a staging host first.

1. Use `.env.example` as a template for an ignored `.env`, or inject the same variables from your deployment secret manager.
2. Generate a unique random `DATABASE_PASSWORD` and an initial `BOOTSTRAP_PASSWORD` of at least 16 characters. Set `BOOTSTRAP_EMAIL` to the organization administrator and `DOMAIN` to the chosen DNS hostname. Generate `MFA_ENCRYPTION_KEY` with `openssl rand -base64 32` (V17; the API refuses to start without it) and store it in the same custody as the backup keys. No shared/default password or key is provided.
3. Run `docker compose config --quiet`, then `docker compose up --build -d`.
4. Caddy terminates HTTPS; web/API/database have no published host ports. PostgreSQL is accessible only on the internal deployment network. Caddy certificates need valid DNS and network reachability. For a private LAN use a properly installed customer certificate/trust chain; do not instruct operators to bypass browser certificate warnings.
5. Check `docker compose ps` and logs, then load the HTTPS hostname. Sign in through the normal email/password form; production has no demo role entry.
6. Configure organization, users, items, partners, work centers and BOMs. A second authorized person is required for document approval. Items, customers/suppliers and opening stock can be loaded with **Administration → Imports** (see *Imports* below); otherwise starting inventory enters through approved purchases and incoming QC.
7. Run the workflow acceptance cases, reconciliation and recovery drill before loading real operations.
8. Sign in as the bootstrap administrator and change its password from the profile menu (**Change password**); move the bootstrap credentials into your approved custody process. At that first sign-in the administrator must also set up two-step sign-in (an authenticator app, V17) and store the ten recovery codes safely. Email-based recovery still requires completion before privileged commercial deployment.

## Users and passwords

All user administration is in **Administration → Users** (ADMIN only) and is audited.

- **Passwords**: 16 to 128 characters (a short sentence works well), never the account's email address; stored as bcrypt (cost 12). The same rule applies to the bootstrap password.
- **New accounts** (*Add team member*): the administrator sets an initial password and shares it through the approved channel. At the first sign-in the user must choose their own password before anything else works.
- **Forgotten password**: open the user and choose *Reset password*. The response shows a one-time temporary password exactly once (it is not stored in clear text anywhere, also not in the idempotency record; a repeated request shows nothing — reset again). The user's open sessions end immediately, and at the next sign-in they must replace the temporary password. Administrators change their own password from the profile menu instead.
- **Two-step sign-in (V17)**: every user can turn it on from the profile menu (**Two-step sign-in**); administrators must, and cannot turn it off. Sign-in then asks for the 6-digit code from the authenticator app, or one of ten single-use recovery codes. A user who lost their phone and their recovery codes asks an administrator: open the user and choose *Reset two-step sign-in* (their sessions end; an administrator sets it up again at the next sign-in). Wrong codes are limited to 5 per account in 15 minutes.
- **The two-step sign-in key**: `MFA_ENCRYPTION_KEY` encrypts the authenticator secrets in the database. Back it up with your recovery keys. Without it the stored secrets cannot be read: every affected user needs an administrator reset, and if no administrator can sign in, clearing an administrator's two-step sign-in needs direct database access (`UPDATE users SET mfa_secret=NULL, mfa_enabled_at=NULL, mfa_last_step=NULL, session_version=session_version+1 WHERE email=…; DELETE FROM mfa_recovery_codes WHERE user_id=…`). Key rotation is not supported yet.
- **Leavers**: *Deactivate* blocks sign-in and ends the user's open sessions on their next request; history and documents keep their name. *Reactivate* restores access (old sessions stay ended). You cannot deactivate yourself, and the last active administrator can never be demoted or deactivated (also enforced by a database trigger) — promote another administrator first.
- **Role changes** apply on the user's next request without signing them out.
- **How revocation works**: every account has a `session_version`, copied into the session at sign-in and incremented on deactivation, reset and password change. Every authenticated request compares it with the database (one primary-key lookup), so revocation works across API restarts and several API processes, unlike an in-memory session registry.
- **Lost access to every administrator account**: there is no back door. Restore from backup or, with database access, promote an existing active user (`UPDATE users SET role='ADMIN' WHERE email=…`) under your change-control process and audit it manually.

## Approval policies

**Administration → Approval policies** configures, per document type, whether a second person must approve, above which value, and which roles besides ADMIN may approve. The person who created a document can never approve it, including administrators. In this release the policy is in effect for stock adjustments and cycle-count variances (the Inventory page's threshold chip edits the same policy); manufacturing and purchase order approval keep their fixed rule (always, a different planner or administrator) until the integration release, and the page says so.

## Imports

**Administration → Imports** (ADMIN only) loads items, customers/suppliers and opening stock from CSV files.

1. Download the template for the import type and fill it in a spreadsheet; save as *CSV UTF-8* (comma or semicolon separated, header in the first row). Limits: 2,000 data rows and 2 MB per file; split larger files.
2. Choose the file. The server validates it by running the whole import in a transaction that is always rolled back and shows every row error (row, column, problem) and a preview of what would be created. Nothing is saved.
3. Fix the spreadsheet and choose the file again until it is valid, then **Import**. The import runs again in one transaction: if any row fails now (for example a SKU was created meanwhile), nothing is imported.
4. Import order for a new installation: items → customers/suppliers → opening stock (opening stock refers to item SKUs and warehouse codes).

Each file's content can be imported once per organization: re-uploading the same content (even re-saved with different line endings) creates nothing and points to the original import (*Import history*). Opening stock is in each item's base unit at the given unit cost: one lot (status `RELEASED`, or `QUARANTINE` with a pending quality inspection) and one ledger receipt per row, and one balanced journal entry per inventory account against `OPENING_EQUITY`. Post opening stock while the current period is open; a closed period rejects the file. Every import is audited (one `IMPORT_COMMITTED` event plus the normal audit event of each created record).

## Audit trail and accounting periods

**Administration → Audit trail** pages through the whole audit history with filters for person, action, text (document number, email, detail) and date range (Asia/Colombo days). *Export CSV* streams the filtered events (up to 50,000 per file; narrow the filters for more); each export is itself audited.

**Administration → Accounting periods** closes periods in date order once their end date has been reached. Postings are always dated today, so closing a past period protects it, and closing a period that ends today stops every posting for the rest of the day. Only the most recently closed period can be reopened, with a reason that is kept in the audit trail.

The API binds to `0.0.0.0` only inside its unexposed container. The database migration/application credential currently owns the schema; separate migration and restricted runtime roles are a production hardening requirement. Add DB TLS/certificate verification for connections that cross hosts. Enable encryption at rest in the platform/volume layer. Database health details and support access must remain private.

## Backups

`scripts/backup.sh` runs `pg_dump` in the database container and encrypts the custom-format dump with **age** to a recovery public key. Install age from its official distribution; provision/store the recovery private identity separately. No private recovery key is needed on the live server.

```sh
# BACKUP_RECIPIENT is the recovery age public key, supplied by your backup configuration.
./scripts/backup.sh .local/backups
```

A temporary plaintext dump is created with mode 0600, cleaned on exit, and should be on encrypted storage. The destination directory is mode 0700. Copy the encrypted dump and checksum to independent/offsite storage. Set retention and scheduled execution only after the customer agrees the recovery objective; this repository does not silently create scheduled jobs.

## Restore rehearsal

```sh
# BACKUP_IDENTITY is the protected path to the recovery age identity.
./scripts/restore-recovery.sh /absolute/path/to/backup.dump.age
```

This command verifies checksum/authenticated decryption and restores into a **new** `dexlanka_recovery_<timestamp>` database. It never overwrites the running `dexlanka` database. Review unbalanced-journal and unreconciled-lot counts (both must be zero), tenant totals, quantities, WIP and dispatch references. Reconcile active reservation totals independently. Boot a separate staging API against the restored database, test representative workflows and record duration. Production cutover requires a separate reviewed operational decision; the script performs none.

No backup/restore drill was executed in the authoring environment. Agree and then prove RPO/RTO; do not market untested guarantees.

## Updates and failure handling

Back up before deploying. Test each new Flyway migration against a recovery clone; the previous checksum is immutable. PostgreSQL runs supported migrations transactionally; a migration error stops startup and rolls back the failing migration's DDL. Never repair a failed production migration by editing posted operational data. Restore/roll forward through a reviewed change and verify schema version before future synchronization.

A persistent local server can keep accepting supported operations without public internet, provided LAN, power, browser assets, local authentication and database remain available. Browser-only disconnection from that local server is **not supported for writes**. Cloud synchronization, version negotiation, partition ownership/transfer and reconciliation are later milestones. Never mark local outbox rows acknowledged unless a future real recipient commits them.

## Installation milestone

The commercial Windows single-PC/LAN installer must check compatibility, request OS permissions at action time, securely provision PostgreSQL/services/firewall, configure the organization, provision encrypted backups, run health checks, sign packages and support controlled upgrade/rollback. The current Docker/local setup is not that installer.
