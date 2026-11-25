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
- Responsive dashboard, light/dark themes, keyboard search (`⌘/Ctrl+K`), favorites, attention center, table search, sorting and pagination, CSV export. Manufacturing orders, Shop floor, Quality control and Costs & reports (cost register with tenant-wide cost totals, posted journal with account/text/date filters) are server-paged.
- Session authentication, bcrypt passwords, CSRF protection, rate-limited login and password changes (5 wrong current passwords per account in 15 minutes), backend action permissions, tenant-scoped lookups and composite foreign keys. Sessions are revalidated on every request: deactivating a user or resetting/changing a password signs out existing sessions, and a role change applies on the user's next request.
- Organization configuration, team account creation, product master, explicit base units, work-center registration.
- User administration (**Administration → Users**): server-paged directory with search/role/status filters and last sign-in, name/role edits, deactivate/reactivate, one-time temporary passwords with a forced change screen, and protection of the last active administrator. Everyone can change their own password from the profile menu; new accounts choose their own password at first sign-in.
- Approval policies per document type (required, amount threshold, approver roles; nobody approves their own document) for stock adjustments and count variances, manufacturing orders (estimated cost) and purchase orders (order total). Defaults are identical to before; a document the policy does not require approval for is auto-approved by policy (audited), and approve buttons appear only for the policy's approver roles.
- CSV imports of items, customers/suppliers and opening stock with downloadable templates, a dry run that reports row-level errors and previews what will be created, and a one-transaction commit; re-uploading the same file creates nothing. Opening stock posts lots, ledger receipts and balanced journals against opening equity.
- Server-paged audit trail with actor/action/text/date filters and an audited CSV export; accounting-period close and reopen (with an audited reason) on top of the existing posting guard.
- Customer and supplier management: contacts, billing/shipping addresses, tax ID, payment terms, customer credit limits, audited edits and deactivation (inactive partners cannot be used on new sales or purchase orders). Server-paged directories with search.
- Multi-line sales orders in sales units (a box of 50 bottles, converted exactly to base units; dispatch stays in base units): draft (editable header and lines with discounts and totals) → confirmation (credit-limit warning) → per-line partial/full dispatch → delivered; cancellation before delivery and close-short with a reason. Server-paged order list and a detail drawer with deliveries, returns, linked production and activity.
- Customer returns against a delivery: quarantine lot at the delivered cost, compensating revenue/receivable and COGS journals, and a `RETURN` inspection that Quality control passes (restock) or fails (held).
- Quality management (V12): inspection plans per item or category with measured and pass/fail characteristics (critical ones out of specification fail the inspection; others need a deviation note), nonconformance reports raised by failed inspections or by hand (holding released lots), partial dispositions — concession with independent approval, scrap to quality loss, return to supplier through the supplier-return document, rework orders whose output lot is re-inspected and carries input + rework cost — and CAPA with root-cause analysis, owned actions with overdue flags and independent effectiveness verification.
- Item master: server-paged item list with type/category/status filters and barcode search; item editing with audited changes (a new standard cost never rewrites posted values); deactivate/reactivate (inactive items are blocked on new orders and BOMs but stay in history); SKU, base unit and type locked once an item has stock or document references (also enforced by the database).
- Tenant units of measure (kg, g, t, pcs, box, bag, roll, pallet, drum, l, ml, m, cm, mm, m²) plus custom units, item-specific conversions (1 bag = 25 kg, 1 box = 50 pcs) and one backend conversion service (`UnitService.toBase`, `GET /api/items/{id}/convert`) with half-up rounding at the base unit's precision; stock stays in base units.
- Item categories (hierarchy), multiple barcodes per item (unique per organization, optionally scanning as an alternate unit), variant families that generate real stockable variant items, and `SEMI_FINISHED` intermediates that are produced into the raw (production input) store and consumed like materials.
- Multi-level BOMs: authoring, draft/approval, immutable approved revisions and order-specific material snapshots; a semi-finished component with its own approved BOM is the next level. Approval rejects cycles at any depth and semi-finished components without an approved BOM (bottom-up approval). Indented explosion for any quantity with expand/collapse per level, multi-level where-used, copy an approved revision into an editable draft, revision comparison (added/removed/changed components with quantity and cost deltas) and a standard-cost roll-up through all levels on every BOM card.
- Parent/child manufacturing orders: child orders for the semi-finished shortages of a draft or approved order (quantity = shortage, due the component's lead time before the parent), shown as a parent ↔ children tree in the order drawer; the child's released `SF-…` lot satisfies the parent's normal reservation.
- Multi-line purchase orders in purchase units (bags, rolls, grams… converted exactly to the base unit): draft (editable, supplier optional) → submit → independent approval by a different planner/administrator → partial/full receipt; cancel while nothing is received and close short with a reason. Inactive suppliers and items are refused with a clear message and hidden from pickers (as are inactive customers, products and warehouses in every document dialog).
- Goods receipts of one or more lines under a delivery note into an active raw-material warehouse; each line becomes a quarantined lot with an incoming inspection; a failed inspection returns the lot and reopens the order line.
- Supplier invoices matched line by line to released receipts with quantity and price variances (price variance posted to `PURCHASE_PRICE_VARIANCE`, payables equal the supplier's bill), and supplier returns of released stock with a debit-note reference and compensating clearing/payables journals. Server-paged order list, receipt/invoice/return registers and a purchase-order drawer with lifecycle, receipts, QC status, invoices, returns and activity.
- Multi-level material planning (MRP): unreserved order demand explodes level by level (low-level codes) and is netted against released stock, stock in quality inspection, open/child manufacturing orders, safety stock and incoming purchases; planned production (start = need date − lead time) and purchase recommendations (order date = need date − lead time), pegged to the driving orders, with **Create MO** and **Create purchase** actions.
- Manufacturing approval → reservation → issue to WIP → release → start/pause/resume → partial/final output → final quality release → completion → close.
- Lot-specific finished-goods dispatch per sales-order line (one or more released lots per dispatch) with quality gates and partial delivery support. Each delivery posts sales revenue, receivables and cost of goods sold.
- FIFO material allocation, atomic reservations, immutable quantity/value ledger, nonnegative balance constraints, idempotent mutations and transactional audit/outbox records.
- Inventory operations: warehouse management (code, kind, active flag; stocked warehouses cannot be deactivated), multi-line stock adjustments with reason codes and found-stock lots, an approval policy (default: above Rs. 50,000) under which a different person must approve, and balanced inventory/adjustment journals.
- Warehouse transfers that preserve item, quality status, unit cost and FIFO age (a quarantined lot stays quarantined and follows its original inspection), with source-lot traceability and no reserved stock moved.
- Cycle counts (whole warehouse, one item, an item type and/or an item category with its sub-categories): per-lot snapshot, blind counting on a touch-friendly sheet, variance review against the quantity at posting time (with moved-stock warnings) and posting through the same adjustment approval rule; one open count per lot.
- Decimal-safe costing. Raw material + conversion cost reconciles to remaining WIP + finished output + scrap expense. Partial lot depletion preserves monetary rounding residuals.

## Try the complete workflow

1. Create a customer and a multi-line sales order under **Sales orders**, then confirm it.
2. In the order drawer, **Plan** a line: the manufacturing order takes the line's product (approved BOM) and unplanned quantity. Assign a work center.
3. Approve as a **different** administrator/planner. In the demo, switch users from the avatar menu. Self-approval is blocked.
4. In the order drawer, reserve materials, issue and release, then start production. Resolve raw-material shortages through **Purchasing → From shortages** (or **Material planning → Create purchase**), approve the order as a different user, **Receive goods**, then pass the lot in **Quality control**; for a semi-finished material use **Create child orders for shortages** in the order drawer (or **Create MO** in Material planning).
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

Production uses an external database and disables the demo login. First startup requires `BOOTSTRAP_EMAIL` and a unique `BOOTSTRAP_PASSWORD` of at least 16 characters. Create a second authorized user for independent approvals. Production is initially empty: configure the organization, then create items, customers/suppliers and opening stock by hand or with **Administration → Imports**, register work centers and BOMs, and procure and quality-release further materials.

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
