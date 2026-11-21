# Deployment and recovery

## Local development and demonstration

Run `npm ci` then `npm start` with Java 21. Data is persisted in `.local/postgres` by the explicit `demo` profile. Demo PostgreSQL is an embedded test distribution with local trust authentication; HTTP cookies are non-secure only in this loopback profile. The normal profile requires configured PostgreSQL credentials and secure cookies. Never bind the demo API/database to an untrusted network.

The first Maven download is verified against the committed official SHA-512 checksum. npm uses the committed lockfile. Java dependency versions follow the pinned Spring Boot BOM. Re-review security updates before any production release; reproducibility alone is not a security certification.

On macOS/Linux the launcher stops its owned child processes on interruption. Windows development requires Java 21 and Maven on PATH. A signed, commercial Windows installer is not included.

## Docker deployment candidate

Prerequisites: Docker Engine/Compose, reachable DNS name and ports 80/443, protected persistent disk and a secret manager. This configuration has **not been executed here** because Docker is unavailable. Validate it on a staging host first.

1. Use `.env.example` as a template for an ignored `.env`, or inject the same variables from your deployment secret manager.
2. Generate a unique random `DATABASE_PASSWORD` and an initial `BOOTSTRAP_PASSWORD` of at least 16 characters. Set `BOOTSTRAP_EMAIL` to the organization administrator and `DOMAIN` to the chosen DNS hostname. No shared/default password is provided.
3. Run `docker compose config --quiet`, then `docker compose up --build -d`.
4. Caddy terminates HTTPS; web/API/database have no published host ports. PostgreSQL is accessible only on the internal deployment network. Caddy certificates need valid DNS and network reachability. For a private LAN use a properly installed customer certificate/trust chain; do not instruct operators to bypass browser certificate warnings.
5. Check `docker compose ps` and logs, then load the HTTPS hostname. Sign in through the normal email/password form; production has no demo role entry.
6. Configure organization, users, items, partners, work centers and BOMs. A second authorized person is required for document approval. Starting inventory enters through approved purchases and incoming QC, rather than an unvalidated direct balance import.
7. Run the workflow acceptance cases, reconciliation and recovery drill before loading real operations.
8. Move bootstrap credentials into your approved custody process. Rotation, recovery and MFA require completion before privileged commercial deployment.

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
