# DexLanka Manufacturing Pro

A React/TypeScript application and Java 21 / Spring Boot modular monolith, backed by PostgreSQL and versioned Flyway migrations. This repository implements a **foundation release and the first manufacturing lifecycle**, not the entire commercial product described in the master brief.

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

- Shared Figma-style motion: spring navigation, page/card reveals, filter indicators, dialog/drawer exits, progress and BOM row transitions, plus saved reduced-motion preferences. See [the motion guide](docs/architecture/MOTION.md).
- Responsive dashboard, light/dark themes, keyboard search (`⌘/Ctrl+K`), favorites, attention center, table search, sorting and pagination, CSV export.
- Session authentication, bcrypt passwords, CSRF protection, rate-limited login, backend action permissions, tenant-scoped lookups and composite foreign keys.
- Organization configuration, team account creation, product master, explicit base units, work-center registration.
- Customer and supplier management: contacts, billing/shipping addresses, tax ID, payment terms, customer credit limits, audited edits and deactivation (inactive partners cannot be used on new sales or purchase orders). Server-paged directories with search.
- Multi-line sales orders: draft (editable header and lines with discounts and totals) → confirmation (credit-limit warning) → per-line partial/full dispatch → delivered; cancellation before delivery and close-short with a reason. Server-paged order list and a detail drawer with deliveries, returns, linked production and activity.
- Customer returns against a delivery: quarantine lot at the delivered cost, compensating revenue/receivable and COGS journals, and a `RETURN` inspection that Quality control passes (restock) or fails (held).
- Single-level BOM authoring, draft/approval, immutable approved revisions and order-specific material snapshots.
- Single-item purchase orders, independent purchase approval, partial receipt, quarantined lots, incoming quality decision, rejected receipt reversal and receipt/PO invoice matching.
- Basic material planning against unreserved demand, available stock, approved incoming supply and safety stock.
- Manufacturing approval → reservation → issue to WIP → release → start/pause/resume → partial/final output → final quality release → completion → close.
- Lot-specific finished-goods dispatch per sales-order line (one or more released lots per dispatch) with quality gates and partial delivery support. Each delivery posts sales revenue, receivables and cost of goods sold.
- FIFO material allocation, atomic reservations, immutable quantity/value ledger, nonnegative balance constraints, idempotent mutations and transactional audit/outbox records.
- Decimal-safe costing. Raw material + conversion cost reconciles to remaining WIP + finished output + scrap expense. Partial lot depletion preserves monetary rounding residuals.

## Try the complete workflow

1. Create a customer and a multi-line sales order under **Sales orders**, then confirm it.
2. In the order drawer, **Plan** a line: the manufacturing order takes the line's product (approved BOM) and unplanned quantity. Assign a work center.
3. Approve as a **different** administrator/planner. In the demo, switch users from the avatar menu. Self-approval is blocked.
4. In the order drawer, reserve materials, issue and release, then start production. Resolve shortages through **Material planning → Purchasing → Incoming QC**.
5. Record output on **Shop floor**; partial output retains unfinished WIP cost. Output lots remain quarantined.
6. Pass each final inspection in **Quality control**.
7. Dispatch released goods per line from the sales order drawer; record a customer return against a delivery if needed (it waits in **Quality control**). Review **Costs & reports**, **Inventory → Ledger** and **Administration → Audit trail**.
8. Close the fully reconciled manufacturing order.

## Verify

```sh
npm run typecheck
npm run build
npm run test:api
```

Database tests launch an isolated real PostgreSQL process; Docker is not required. Browser QA and verification evidence are recorded in [docs/VERIFICATION.md](docs/VERIFICATION.md). Build output and runtime data are ignored.

## Production deployment

A Docker Compose configuration includes PostgreSQL, the API, a built web app and Caddy HTTPS. It intentionally publishes **only** HTTP/HTTPS ports. Supply unique credentials and a valid DNS hostname in your secret manager or an ignored `.env`; see `.env.example` and [deployment instructions](docs/deployment/OPERATIONS.md). This Docker configuration requires validation on the target host before use; Docker was unavailable in the build environment.

Production uses an external database and disables the demo login. First startup requires `BOOTSTRAP_EMAIL` and a unique `BOOTSTRAP_PASSWORD` of at least 16 characters. Create a second authorized user for independent approvals. Production is initially empty: configure the organization, create items, customers/suppliers, work centers and BOMs, then procure and quality-release starting materials.

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

See [architecture](docs/architecture/ARCHITECTURE.md), [database model](docs/database/MODEL.md), [API contract](docs/api/API.md), [feature register](docs/FEATURES.md), and [roadmap](docs/ROADMAP.md). The production-readiness checklist explicitly tracks MFA, granular plant/warehouse permissions, full accounting, multi-factory operations, offline synchronization, licensing, Windows installation, security assessment, backup drills and load testing. No claim of regulatory compliance or commercial readiness is made.
