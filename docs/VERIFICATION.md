# Verification record

## Advanced factory operations (V10–V16) — 27 November 2026

Environment: macOS 26.7, Node 24.14.0, Maven 3.9.11 with OpenJDK 21.0.11, embedded PostgreSQL (zonky
`embedded-postgres` 2.1.0) for tests. Run on `main` after the V16 merge (`a05e4c0`), from a clean `npm ci`.

| Check | Result |
|---|---|
| `npm run typecheck` | passed |
| `npm run build` | passed; largest chunk `charts` 414 kB, every chunk below 500 kB before compression |
| `npm run format:check -w apps/erp-web` | passed |
| Frontend unit tests (vitest) | 30 / 0 failures (6 files) |
| `mvn spotless:check` | passed |
| `npm run test:api` (first run) | 303 tests, **1 failure**: `PlanningDemoSeedTest` found 23 demo manufacturing orders after its rollback |
| `npm run test:api` (after the fix below) | **303 / 0 failures**, 3 min 37 s, in the same class order that failed |

Cause of the failure: `SalesDemoSeedTest` (V3) committed the `demo` tenant to the shared test database and left it
there, while every later demo seed test rolls back and expects no demo tenant afterwards. On macOS the sales test runs
just before the planning test; the earlier Windows runs evidently ran them the other way round. The failing test
passed on its own. Fix (test code only): `SalesDemoSeedTest` now removes the `demo` tenant's rows from every table
after it runs, inside one transaction with `session_replication_role = replica` so append-only triggers and foreign
keys do not block the cleanup. No production code changed.

Not verified in this round: the Phase 2 browser walkthroughs in `docs/workflows/ACCEPTANCE.md` (V10–V16 sections),
phone-width layouts of the Phase 2 pages, Docker deployment, backup/restore drills and load targets.

## Commercial core (V3–V9) — 21–25 November 2026

Environment: Windows 11, Node 24.11.1, Maven 3.9.10 with JDK 21, embedded PostgreSQL 14 (zonky) for demo and tests, Brave with the Claude browser extension for visual checks. Work was built by parallel agents in git worktrees and merged in migration order; each merge was followed by the full suite.

| Step | Commit | Backend tests | Notes |
|---|---|---|---|
| Baseline as received (Windows) | `831ebe9` | 21 / 0 failures | macOS `.local/postgres` incompatible (posix shm, `C.UTF-8`); fresh demo DB seeded |
| Frontend + backend restructure (pure refactors) | `b8dadea`, `9870f97` | 21 / 0 | ~110-call API behaviour dump identical before/after |
| V3 sales, V4 item master, V5 inventory | `214fccc`, `1a6aa02`, `4d823dc` | 83 / 0 | V3–V5 migrated the existing demo DB in place |
| V6 procurement, V7 multi-level BOM/MRP, V8 administration | `d799e5e`, `1f20f1b`, `5afee50` | 151 / 0 | MRP incoming supply switched to purchase-order lines at merge |
| V9 integration + frontend polish | `ae80aa8`, `81d6d79` | 174 / 0 | + 8 frontend unit tests (vitest); entry chunk 600 kB → 182 kB, no chunk warning |

Every merge also passed `npm run typecheck`, `npm run build`, `npm run format:check -w apps/erp-web` and `mvn spotless:check`, and the demo API started with all migrations applied and no warnings.

Browser checks (demo workspace, admin persona unless noted; no console errors on any page):

- Sales: multi-line order drawer (lifecycle, per-line dispatch/plan, deliveries with return, linked production, close short); PO-ICP-7790 line shows "40 box × Rs. 1,375 (2,000 pcs)".
- Item master: barcode `14790001000019` opens FG-001 with "1 box = 50 pcs"; semi-finished preform, variant family and categories present.
- Inventory: approved and posted the Rs. 72,750 damage adjustment (created by the warehouse persona) — balanced journal Dr inventory adjustment / Cr raw inventory.
- Purchasing: multi-line order (HDPE 120 bag = 3,000 kg, receipts with QC status, supplier return, match invoice / receive goods).
- BOMs and planning: FG-101 two-level explosion for 1,000 units (1,011 preforms → 24.7695 kg PET resin, Rs. 18,032.64); MRP shows level-1 planned production for SF-001 with pegging and Create MO.
- Administration: users with roles and last sign-in; server-paged audit trail with filters and CSV export.
- Polish: Overview chart marks today as a dashed partial segment; MO drawer shows "WIP cost" and readable activity ("Materials reserved → Released", "WIP 221,301.74"); drawer tables fit at desktop width.

Not verified in this round: phone-width layout of the V9 card tables (the browser window could not be resized), screen-reader behaviour, Docker deployment, backup/restore drills, load/performance targets. Known limitations are listed in each feature's section of `docs/FEATURES.md` and in the agent merge notes.

## Interface motion — 30 October 2026

Executed against the local React workspace and the running PostgreSQL-backed API.

| Check | Result |
|---|---|
| Clean dependency installation | `npm ci --offline --cache .local/npm-cache --include=dev --no-audit --no-fund` passed |
| TypeScript | `npm run typecheck` passed after the final source changes |
| Production web build | `npm run build` passed; app, chart and motion chunks are each below 500 kB before compression |
| Navigation/page transitions | Opened manufacturing, BOM, administration and overview through the application |
| Manufacturing filters | Switching to Draft showed 2 matching records; switching to Active showed 6, with a moving selection indicator |
| Modal entry and keyboard focus | New manufacturing-order dialog focused its product selector; Escape dismissed it through the animated exit |
| Command palette | Opened with focus on the search field; Escape dismissed it |
| Production drawer and nested dialog | Opened `MO-2026-2231E0`, then its output form; the form focused quantity entry |
| Nested labels and focus restoration | Two open dialogs had distinct heading IDs; cancelling output left the drawer open and restored focus to “Record output” |
| BOM component transitions | Added a second material row, removed it and cancelled the draft editor |
| Reduced-motion preference | Selected Reduce motion, reloaded, and confirmed the saved choice and “Reduced motion is active” state |
| Restoring standard motion | Selected Follow device preference; the page reported `data-motion="full"`; the three-stage preview replayed |
| Mobile layout | At 390 × 844, navigation opened, a route selection closed it, the hidden sidebar was inert, and document width equalled the 390 px viewport |
| Browser console | No error entries captured at the end of these checks |

Screenshots: `docs/screenshots/motion-settings-desktop.jpg` and `docs/screenshots/motion-mobile.jpg`. Temporary viewport override was reset after mobile checks. The demo returns to device-controlled motion, not a forced reduced-motion preference.

Scope limits: the device-level reduced-motion branch is implemented but the operating-system preference was not changed during testing. No formal FPS, cross-browser, screen-reader certification or accessibility audit was performed. Current browser checks exercise presentation and cancellation; no new production completion or inventory posting was needed for this interface update.

## Manufacturing foundation baseline — 29 October 2026

The prior implementation ran **21 PostgreSQL integration tests, 0 failures, 0 errors**, including concurrency-safe reservations, idempotent completion, partial WIP reconciliation, immutable BOM approval, exact ratio rounding, cross-tenant denial, restricted self-approval, held-lot dispatch denial, incoming rejection reversal, closed-period rollback, account creation/login, and transactional migration failure rollback. These tests were not rerun for the presentation-only motion update; the backend was unchanged.

The prior browser workflow created `MO-2026-F10B71` for 1,000 units, independently approved it, reserved/issued materials, recorded 400 then 600 good units, passed both final inspections, closed the order and delivered all 1,000 units on `SO-2026-7EA8C4` using two delivery references. After the first posting, WIP was Rs. 10,146 and finished output value Rs. 6,764; after final posting, WIP was zero and cumulative finished output value Rs. 17,910. The final sales row showed 1,000/1,000 delivered.

The backend JAR exists at `services/erp-api/target/erp-api-0.1.0.jar`. Docker deployment, recovery drills, cloud synchronization and commercial Windows installation remain unverified or planned as recorded in the roadmap. Artifact existence is not a production-readiness claim.
