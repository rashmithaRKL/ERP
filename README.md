# DexLanka Manufacturing Pro

A React/TypeScript application and Java 21 / Spring Boot modular monolith, backed by PostgreSQL and versioned Flyway migrations. This repository implements the **foundation, the Phase 1 commercial core and the Phase 2 advanced factory operations** (routings and shifts, maintenance, quality management, finite-capacity scheduling, genealogy and recalls, OEE), not the entire commercial product described in the master brief.

## Run locally

Prerequisites: Node.js 22.12+ (Node 24 used for verification), npm, Java 21, and internet access for the first dependency download. macOS/Linux demo uses a checksum-verified Maven bootstrap and embedded **real PostgreSQL**, with data persisted under `.local/postgres`. Windows development requires Maven on PATH; the commercial Windows installer is not implemented.

```sh
npm ci
npm start
```

Open **http://127.0.0.1:5173**. The API runs at `127.0.0.1:8080`. The explicit `demo` profile opens a clearly labelled sample workspace with demo-only role switching. Its database uses local trust authentication and must remain loopback-only. Never deploy that profile to a customer environment.

Separate terminals are also supported:

```sh
npm run api
npm run dev
```

`Ctrl+C` stops the owned processes. Demo records survive restarts. For a fresh demonstration, stop both processes and move `.local/postgres` to a different directory before restarting. Do not remove operational customer data using this procedure.

## What works

The release covers three layers: the platform, the Phase 1 commercial core (V1–V9) and the Phase 2 advanced factory
operations (V10–V15), made to work together by the V16 integration pass. Everything below runs against the real
database with tests; limits are listed in [the feature register](docs/FEATURES.md) and [the roadmap](docs/ROADMAP.md).

### Platform and administration

- Responsive workspace, light/dark themes, keyboard search (`⌘/Ctrl+K`), favorites, table search/sort/paging and CSV export; shared spring motion with saved reduced-motion preferences ([motion guide](docs/architecture/MOTION.md)). Tabbed pages follow the URL (`?tab=`), so links into a tab and the browser history work.
- One attention model: the Overview "Needs your attention" panel, the attention center and the header bell show the same counts and items (shortages, overdue orders, approvals, quality alerts, open breakdowns and overdue preventive work, late and stale schedules, recalls), each linking only to pages the user's role can open.
- Session authentication, bcrypt passwords, CSRF protection, rate-limited login and password changes, per-request session revalidation (deactivation or a password reset signs out existing sessions; role changes apply on the next request), two-step sign-in with an authenticator app (V17: TOTP codes, secrets encrypted at rest, ten one-time recovery codes; required for administrators of a real workspace, optional for everyone else, the demo exempt), backend action permissions, tenant-scoped lookups and composite foreign keys. Eight roles (ADMIN, PLANNER, WAREHOUSE, OPERATOR, QUALITY, SALES, VIEWER, MAINTENANCE) with a tested page-by-role access matrix.
- Administration: organization settings; a server-paged user directory (edit, deactivate/reactivate, one-time temporary passwords with a forced change, a Two-step column and a reset of another user's two-step sign-in for a lost phone, last-administrator protection; everyone changes their own password and manages their own two-step sign-in from the profile menu); approval policies per document type (stock adjustments and counts, manufacturing orders, purchase orders; nobody approves their own document); CSV imports of items, customers/suppliers and opening stock with templates, dry run and one-transaction commit; a server-paged audit trail with an audited CSV export; accounting-period close and reopen.

### Commercial core (Phase 1, V1–V9)

- Item master: server-paged items with barcode search, audited editing, deactivation, SKU/unit/type locked once used; tenant units with exact item conversions (`UnitService.toBase`); categories, barcodes, variant families and `SEMI_FINISHED` intermediates.
- Customers and suppliers with contacts, terms, credit limits and deactivation. Multi-line sales orders in sales units (draft → confirm → per-line dispatch from released lots → delivered; close short), customer returns with compensating journals and a `RETURN` inspection.
- Multi-line purchase orders in purchase units (draft → submit → independent approval → partial/full receipt; close short), goods receipts into quarantined lots with incoming inspection, supplier invoices with quantity/price variances, supplier returns with debit notes.
- Inventory: lots with quarantine/released status, an immutable quantity/value ledger, FIFO allocation and atomic reservations; warehouses, stock adjustments with threshold approval, transfers that keep quality status, cost and FIFO age, and blind cycle counts.
- Multi-level BOMs (immutable approved revisions, explosion, where-used, compare, cost roll-up) and multi-level MRP (low-level netting, planned production and purchase recommendations pegged to their demand, **Create MO** / **Create purchase**); parent/child manufacturing orders.
- Manufacturing orders: approval → reservation → issue to WIP → start/pause/resume → partial/final output → final quality release → close, with decimal-safe actual costing (materials + conversion reconcile to WIP, output and scrap), balanced journals and server-paged cost register and journal.

### Advanced factory operations (Phase 2, V10–V16)

- **Routings & shifts** (V10): versioned routings (operations, alternate work centers, setup/run minutes, crew, skills, inspection points), approved by a second planner and immutable; shift calendars with breaks, overnight shifts, holidays, overtime and per-work-center assignment behind one working-time service; a skill matrix with certification expiry.
- **Shop floor** (V10): orders snapshot their routing into operations; operators pick a work center and start (setup or run), pause, resume, report and finish operations in sequence with time logs and skill checks; cards read "Step 2 of 2 · Op 20"; orders without a routing keep their order cards and count as one implicit operation in the work-center chips.
- **Work centers and maintenance** (V11): a work center is RUNNING while an operation or an order without a routing runs on it, IDLE otherwise, and MAINTENANCE only through a maintenance work order; every change (production, maintenance, planner correction) is in the machine's history. Downtime with stable reason codes (never overlapping, append-only corrections); corrective requests with independent approval, preventive plans with idempotent generation, checklists, journalized labour and FIFO spare parts; a reason Pareto, MTTR and MTBF on shift working time less downtime.
- **Quality management** (V12): inspection plans with measured and pass/fail characteristics; nonconformance reports from failed inspections or by hand; partial dispositions (concession with independent approval, scrap, return to supplier, rework into a re-inspected lot carrying its cost); CAPA with root cause, owned actions and independent effectiveness checks.
- **Production schedule** (V13): forward finite-capacity scheduling inside shift calendars, maintenance windows and downtime, with alternates, precedence, pins (also by dragging a Gantt bar), draft/publish/compare, late orders, capacity heatmap, next operations on the Shop floor and projected completion in the order drawer; the page, the `schedule` state key and the planners' attention items say when orders, operations, maintenance, downtime or calendars changed since the published run (V16).
- **Traceability & recalls** (V14): a genealogy explorer (graph or tree) from any lot, order, receipt, delivery or supplier, backward to suppliers and forward to customers through every level, transfer, rework and return; recalls that compute affected stock and shipments, contain them through RECALL nonconformances, track customer notifications and close only when everything is notified and dispositioned (or by an independent override).
- **Performance (OEE)** (V15): OEE per work center and business day from records only (shift time, downtime, operation time logs and reports), never a fabricated 0 %; time-weighted roll-ups, loss waterfall, heatmap, six big losses, utilization, data-coverage warnings and closed days that later calendar edits never rewrite.

## Try the complete workflow

1. Create a customer and a multi-line sales order under **Sales orders**, then confirm it.
2. In the order drawer, **Plan** a line: the manufacturing order takes the line's product (approved BOM) and unplanned quantity. Assign a work center.
3. Approve as a **different** administrator/planner. In the demo, switch users from the avatar menu. Self-approval is blocked.
4. In the order drawer, reserve materials, issue and release, then start production. Resolve raw-material shortages through **Purchasing → From shortages** (or **Material planning → Create purchase**), approve the order as a different user, **Receive goods**, then pass the lot in **Quality control**; for a semi-finished material use **Create child orders for shortages** in the order drawer (or **Create MO** in Material planning).
5. Record output on **Shop floor**; partial output retains unfinished WIP cost. Output lots remain quarantined. For a product with an approved routing (**Routings & shifts**), first run its operations on the Shop floor (pick the work center, start, report, finish); output follows the last operation.
6. Pass each final inspection in **Quality control**.
7. Dispatch released goods per line from the sales order drawer; record a customer return against a delivery if needed (it waits in **Quality control**). Review **Costs & reports**, **Inventory → Ledger** and **Administration → Audit trail**.
8. Close the fully reconciled manufacturing order.

The Phase 2 walkthroughs (routings and the shop floor, maintenance, quality management, the production schedule,
traceability and recalls, OEE) use the demo data and are listed step by step in
[workflow acceptance](docs/workflows/ACCEPTANCE.md).

## Verify

```sh
npm run typecheck
npm run build
npm run test:api
```

Database tests launch an isolated real PostgreSQL process; Docker is not required. Browser QA and verification evidence are recorded in [docs/VERIFICATION.md](docs/VERIFICATION.md). Build output and runtime data are ignored.

## Production deployment

A Docker Compose configuration includes PostgreSQL, the API, a built web app and Caddy HTTPS. It intentionally publishes **only** HTTP/HTTPS ports. Supply unique credentials and a valid DNS hostname in your secret manager or an ignored `.env`; see `.env.example` and [deployment instructions](docs/deployment/OPERATIONS.md). This Docker configuration requires validation on the target host before use; Docker was unavailable in the build environment.

Production uses an external database and disables the demo login. First startup requires `BOOTSTRAP_EMAIL` and a unique `BOOTSTRAP_PASSWORD` of at least 16 characters. The API also requires `MFA_ENCRYPTION_KEY` (the base64 of 32 random bytes, for example `openssl rand -base64 32`) and refuses to start without it; it encrypts the two-step sign-in secrets, so keep it with the database backups: it cannot be rotated, and losing it means every user's two-step sign-in must be reset by an administrator (or, if no administrator can sign in, cleared in the database). At the first sign-in the bootstrap administrator must set up two-step sign-in with an authenticator app before using the workspace, and so must every later administrator (`MFA_REQUIRED_FOR_ADMINS`, default `true`). Create a second authorized user for independent approvals, and a second administrator who can reset a lost phone. Production is initially empty: configure the organization, then create items, customers/suppliers and opening stock by hand or with **Administration → Imports**, register work centers and BOMs, and procure and quality-release further materials.

## Project map

```text
apps/erp-web/                 React + TypeScript + Vite UI
services/erp-api/             Spring Boot API, domain services, migrations, DB tests
infrastructure/docker/       API/web images, reverse proxy, HTTPS gateway
scripts/                     Local development, encrypted backup, isolated recovery
.github/workflows/           CI checks
.openapi/                    Machine-readable API contract
docs/                       Architecture, ERD, workflow acceptance, readiness, roadmap
```

See [architecture](docs/architecture/ARCHITECTURE.md), [database model](docs/database/MODEL.md), [API contract](docs/api/API.md), [feature register](docs/FEATURES.md), and [roadmap](docs/ROADMAP.md). The production-readiness checklist explicitly tracks email self-service recovery, granular plant/warehouse permissions, full accounting, multi-factory operations, offline synchronization, licensing, Windows installation, security assessment, backup drills and load testing. No claim of regulatory compliance or commercial readiness is made.
