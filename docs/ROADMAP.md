# Durable roadmap

Status language: **verified** means executed evidence exists; **implemented** means source exists with remaining validation; **planned** means not available. This release is a foundation plus a supported end-to-end slice, not all of Phase 1.

| Milestone | State | Acceptance still required |
|---|---|---|
| Phase 0 architecture, schema, security boundaries, API and local runtime | Implemented | External architecture/security review, target environment sign-off |
| Phase 1 first manufacturing lifecycle | Implemented, integration-tested | Pilot operator acceptance and reconciliation on customer scenarios |
| Phase 1 wider commercial core | Implemented, integration-tested (V3–V9, November 2026) | Pilot acceptance with real operators and data; remaining gaps: supplier invoices in purchase units, credit notes/cash receipts, warehouse-level permissions, finite capacity (see `docs/VERIFICATION.md`) |
| Phase 2 advanced factory operations | Planned | Versioned routing/skills/shifts, finite-capacity scheduling, maintenance workflows, full QC/rework/CAPA, genealogy explorer, validated utilization/OEE |
| Phase 3 offline commercial deployment | Planned | Hardened local Postgres installation, local MFA/credential lifecycle, durable browser queue, single-writer partition sync, inbox deduplication, checkpoints/conflict review, licensing grace, signed Windows installer, proven restore/upgrade drills |
| Phase 4 finance/industry/SaaS | Planned | Full chart of accounts/period admin/AP/AR/payments/taxes/statutory reports, country tax adapters with effective versions, customer provisioning, subscription entitlements, isolated support console, industry packages |
| Phase 5 enterprise/AI | Planned | Validated forecasting with provenance, tenant-scoped analytical services, approved telemetry/integration adapters, internationalization |

## Production release blockers

- [ ] Production PostgreSQL version and Docker images validated on the target OS; full dependency vulnerability review and update policy.
- [ ] MFA for privileged users, email-based self-service password recovery and centralized rate limiting. (Done in V8: administrator password reset with forced change, self-service change, account deactivation and session revocation.)
- [ ] Configurable company/plant/warehouse permissions with denial tests at every read/write boundary; database RLS or documented equivalent defense.
- [ ] Full role/field access policy; current operational read model includes costs for tenant members.
- [ ] Server cursor pagination, background reports/exports, query plans and load evidence on realistic ledger volume.
- [ ] Multi-line procurement/sales, unit conversion and stock variance controls validated with actual manufacturing operators.
- [ ] Quality rework/recall/authorized disposition and warehouse reconciliation procedures. (V12: authorized disposition, rework and CAPA; V14: genealogy explorer and recalls with containment, customer notification and close rules — implemented and integration-tested; operator validation and recall drills remain.)
- [ ] Configurable taxes, invoicing/financial controls and localization reviewed with qualified accounting/privacy professionals; no certification claimed.
- [ ] Encrypted backup/restore drill with agreed RPO/RTO, recovery runbook, retention/key custody and offsite copies.
- [ ] Accessibility assessment including contrast, keyboard and screen readers; comprehensive automated browser regression suite.
- [ ] Installer compatibility, signatures, permissions, firewall rules, encrypted storage and service update rollback validated.
- [ ] Security testing, operational monitoring, alerting and customer-authorized diagnostics.
- [ ] Distributed/offline failure cases and inter-factory transfer custody proven before offline/cloud marketing claims.

## Performance and reliability test plan

Agree benchmark hardware before claiming targets: proposed baseline 4 vCPU, 8 GB RAM application host, dedicated 4 vCPU/16 GB RAM PostgreSQL with SSD, one region/LAN, realistic 1M-row stock ledger and 100k manufacturing orders. Test 20/50/100/200 active sessions, mixed reads and 10/25/50 concurrent posting workers. Measure p50/p95/p99, lock wait, errors, DB CPU and reconciliation after concurrent reservations/completions. Target indexed typical list server processing below one second where feasible; do not treat this as a measured guarantee. The broad per-tenant write lock is an expected bottleneck to measure and replace when justified.

Inject API retries, process termination before/after commit, database restart, migration error, low disk space and backup restoration. Report exact hardware/data and accepted errors. Proposed pilot RPO/RTO must be negotiated and proven by a timed restore; no uptime/recovery guarantee exists yet.
