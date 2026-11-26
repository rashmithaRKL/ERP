# erp-api — backend developer guide

Java 21 / Spring Boot 3.5 modular monolith on PostgreSQL (Flyway). One deployable, split into domain packages so that
features can be built in parallel without editing the same files. Architecture decisions: [ARCHITECTURE.md](../../docs/architecture/ARCHITECTURE.md);
data model: [MODEL.md](../../docs/database/MODEL.md); HTTP contract: [API.md](../../docs/api/API.md).

## Everyday commands

Run from the repository root. `scripts/maven.mjs` runs `./mvnw` on macOS/Linux/CI and `mvn` from `PATH` on Windows.

| Task | Command |
|---|---|
| All backend tests (real embedded PostgreSQL, no Docker) | `npm run test:api` |
| One test class | `node scripts/maven.mjs -f services/erp-api/pom.xml test -Dtest=MyFeatureTest` |
| Format the code (palantir-java-format via Spotless) | `node scripts/maven.mjs -f services/erp-api/pom.xml spotless:apply` |
| Check formatting (CI runs this) | `node scripts/maven.mjs -f services/erp-api/pom.xml spotless:check` |
| Demo API on 127.0.0.1:8080 | `npm run api` |

Spotless is not bound to the build lifecycle: `test`/`package` never reformat or fail on style, but CI fails on
unformatted code, so run `spotless:apply` before committing. On a low-memory machine set `MAVEN_OPTS=-Xmx512m` and run
one Maven command at a time. Embedded-Postgres `ERROR:` lines in test output are expected (negative-path tests); judge a
run by `target/surefire-reports/*.xml`.

## Package layout

| Package | Owns |
|---|---|
| `lk.dexlanka.erp` | `ErpApplication` only (component scan root) |
| `platform` | `Store` (posting primitives), `Actor` (identity + role checks), `ErpUser` (principal with its session version), `SecurityConfig`, `LoginRateLimit`, `SessionGuard` (per-request revocation, role refresh, forced password change), `Passwords` (the password rule, temporary passwords), `PasswordService` + `AccountCommands` (self-service change), `PasswordAttemptLimit` (V9: 5 wrong current passwords per account in 15 minutes, then 429), `AuthController` (`/api/health`, `/api/auth/*`), `ApiErrors` (the single `@RestControllerAdvice`) |
| `catalog` | Organization, user accounts, partners (maintenance and directory in `Partner*` files), items, work centers (machines), BOM revisions; item master (V4: `ItemService`, `UnitService` conversion service, `CategoryService`, `VariantService`, `ItemQuery`); multi-level BOMs (V7: `BomGraph` structure + roll-up, `BomService` copy/edit-draft + approval rules called from `CatalogService.approveBom`, `BomQuery` explosion/where-used/compare/cost, `BomStateSections`) |
| `procurement` | Multi-line purchase orders in purchase units (`PurchaseOrderService`: draft/submit/independent approval/cancel/close short, drafts from MRP shortages), goods receipts per line (`ReceivingService`), supplier invoices with quantity/price variances (`SupplierInvoiceService`), supplier returns (`SupplierReturnService`), the read model (`ProcurementQuery`), open MRP supply (`ProcurementSupply`), shared rules (`PurchaseRules`) and the original single-item contract (`ProcurementService`) |
| `quality` | Incoming and final inspections (including the supplier return of a failed incoming lot and MO completion after the last final release); the paged inspection list (V9: `InspectionQuery`). V12 quality management: inspection plans, defect codes and measurement evaluation (`InspectionPlanService`, `InspectionPlanQuery`), nonconformance reports and dispositions (`NcrService`, `NcrQuery`), rework orders (`ReworkService`), CAPA (`CapaService`, `CapaQuery`), shared rules (`QualityRules`: lot hold settling, MO completion, accounts) and keyset paging (`QualityPaging`). `NcrService` calls `procurement.SupplierReturnService` (joining its transaction) to post RETURN_TO_SUPPLIER dispositions |
| `sales` | Multi-line sales orders in sales units (V9: entered quantity/unit/factor/base quantity through `catalog.UnitService`; draft/confirm/cancel/close short), per-line dispatch (revenue/receivables and COGS per delivery), customer returns (`ReturnsService`), the sales read model (`SalesQuery`) |
| `manufacturing` | Manufacturing-order lifecycle: snapshot, approval (V9: per the `MANUFACTURING_ORDER` approval policy), reservation, issue, start/pause/resume, output, close/cancel; parent/child orders (V7: `ChildPlanning` shortages, `OrderTreeService` child orders, `OrderTreeQuery` order tree); the paged order list (V9: `ManufacturingQuery`); V10 operations: `OperationPlanner` (routing snapshot at creation and the lifecycle hooks `ManufacturingService` calls: release, pause, cancel, output limits; work-center RUNNING/IDLE), `OperationService` (shop-floor start/pause/resume/setup-done/report/finish, time logs, skill check), `OperationQuery` (order operations, queue per work center, work-center summary, time logs) |
| `routing` | V10 master data and working time: versioned routings (`RoutingService` draft/approve/copy/make-current, `RoutingQuery` list/detail/compare), shift calendars (`ShiftCalendarService` patterns/calendars/exceptions/assignment, `CalendarQuery`, and **`CalendarService`**, the public working-time service: `availableMinutes(tenant, machineId, date)`, `workingIntervals(tenant, machineId, from, to)`, `availability(...)`), skills (`SkillService`, `SkillQuery`, public `SkillRules.check`) |
| `reporting` | `GET /api/state` (`StateQuery` + the `StateContributor` contract), `GET /api/manufacturing/{id}` (`OrderDetailsQuery`), cross-domain sections: inventory (lots, ledger), MRP, dashboard, audit/sync, finance; multi-level MRP (V7: `MrpPlanner` engine, `PlanningStateSections` with the isolated `incomingPurchaseSupply(tenant)` query, `PlanningQuery`); Costs & reports (V9: `ReportQuery` paged cost register and journal, `ReportsController`) |
| `maintenance` | V11 maintenance and downtime: `DowntimeService` (report/close/correct downtime), `WorkOrderService` (corrective request, independent approval, schedule, start/progress/labour/complete, cancel, labour-rate settings), `SparePartService` (FIFO issue and returns of `SPARE_PART` items), `PlanService` (preventive plans, idempotent generation, run-hour meter), `MachineStatusService` (planner status correction), `MaintenanceRules` (shared rules: machine MAINTENANCE/IDLE transitions and their history, downtime insert/close, checklists), `MaintenanceQuery` + `MaintenanceMetrics` (paged read model, MTBF/MTTR/Pareto), `MaintenanceStateSections` |
| `scheduling` | V13 finite-capacity scheduling: `ScheduleEngine` (pure forward scheduler: calendars minus blocks, one operation at a time per work center, splitting, sequence and parent/child precedence, running work, alternates, pins, horizon), `ScheduleInputs` (loads the engine input: `routing.CalendarService` working intervals, maintenance windows and the `machine_downtime` view, open orders/operations with remaining minutes and allowed work centers, estimates, pins), `ScheduleService` (run, publish, discard, pin/unpin validated by scheduling, duration estimates), `ScheduleQuery` (runs, run detail, Gantt, late orders, capacity load, compare, next operations, order projection, pins), `ScheduleStateSections` |
| `traceability` | V14 genealogy and recalls: **`TraceService`**, the trace engine and the one public read service recalls use (`forward`/`backward` lot closures over the `lot_genealogy_links` view with depth-limited, cycle-safe recursive CTEs, `origins`, `supplierLots`, plus the `search` and `trace` graph/tree read endpoints), `RecallService` (open/refresh/contain/notify/close/cancel, `outstanding` close check, audited export), `RecallQuery` (paged list, detail, notification CSV), `RecallStateSections`, `TraceController`, `RecallController` |
| `performance` | V15 OEE and utilization (read model plus closed-day snapshots): `OeeCalculator` (pure per-day calculation: time classification, A/P/Q/OEE, losses, warnings; `Spans` interval arithmetic), `OeeEngine` (loads working time through `routing.CalendarService`, the `machine_downtime` view, operation time logs/reports and production actuals; merges closed snapshots with live figures), `Rollup` (time-weighted roll-ups), `PerformanceQuery` (OEE matrix, losses, day detail, the state key), `PerformanceService` (close / recalculate days), `PerformanceStateSections`, `PerformanceDemoSeed` |
| `demo` | `DemoDatabase` (embedded PostgreSQL for the `demo`/`test` profiles), `DemoSeed` (demo tenant, or first-start bootstrap tenant outside demo), `DemoPersonas` (V11 adds `MAINTENANCE`), `IntegrationDemoSeed` (V9), `RoutingDemoSeed` (V10), `MaintenanceDemoSeed` (V11), `QualityDemoSeed` (V12), `ScheduleDemoSeed` (V13), `TraceabilityDemoSeed` (V14); `performance.PerformanceDemoSeed` (V15) lives in its feature package |
| `admin` | V8 administration (ADMIN only): `UserAdminService` + `UserQuery` (edit, deactivate/reactivate, temporary passwords, directory), `ApprovalPolicyService` (public `evaluate(tenant, documentType, amount, creatorId)` → `Decision`, `approverDecision`, `autoApproval`; used by `inventory` for stock adjustments and, since V9, by `manufacturing` and `procurement` for order approval) + `ApprovalPolicyController` (`GET /api/approval-policies` for every role), `ImportService` with `ImportKind`s `ItemImport`, `PartnerImport`, `OpeningStockImport` and the `CsvFile` reader, `AuditQuery` (paged trail, facets, streamed CSV export), `PeriodService` (close/reopen accounting periods), `AdminController` |

A domain package contains, by convention:

```text
<domain>/
  <Domain>Commands.java        public final holder of public request records (JSON + Bean Validation)
  <Domain>Service.java         public @Service; one public @Transactional method per command
  <Domain>Controller.java      package-private @RestController, @RequestMapping("/api")
  <Domain>StateSections.java   optional package-private @Component implements reporting.StateContributor
  <Domain>DemoSeed.java        optional idempotent demo data, ordered after demo.DemoSeed
```

Dependency direction: domain packages → `platform` and `reporting` (contract only); `reporting` → `platform`;
`demo` → domain services. Domains never call each other's services; cross-domain effects are SQL on the shared,
tenant-scoped schema inside the same transaction (for example, `quality` completes a manufacturing order).
`maintenance` writes `machines.status` (only the transitions into and out of `MAINTENANCE` and planner corrections) and reads `manufacturing_orders` and `mo_operations` (refuses to start maintenance while an order without a routing, or an operation of a routed order, is in progress on the machine) through SQL in its own transactions. Deliberate exceptions: `admin` imports call the public `catalog.ItemService` / `catalog.CatalogService#partner`
commands (joining the import's transaction); `quality.NcrService` posts a RETURN_TO_SUPPLIER disposition through
`procurement.SupplierReturnService#returnToSupplier` (V12, same transaction, warehouse actor); `traceability.RecallService#contain`
holds affected stock by calling the public `quality.NcrService#raise` (V14, `source RECALL`, joining the recall's
transaction with a nested idempotency key per recall and lot; the NCR quarantines the lot — `traceability` never writes
`lots.status`) and reads NCR statuses for its close rule through SQL; and domains that require approvals ask `admin.ApprovalPolicyService`
(non-transactional reads inside the caller's transaction: `inventory` for stock adjustments, `manufacturing` for orders, `procurement` for purchase orders).
V10 adds read-only calls from `manufacturing` to `routing.SkillRules` (operation skill check) and `routing.CalendarService`
(today's available minutes on the shop floor); V13 scheduling and V15 OEE call `CalendarService` the same way. `scheduling` reads `mo_operations`, `mo_operation_time_logs`, `routing_operation_alternates`, `manufacturing_orders`, `maintenance_work_orders` windows and the `machine_downtime` view through SQL and writes only its own tables. `performance` (V15) reads the V10–V12 execution tables and the `machine_downtime` view through SQL only and writes nothing but `oee_day_snapshots` (its demo seed reports downtime through `maintenance.DowntimeService`). The routing
snapshot itself is SQL on the routing tables inside `ManufacturingService`'s transaction.
Make a class or method `public` only when another package (or a test in another package) needs it.

Route ownership:

| Controller | Routes under `/api` |
|---|---|
| `platform.AuthController` | `GET /health`, `GET /auth/config`, `GET /auth/csrf`, `GET /auth/me`, `POST /auth/password`, `POST /auth/demo` (`/auth/login`, `/auth/logout` are Spring Security filters) |
| `admin.ApprovalPolicyController` | `GET /approval-policies` (V9, every role) |
| `admin.AdminController` | `GET /admin/users`, `/admin/approval-policies`, `/admin/imports`, `/admin/imports/{kind}/template`, `/admin/audit`, `/admin/audit/facets`, `/admin/audit/export`, `/admin/periods`; `POST /admin/users/{id}`, `/admin/users/{id}/deactivate`, `/reactivate`, `/reset-password`, `/admin/approval-policies/{documentType}`, `/admin/imports/{kind}/validate`, `/admin/imports/{kind}/commit`, `/admin/periods/close`, `/admin/periods/reopen` |
| `catalog.CatalogController` | `POST /users`, `/organization`, `/partners`, `/machines`, `/items`, `/boms`, `/boms/{id}/approve` |
| `catalog.PartnerController` | `GET /partners`, `GET /partners/{id}`, `POST /partners/{id}` |
| `catalog.ItemMasterController` | `GET /items`, `/items/{id}`, `/items/{id}/movements`, `/items/{id}/units`, `/items/{id}/convert`, `/items/by-barcode`, `/units`, `/item-categories`, `/item-templates`, `/item-templates/{id}`; `POST /items/{id}`, `/items/{id}/deactivate`, `/items/{id}/activate`, `/items/{id}/conversions`, `/items/{id}/conversions/{unit}/remove`, `/items/{id}/barcodes`, `/items/{id}/barcodes/{barcodeId}/remove`, `/units`, `/units/{code}`, `/item-categories`, `/item-categories/{id}`, `/item-templates`, `/item-templates/{id}`, `/item-templates/{id}/variants` |
| `procurement.ProcurementController` | `GET /purchases/orders`, `/purchases/orders/{id}`, `/purchases/receipts`, `/purchases/invoices`, `/purchases/returns`, `/purchases/sources`; `POST /purchases/orders`, `/purchases/orders/{id}`, `/purchases/orders/{id}/submit`, `/approve`, `/cancel`, `/close`, `/receipts`, `/invoices`, `/purchases/receipts/{id}/returns`, `/purchases/recommendations`; original contract `POST /purchases`, `/purchases/{id}/approve`, `/purchases/{id}/receive`, `/receipts/{id}/invoice` |
| `quality.QualityController` | `GET /quality/inspections` (V9); `POST /quality/{id}/inspect` (V12: optional `measurements`) |
| `quality.InspectionPlanController` | `GET /quality/plans`, `/quality/plans/{id}`, `/quality/inspections/{id}/plan`, `/quality/inspections/{id}/measurements`, `/quality/defect-codes`; `POST /quality/plans`, `/quality/plans/{id}`, `/quality/defect-codes`, `/quality/defect-codes/{code}` (V12) |
| `quality.NcrController` | `GET /quality/ncrs`, `/quality/ncrs/{id}`, `/quality/rework`, `/quality/rework/{id}`, `/quality/summary`, `/quality/lots/{id}`, `/quality/people`; `POST /quality/ncrs`, `/quality/ncrs/{id}`, `/quality/ncrs/{id}/dispositions`, `/close`, `/cancel`, `/quality/dispositions/{id}/decision`, `/quality/dispositions/{id}/return`, `/quality/rework/{id}/complete` (V12) |
| `quality.CapaController` | `GET /quality/capas`, `/quality/capas/{id}`, `/quality/capa-actions`; `POST /quality/capas`, `/quality/capas/{id}`, `/quality/capas/{id}/actions`, `/start`, `/submit`, `/verify`, `/cancel`, `/quality/capa-actions/{id}/complete`, `/cancel` (V12) |
| `sales.SalesController` | `GET /sales/orders`, `GET /sales/orders/{id}`; `POST /sales/orders`, `/sales/orders/{id}`, `/sales/orders/{id}/confirm`, `/cancel`, `/close`, `/sales/orders/{id}/lines/{lineId}/dispatch`, `/sales/deliveries/{id}/returns`; original contract `POST /sales`, `/sales/{id}/dispatch` |
| `catalog.BomController` | `GET /boms/{id}/explosion`, `/boms/explosion`, `/boms/where-used`, `/boms/compare`, `/boms/{id}/cost`; `POST /boms/{id}/copy`, `/boms/{id}` (edit draft) |
| `manufacturing.ManufacturingController` | `GET /manufacturing/orders` (V9; V10 adds `routing=none|routed`); `POST /manufacturing`, `/manufacturing/{id}/actions/{action}`, `/manufacturing/{id}/complete` |
| `manufacturing.OperationController` | V10: `GET /manufacturing/{id}/operations`, `/operations/work-centers`, `/operations/queue`, `/operations/time-logs`, `/operations/{id}`; `POST /operations/{id}/start`, `/pause`, `/resume`, `/setup-done`, `/report`, `/finish` |
| `routing.RoutingController` | V10: `GET /routings`, `/routings/compare`, `/routings/{id}`; `POST /routings`, `/routings/{id}`, `/routings/{id}/approve`, `/copy`, `/make-current` |
| `routing.CalendarController` | V10: `GET /calendars`, `/calendars/available-minutes`, `/calendars/{id}`; `POST /calendars`, `/calendars/{id}`, `/calendars/patterns`, `/calendars/patterns/{id}`, `/calendars/assignments`, `/calendars/{id}/exceptions`, `/calendars/{id}/exceptions/{exceptionId}/remove` |
| `routing.SkillController` | V10: `GET /skills`, `/skills/users` (ADMIN/PLANNER), `/skills/mine`; `POST /skills`, `/skills/{id}`, `/skills/assignments`, `/skills/assignments/remove` |
| `manufacturing.OrderTreeController` | `GET /manufacturing/{id}/tree`; `POST /manufacturing/{id}/children` |
| `reporting.ReportingController` | `GET /state`, `GET /manufacturing/{id}` |
| `reporting.PlanningController` | `GET /planning/mrp` |
| `reporting.ReportsController` | `GET /reports/costs`, `/reports/journal` (V9, ADMIN/PLANNER) |
| `scheduling.ScheduleController` | V13: `GET /schedule`, `/schedule/runs`, `/schedule/runs/{id}`, `/schedule/runs/{id}/gantt`, `/late`, `/capacity`, `/compare`, `/schedule/next`, `/schedule/orders/{id}`, `/schedule/pins`; `POST /schedule/runs`, `/schedule/runs/{id}/publish`, `/discard`, `/schedule/pins`, `/schedule/pins/{id}/remove`, `/schedule/estimates`, `/schedule/estimates/{orderId}/remove` |
| `traceability.TraceController` | V14: `GET /traceability/search`, `/traceability/trace` |
| `traceability.RecallController` | V14: `GET /recalls`, `/recalls/{id}`, `/recalls/{id}/notifications/export`; `POST /recalls`, `/recalls/{id}/refresh`, `/contain`, `/customers/{customerId}/notify`, `/close`, `/cancel` |
| `performance.PerformanceController` | V15: `GET /performance/oee`, `/performance/losses`, `/performance/days/{machineId}/{date}` (every role); `POST /performance/close` (PLANNER) |
| `maintenance.MaintenanceController` | V11: `GET /maintenance/work-orders`, `/maintenance/work-orders/{id}`, `/maintenance/plans`, `/maintenance/plans/{id}`, `/maintenance/downtime`, `/maintenance/machines`, `/maintenance/machines/{id}`, `/maintenance/machines/{id}/history`, `/maintenance/metrics`, `/maintenance/spare-parts`, `/maintenance/technicians`, `/maintenance/settings`; `POST /maintenance/work-orders`, `/maintenance/work-orders/{id}/approve`, `/schedule`, `/start`, `/progress`, `/labour`, `/parts`, `/parts/{movementId}/return`, `/complete`, `/cancel`, `/maintenance/plans`, `/maintenance/plans/{id}`, `/maintenance/plans/generate`, `/maintenance/downtime`, `/maintenance/downtime/{id}/close`, `/maintenance/downtime/{id}/correct`, `/maintenance/machines/{id}/status`, `/maintenance/machines/{id}/meter`, `/maintenance/settings` |

## How to add a feature module

The examples use a hypothetical inventory-adjustment feature (`InventoryCommands.Adjust`, a `stock_adjustments`
table); the existing services follow exactly the same shapes, so `procurement.SupplierReturnService` is a good real
reference.

### 0. Reserve your numbers first

Each feature owns one Flyway version and one `/api/state` order range, so parallel branches never collide:

| Migration | Feature (wave plan) | State order range |
|---|---|---|
| `V1`, `V2` | foundation (applied — never edit) | 100–320 |
| `V3` | sales: multi-line sales orders, customer/supplier (partner) editing, customer returns | 1300–1399 |
| `V4` | item master: editing, units/conversions, categories, barcodes, variants, `SEMI_FINISHED` | 1400–1499 |
| `V5` | inventory: adjustments, transfers, cycle counts, warehouses | 1500–1599 |
| `V6` | procurement: multi-line purchase orders, supplier returns | 1600–1699 |
| `V7` | multi-level BOM and MRP explosion | 1700–1799 |
| `V8` | administration: user management, approval policies, CSV imports, audit pagination | 1800–1899 |
| `V9` | integration: pagination rollout, approval and unit wiring | 1900–1999 |
| `V10` | Phase 2 routings: versioned routings and operations, shift calendars, skills, manufacturing-order operation tracking | 2000–2099 |
| `V11` | Phase 2 maintenance: preventive plans, maintenance work orders, spare parts, downtime events, machine status | 2100–2199 |
| `V12` | Phase 2 quality: inspection plans and measurements, nonconformance reports, dispositions, rework, CAPA | 2200–2299 |
| `V13` | Phase 2 finite-capacity scheduling (builds on V10–V12) | 2300–2399 |
| `V14` | Phase 2 genealogy explorer and recalls (builds on V10–V12) | 2400–2499 |
| `V15` | Phase 2 OEE and utilization (builds on V10–V12) | 2500–2599 |
| `V16` | Phase 2 integration | 2600–2699 |
| `V17` | Phase 3 security: TOTP MFA (required for administrators) with recovery codes, credential lifecycle | 2700–2799 |
| `V18` | Phase 3 local server: hardened PostgreSQL on Windows, encrypted backup/restore, upgrade and restore drills, backup status | 2800–2899 |
| `V19` | Phase 3 licensing: signed per-site licence files, user limits, 14-day grace, read-only-but-exportable expiry, optional online validation | 2900–2999 |
| `V20` | Phase 3 company/site groundwork (tenant = company boundary, sites) and site/warehouse access scopes | 3000–3099 |
| `V21` | Phase 3 LAN resilience: durable browser write queue for brief server/LAN outages, replay with idempotency keys | 3100–3199 |
| `V22` | Phase 3 Windows installer: services, firewall, setup wizard, upgrade/rollback (unsigned; signing pluggable) | 3200–3299 |
| `V23` | Phase 3 integration | 3300–3399 |

Branches are merged to `main` in ascending migration order (Flyway runs with `outOfOrder=false`), two features at a
time. A feature that needs a second migration uses a sub-version of its own number (e.g. `V3_1__…`) so it still sorts
inside its slot.

### 1. Package and files

New capability → new package `lk.dexlanka.erp.<feature>`. Extending an existing domain → still prefer **new files**
(`ReturnsCommands`, `ReturnsService`, `ReturnsController` inside `sales`) over growing `SalesService`; two branches that
append to the same class conflict, two new files never do.

### 2. Command records

```java
public final class InventoryCommands {
    private InventoryCommands() {}

    public record Adjust(
            @NotBlank String lotId,
            @NotNull @Digits(integer = 12, fraction = 6) BigDecimal delta,
            @NotBlank @Size(max = 400) String reason) {}
}
```

- Quantities and money are `BigDecimal` with `@Digits(integer = 12, fraction = 6)`; dates are `yyyy-MM-dd` strings,
  checked again with `s.validDate(...)`. Enumerations are `@Pattern(regexp = "A|B")` strings.
- Record component names are the JSON field names, and the record serialized in declaration order is hashed for
  idempotency. Never rename or reorder components of a released command; add new optional components at the end.
- Never accept `tenantId` (or a user id that grants authority) in a body. Tenant ownership comes from `Actor`.

### 3. Controller

```java
@RestController
@RequestMapping("/api")
class InventoryController {
    private final AdjustmentService adjustments;

    InventoryController(AdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    @PostMapping("/inventory/adjustments")
    Map<String, Object> adjust(
            Authentication a,
            @Valid @RequestBody InventoryCommands.Adjust c,
            @RequestHeader("Idempotency-Key") String k) {
        return adjustments.adjust(Actor.of(a), c, k);
    }
}
```

Controllers only translate HTTP: `Actor.of(authentication)`, the validated body, path variables and the
`Idempotency-Key` header. Keep the parameter order `Authentication, @PathVariable..., @Valid @RequestBody, @RequestHeader`
(body validation errors win over a missing header). No business rules, no SQL. Every new route is authenticated and
CSRF-protected automatically; only `SecurityConfig` may add `permitAll` routes.

### 4. The write-command pattern

Every mutating command is one public `@Transactional` service method with this shape:

```java
@Transactional
public Map<String, Object> adjust(Actor a, InventoryCommands.Adjust c, String key) {
    a.allow("WAREHOUSE");                                     // (1) role check; ADMIN always passes
    return s.once(a, key, "adjust:" + c.lotId(), c, () -> {   // (2) tenant lock + (3) idempotency
        s.openPeriod(a);                                      //     postings: closed period -> 409
        var lot = s.one("SELECT * FROM lots WHERE tenant_id=? AND id=?", a.tenant(), c.lotId()); // 404 if not ours
        require(lot.get("status").equals("RELEASED"), "Only released lots can be adjusted");     // (4) rules -> 409
        String id = id();
        s.db().update(                                        // (5) domain rows, always with tenant_id
                "INSERT INTO stock_adjustments(id,tenant_id,lot_id,delta,reason,created_by) VALUES(?,?,?,?,?,?)",
                id, a.tenant(), c.lotId(), c.delta(), c.reason(), a.id());
        BigDecimal value = s.ledger(a, c.lotId(), c.delta(), id, "STOCK_ADJUSTMENT"); // (6) stock via the ledger
        s.journal(a, id, "Stock count adjustment", "INVENTORY_ADJUSTMENT", "RAW_INVENTORY", value.abs());
        s.audit(a, "STOCK_ADJUSTED", id, c.delta() + " on lot " + lot.get("number")); // (7) audit + outbox
        return Map.of("id", id, "status", "POSTED");          // (8) stored as the idempotent response
    });
}
```

1. **Permission** — `a.allow(roles...)` throws `AccessDeniedException` (403). Call it before `once` (cheap, before
   locking). When the required role depends on the request (as in `ManufacturingService.transition`), call it inside
   the work lambda.
2. **Tenant lock** — `s.once` validates the key (`[a-zA-Z0-9_-]{8,120}`) and then locks the tenant row
   (`SELECT … FOR UPDATE`): commands of one tenant are serialized (ADR-002). Lock balance rows with `FOR UPDATE` when
   you allocate or deplete them.
3. **Idempotency** — `once` hashes the JSON of `body` and stores `(key, action, hash, result)`. A retry with the same
   key, action and body returns the stored result without running the lambda; anything else with that key is a 409.
   `action` must identify the command *and* its target (`"approve-po:" + id`). Replayed results are decoded from JSON,
   so numbers come back without their database scale.
4. **Business rules and self-approval** — load rows with `s.one` (tenant-scoped; no row → 404), check state with
   `require(condition, message)` (409). Independent approval is a rule, not a role:
   `require(!a.id().equals(row.get("created_by")), "A different authorized person must approve this …")` — it applies
   to ADMIN too, so record `created_by` on anything that is approved later.
5. **Domain writes** — `s.db().update(...)` with `tenant_id` in every statement. IDs come from `Store.id()`;
   document numbers from `s.number(a, "PREFIX")`.
6. **Postings** — stock changes only through `s.ledger(...)` (the DB trigger updates `stock_balances`, enforces
   item/warehouse/unit consistency and non-negative balances); money only through `s.journal(...)` (balanced
   debit/credit pair, rejected in a closed period). Call `s.openPeriod(a)` before posting.
7. **Audit and outbox** — `s.audit(a, ACTION, reference, detail)` writes the audit event and a `LOCAL_ONLY` outbox
   event. Every successful command audits.
8. **One transaction** — everything above runs in the service method's transaction. Any exception rolls back the
   business rows, ledger, journal, audit, outbox *and* the idempotency record. Never catch and swallow inside the
   lambda, never call another domain's `@Transactional` method expecting a separate transaction, never start threads.

Validation that needs no database (`s.validDate`, `s.quantity` for whole pieces) may run before `once`.

### 5. Read endpoints

Prefer a feature's own `GET` endpoints with server-side pagination over growing `/api/state` (which loads everything
for every page and is capped at 250 rows per list):

```java
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdjustmentQuery {
    public Map<String, Object> page(Actor a, int limit, String before) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        var rows = s.list("""
                SELECT a.*,l.number AS lot FROM stock_adjustments a
                JOIN lots l ON l.tenant_id=a.tenant_id AND l.id=a.lot_id
                WHERE a.tenant_id=?
                  AND (?::text IS NULL OR (a.created_at,a.id) <
                       (SELECT c.created_at,c.id FROM stock_adjustments c WHERE c.tenant_id=a.tenant_id AND c.id=?))
                ORDER BY a.created_at DESC,a.id DESC
                LIMIT ?""", a.tenant(), before, before, limit + 1);
        var more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        return Map.of("items", items, "next", more ? items.getLast().get("id") : "");
    }
}
```

- Read services are `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`; every query is scoped by
  `a.tenant()`; role-restricted data checks `a.role()` explicitly.
- Use keyset (cursor) pagination on an indexed, unique ordering (`created_at, id`), not `OFFSET`.
- Return JSON objects (`Map`), never bare arrays, so fields can be added later.

### 6. Adding a key to `/api/state`

Only for small, bounded data the workspace needs on every screen. Implement `StateContributor` in your package:

```java
@Component
class InventoryStateSections implements StateContributor {
    @Override
    public List<StateSection> sections() {
        return List.of(
                StateSection.of("adjustments", 1400, this::adjustments),
                StateSection.of("valuation", 1410, this::valuation).visibleToRoles("ADMIN", "PLANNER"));
    }
    // private loaders: StateRequest -> Map/List, queries scoped by r.tenant(), with LIMIT
}
```

`StateQuery` sorts all sections by `order` (then key), rejects duplicate keys at startup, and runs every loader inside
one read-only repeatable-read snapshot. `visibleToRoles` lists the exact roles (ADMIN is *not* implied). Do not
annotate contributors with `@Transactional`. Existing keys, in order:

| Order | Keys | Owner |
|---|---|---|
| 100–160 | `company`, `factories`, `warehouses`, `partners`, `machines`, `items` (`users` at 110 was removed in V9; use `GET /api/admin/users`) | catalog |
| 170 | `orders` | manufacturing |
| 180–190 | `boms`, `bomLines` | catalog |
| 200 | `sales` (one summary row per order; summed base quantities) | sales |
| 210 | `purchases` (one summary row per order, V6) | procurement |
| 220 | `lots` | reporting (inventory) |
| 230 | `inspections` | quality |
| 260 | `mrp` | reporting (planning) |
| 270–280 | `trend`, `metrics` | reporting (dashboard) |
| 300 | `sync` (the `audit` key at 290 was removed in V8; use `GET /api/admin/audit`) | reporting (audit) |
| 310 | `financial` (ADMIN, PLANNER) | reporting (finance) |
| 1300 | `salesLines` (open lines of confirmed orders, for planning; `quantity` is the base quantity, V9 adds `entered_quantity` and `unit`) | sales |
| 1700 | `bomCosts` (multi-level standard-cost roll-up per revision; V7) | catalog |
| 2100 | `maintenance` (V11: `open_breakdowns`, `open_downtime`, `overdue_plans`, `awaiting_approval`, `in_progress` and up to five rows each of open `breakdowns` and `overdue` plans, for the overview and attention center) | maintenance |
| 2200 | `qualityAlerts` (one object of attention counts: `open_ncrs`, `open_critical_ncrs`, `pending_concessions`, `awaiting_returns`, `open_rework`, `overdue_capa_actions`, `my_overdue_capa_actions`; V12) | quality |
| 2300 | `schedule` (V13, every role: `published` run header or null, `late_orders` and `unscheduled_orders` of the published run that are still open, up to five `late` rows for the overview and attention center) | scheduling |
| 2400 | `recalls` (V14: `open` = not yet contained, `contained`, `pending_customers` on contained recalls, and up to five `active` recalls with `pending_customers`, most severe first) | traceability |
| 2500 | `performance` (V15: yesterday's time-weighted plant OEE `{day, oee, availability, performance, quality, timeCoverage, outputCoverage, utilization, measuredDays, closedDays, warningCount, workCenters}`, or `null` when no work center has a measured OEE that day) | performance |

V9 removed keys no screen reads any more: `users` (110), `receipts` (240, `GET /api/purchases/receipts`), `ledger` (250, `GET /api/inventory/ledger`) and `journal` (320, `GET /api/reports/journal`). The Manufacturing orders, Shop floor, Quality control and Costs & reports screens read the V9 paged endpoints; `orders` and `inspections` stay for the dashboard and attention counts. V9 contributes no new keys (range 1900–1999 unused). V10 contributes no keys either (range 2000–2099 unused): routings,
calendars, skills and operations are read from their own endpoints; `orders` rows gain the columns `routing_id` and
`routing_revision`.

V7 also added columns to existing keys: `orders` rows carry `parent_order_id`, and `mrp` rows (now computed by the
multi-level `MrpPlanner`) keep their original columns and add `level`, `independent_demand`, `dependent_demand`,
`inspection`, `on_order`, `safety`, `action`, `bom_id`, `need_date`, `start_date`, `late`, `drivers`.

The frontend depends on these keys and their row shapes: changing an existing section's columns is an API change.

### 7. Migrations

- File: `src/main/resources/db/migration/V<N>__snake_case_summary.sql`, using your reserved `N`. Never edit an applied
  migration (Flyway validates checksums at startup); fix forward with a new version.
- PostgreSQL DDL is transactional: a failing migration rolls back completely (covered by a test).
- Every tenant-owned table has `tenant_id text NOT NULL REFERENCES tenants(id)` and `UNIQUE(tenant_id,id)`; references
  to other tenant-owned rows are composite foreign keys `FOREIGN KEY(tenant_id,x_id) REFERENCES x(tenant_id,id)`, so a
  row can never point into another tenant. Document numbers/codes are unique per tenant.
- Quantities and money are `numeric(20,6)` with `CHECK` constraints; timestamps are `timestamptz DEFAULT now()`;
  statuses are `text` with a `CHECK(status IN (...))`.
- Add indexes for the tenant-scoped list/filter columns your read endpoints use.
- Update [MODEL.md](../../docs/database/MODEL.md) when you add tables.

### 8. Database triggers (do not work around them)

The database, not the Java code, is the last line of defence:

| Trigger (migration) | Effect |
|---|---|
| `stock_history_immutable`, `journal_history_immutable`, `journal_lines_immutable`, `audit_immutable` (V1) | `BEFORE UPDATE OR DELETE` → `reject_history_mutation()` raises *"Posted history is immutable; use a compensating transaction"*. Corrections are new reversing rows. |
| `apply_stock_balance` (V1) | `AFTER INSERT ON stock_ledger`: checks the entry's item/warehouse/unit against its lot (*"Ledger unit, item or warehouse mismatch"*), requires quantity and value signs to agree, and updates `stock_balances`, whose `CHECK`s reject negative stock/value and `reserved > quantity`. |
| `journal_must_balance` (V1) | `DEFERRABLE INITIALLY DEFERRED` constraint trigger: at commit, every journal entry's debits must equal its credits. |
| `approved_bom_immutable`, `approved_bom_line_immutable` (V2) | An `APPROVED` BOM and its lines cannot be changed or deleted; create a new revision. |
| `customer_returns_immutable` (V3) | Posted customer returns cannot be changed or deleted. |
| `sales_partner_active`, `purchase_partner_active` (V3) | `partner_must_be_active()`: a new sales/purchase order (or a sales order's changed customer) must reference an active partner; raises SQLSTATE 23514 → 409. |
| `purchase_partner_active`, `purchase_partner_changed` (V6) | Replace V3's purchase trigger: `partner_must_be_active()` runs on insert when a supplier is set and when a draft's supplier changes (drafts may have no supplier yet). |
| `supplier_invoices_immutable`, `supplier_invoice_lines_immutable`, `supplier_returns_immutable` (V6) | Posted supplier invoices, their lines and supplier returns cannot be changed or deleted. |
| `tenant_default_units` (V4) | `AFTER INSERT ON tenants`: seeds the standard units of measure for every new tenant (`seed_default_units`). Items reference `units(tenant_id, code)`. |
| `item_identity_guard` (V4) | `BEFORE UPDATE ON items`: the base unit cannot change once stock-ledger rows exist, and SKU/base unit/type cannot change once `item_is_referenced()` finds any row of any table with a foreign key to `items` (new document tables are covered automatically). |
| `mo_parent_immutable` (V7) | `BEFORE UPDATE OF parent_order_id ON manufacturing_orders`: a child order's parent link never changes (SQLSTATE 23514 → 409), so links always point at older orders and cannot form cycles. BOM cycles and missing sub-BOMs are approval rules in `BomService.checkApprovable`, not triggers. |
| `users_keep_an_active_admin` (V8) | `BEFORE UPDATE OF role, active ON users`: the last active ADMIN of a tenant cannot be demoted or deactivated (*"A workspace needs at least one active administrator"*). |
| `import_batches_immutable` (V8) | Committed CSV import batches cannot be changed or deleted (`reject_history_mutation()`). |
| `approved_routing_immutable`, `approved_routing_operation_immutable`, `approved_routing_alternate_immutable` (V10) | An `APPROVED` routing, its operations and alternate work centers cannot be changed or deleted (no operation can be added either); copy the revision into a new draft. |
| `routing_default_approved` (V10) | Only an approved routing can be a product's current revision (`routing_defaults`). |
| `mo_operation_snapshot` (V10) | The planned columns of a manufacturing-order operation (sequence, planned work center, quantity, setup/run minutes, crew, skill) never change; `DONE`/`CANCELLED` operations are immutable; operations are never deleted. |
| `mo_time_log_closed_immutable` (V10) | A closed operation time log cannot change and no time log can be deleted (only an open log gets its end). Partial unique indexes allow one open log per operation and per work center. |
| `mo_operation_reports_immutable` (V10) | Operation quantity reports are posted history (`reject_history_mutation()`). |
| `downtime_event_guard` (V11) | `protect_downtime_event()`: downtime events are never deleted; SUPERSEDED/VOID events are frozen; a CLOSED event may only become SUPERSEDED with every other column unchanged (a correction inserts the corrected event); an OPEN event keeps its machine and start. |
| `downtime_no_overlap` (V11) | `reject_overlapping_downtime()`: effective (OPEN/CLOSED) events of one machine never overlap (`[start, end)`, an open event runs to infinity); *"Downtime overlaps DT-… on the same machine"*. |
| `maintenance_work_order_immutable` (V11) | Completed and cancelled maintenance work orders cannot be changed or deleted. |
| `maintenance_labour_immutable`, `maintenance_part_movements_immutable`, `machine_meter_readings_immutable`, `machine_status_changes_immutable` (V11) | `reject_history_mutation()`: labour entries, spare-part issues/returns, meter readings and machine status history are append-only. |
| `tenant_default_defect_codes` (V12) | `AFTER INSERT ON tenants`: seeds the default defect codes (`seed_default_defect_codes`: system `INSP` plus DIM, VIS, LEAK, CONT, MAT, PACK, DOC, OTHER). |
| `recall_guard` (V14) | `protect_recall()`: recalls are never deleted; CLOSED/CANCELLED recalls are immutable; a CONTAINED recall may only become CLOSED (closing fields), everything defining its scope and affected set is frozen. A `CHECK` requires an override reason to come from someone other than the recall's creator. |
| `recall_suspects_immutable`, `recall_lots_immutable`, `recall_shipments_immutable`, `recall_customers_guard` (V14) | `protect_recall_snapshot()`: the suspect/affected-lot/shipment snapshot changes only while the recall is OPEN; `protect_recall_customer()`: customers are added/removed only while OPEN, a notification is recorded once (NOTIFIED rows never change), nothing changes after CLOSED/CANCELLED. |
| `inspection_measurements_immutable`, `inspection_readings_immutable`, `rework_materials_immutable`, `rework_lot_links_immutable` (V12) | Measurements recorded with an inspection decision, rework material issues and rework lot links (genealogy) are posted history (`reject_history_mutation()`). `CHECK`s keep rework value conservation (`output_value = input_value + material_cost + labour_cost`) and a closed CAPA verified by someone other than its owner. |
| `schedule_run_guard` (V13) | `protect_schedule_run()`: schedule runs are never deleted; SUPERSEDED/DISCARDED runs are immutable; only DRAFT → PUBLISHED/DISCARDED and PUBLISHED → SUPERSEDED, with parameters and results frozen. The partial unique index `schedule_runs_one_published` keeps one PUBLISHED run per tenant. |
| `schedule_run_orders_immutable`, `schedule_run_operations_immutable`, `schedule_run_blocks_immutable`, `schedule_pin_guard` (V13) | Run results are written once with their run (`reject_history_mutation()`); pins are never deleted and only their removal is recorded. |
| `oee_snapshot_guard` (V15) | `protect_oee_snapshot()`: closed OEE days are never deleted; a CURRENT snapshot may only become SUPERSEDED (with `superseded_at`, every other column unchanged); SUPERSEDED snapshots are frozen. A recalculation inserts the next version. |

Ledger reasons and journal accounts written by V12 (`quality.QualityRules`): `QUALITY_SCRAP` (reference = NCR number;
Dr `QUALITY_LOSS` / Cr `FG_INVENTORY` or `RAW_INVENTORY`), `REWORK_ISSUE`, `REWORK_MATERIAL`, `REWORK_OUTPUT` (reference =
`RW-…`; through the new `REWORK_WIP` account, labour credited to `CONVERSION_CLEARING`); returns to the supplier reuse
`SUPPLIER_RETURN` (`SR-…`). Genealogy reads `rework_lot_links` (input lot → output lot) and `lots.source_type='REWORK'`.

A new table of posted history gets the same protection by reusing the function:
`CREATE TRIGGER <table>_immutable BEFORE UPDATE OR DELETE ON <table> FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();`.
Every trigger error rolls back the whole command. A plain `RAISE EXCEPTION '…'` (SQLSTATE P0001) becomes HTTP 409
`application/problem+json` with the trigger's message in `detail` (`ApiErrors`, V8); a trigger raised with
`ERRCODE = 'check_violation'` (23514) or a constraint violation is a `DataIntegrityViolationException` → HTTP 409
*"This transaction violates a record, quantity or accounting constraint…"*. Prefer a plain `RAISE EXCEPTION` with a
readable sentence for business rules.

### 9. Demo data

`demo.DemoSeed` seeds the base demo tenant once, when `app.demo=true`; never edit it for a feature (V3 only moved its
sales orders to the draft → confirm → per-line dispatch commands, V6 its two purchase orders to the draft → submit →
approve → receive-lines commands). `demo.SalesDemoSeed` (`ORDER + 30`) adds the V3 sales documents,
`demo.ItemMasterDemoSeed` (`ORDER + 40`) the V4 item master data, `inventory.InventoryDemoSeed` (`ORDER + 50`) the V5
documents, `demo.ProcurementDemoSeed` (`ORDER + 60`) the V6 purchase orders (one per state), an invoice with a price
variance and a supplier return, and `demo.PlanningDemoSeed` (`ORDER + 70`) the V7 two-level bottle/preform structure,
an R02 draft copy, an approved parent order with a preform child order and a draft parent that drives planned
production, and `demo.IntegrationDemoSeed` (`ORDER + 90`) a confirmed sales order with a bottle line entered in
cartons (`box` = 50 pcs, V9), `demo.RoutingDemoSeed` (`ORDER + 100`) the V10 skills, shift calendars (two-shift
plant default with a Poya holiday and Sunday overtime, three shifts on the blow moulders), approved routings for FG-001,
SF-001 and FG-101 (plus an R02 draft) and released orders on the shop floor (FG-001 with operation 10 done and
operation 20 running; preform and PET-bottle orders with their first operation ready), and
`demo.MaintenanceDemoSeed` (`ORDER + 110`) the V11 maintenance technician persona
(also added to an existing demo database), two spare parts with stock, a week of closed downtime of different reasons,
preventive plans for IM-01 (scheduled order generated) and BM-01 (overdue), a completed corrective order on PK-01 with a
spare part, labour and a closed breakdown, and a corrective order in progress on IM-03 with its breakdown open, and
`demo.QualityDemoSeed` (`ORDER + 120`, own transaction, keys `demo-v12-…`) the V12
quality data: bottle (final) and HDPE (incoming) inspection plans, a resin receipt released with measurements, a returned
bottle lot failed on wall thickness whose NCR is partly scrapped and partly reworked (rework lot re-inspected and
released), an open critical leak NCR, and a CAPA with one done and one overdue action, and `demo.ScheduleDemoSeed` (`ORDER + 130`, own transaction, keys `demo-v13-…`) four more approved routed orders (a 30,000-bottle rush order due today that ends late, a preform batch, a second PET order and a bottle campaign into the Poya week), a duration estimate for the oldest open order without a routing, a published schedule run, then a pin on the PET order's last operation with a re-run that is published too (the first run is superseded). `performance.PerformanceDemoSeed` (`ORDER + 150`, own transaction, keys `demo-v15-…`) adds the V15 OEE history: because operation time logs and quantity reports cannot be posted with past timestamps through the commands, it backfills **clearly marked demo history** (demo mode only) on three dedicated orders `MO-DEMO-OEE-1..3` (CLOSED, execution records only — no stock, cost or production postings; reports and operation instructions carry the note *Demo OEE history (backfilled by the V15 demo seed)*): fourteen days before today of FG-001 blowing on BM-02 (alternate) and packing on PK-01 with a campaign changeover after a week, and SF-001 preform moulding and checks on IM-01, only inside shift time and never overlapping existing time logs or effective downtime; four extra downtime events through `maintenance.DowntimeService` (a 7-hour BM-02 breakdown nine days ago is the bad day, an IM-01 planned mould cleaning, a PK-01 label shortage, an IM-01 quality hold); then it closes days today − 14 … today − 2 through `PerformanceService.close` (yesterday stays open). BM-01 keeps only the base seed's output of orders without a routing (untimed), IM-02/IM-03 only their V11 downtime.
`demo.TraceabilityDemoSeed` (`ORDER + 140`, own transaction, keys `demo-v14-…`) adds the V14 recall walkthrough on its own
rPET product line (so FIFO consumption of the other seeds' stock is untouched): items `RM-010` rPET resin, `SF-010`
preform and `FG-010` 1.5 L bottle (+ the existing label) with approved R01 BOMs and no routing or inspection plan, a
Lanka Polymer Trading receipt as supplier lot `LPT-R2611-31`, preform (IM-02) and bottle (BM-02) orders run to released
output, bottles shipped to Ceylon Beverage Industries and Island Consumer Products, and a CRITICAL recall of the resin
lot, contained (resin, preform and bottle lots held by RECALL NCRs) with Ceylon notified and Island pending. A feature that needs
demo data adds its own runner, ordered after the base seed and idempotent (it also runs against an existing
`.local/postgres` demo database on the next start):

```java
@Component
@Order(DemoSeed.ORDER + 40) // after the base seed; use your migration number x 10
class InventoryDemoSeed implements ApplicationRunner {
    private final Store s;
    private final AdjustmentService adjustments;
    private final boolean demo;

    InventoryDemoSeed(Store s, AdjustmentService adjustments, @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.adjustments = adjustments;
        this.demo = demo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!demo) return;
        if (!s.list("SELECT 1 FROM stock_adjustments WHERE tenant_id=? LIMIT 1", DemoPersonas.TENANT).isEmpty())
            return; // already seeded
        adjustments.adjust(DemoPersonas.WAREHOUSE, new InventoryCommands.Adjust("opening-hdpe", ...), "demo-v4-adjust-1");
    }
}
```

Create demo records through the public service commands as `DemoPersonas` (so permissions, approvals, ledger, journal
and audit are real), use fixed `Idempotency-Key`s (`demo-v<N>-…`), keep all data fictional, and never seed outside
`app.demo=true`.

### 10. Tests

Extend `lk.dexlanka.erp.IntegrationTestBase` (in `src/test/java`). Put the class in your feature's package
(`lk.dexlanka.erp.inventory.InventoryAdjustmentTest`) to reach package-private code.

```java
class InventoryAdjustmentTest extends IntegrationTestBase {
    @Autowired AdjustmentService adjustments;

    @Test
    void adjustmentPostsLedgerJournalAndAudit() throws Exception {
        var tenant = createTenant();
        var admin = createUser(tenant.id(), "ADMIN");
        var warehouse = createUser(tenant.id(), "WAREHOUSE");
        String item = id();
        update("INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'RM','Resin','RAW_MATERIAL','kg',10,0)",
                item, tenant.id());
        String lot = openingStock(admin, item, tenant.rawWarehouse(), "10", "10");

        command(warehouse, "/api/inventory/adjustments", new InventoryCommands.Adjust(lot, bd("-2"), "Count"))
                .andExpect(status().isOk());
        assertThat(decimal("SELECT quantity FROM stock_balances WHERE tenant_id=? AND lot_id=?", tenant.id(), lot))
                .isEqualByComparingTo("8");
    }
}
```

What the base provides:

- The Spring context on a real embedded PostgreSQL (`test` profile, migrations applied, no demo data). All test classes
  share one cached context and **one database**: always create your own tenant (`createTenant()`), scope every
  assertion by tenant, and never assert global row counts. Do not add `@SpringBootTest` properties, profiles or
  `@MockBean`s in a subclass — that starts a second context and a second PostgreSQL.
- Fixtures: `createTenant()` (plant + RAW + FINISHED warehouse), `createUser(tenant, role)` (direct service calls or
  `as(actor)`), `createUser(tenant, role, password)` + `login(email, password)` (real form login, returns the session),
  `openingStock(admin, item, warehouse, qty, unitCost)`.
- HTTP: `command(actor, path, body[, idempotencyKey])` posts JSON with CSRF and an `Idempotency-Key`;
  `command(session, path, body)`; `query(actor, path)`; `commandRequest(...)` to customize; `json(result)` to read a
  JSON object response; `toJson(value)`.
- Database: `update`, `row`, `rows`, `count`, `decimal`; `s` is the `Store` for posting primitives.

Cover for every command: the happy path with its ledger/journal/audit effects, role denial (403), another tenant's
record (404), each `require` rule (409), the idempotent replay and key reuse with a different body, and rollback of
partial work on failure.

### Ledger reasons and journal accounts added by features

| Feature | Stock ledger `reason` | Journal accounts |
|---|---|---|
| V11 maintenance | `MAINTENANCE_ISSUE` (spare parts to a work order, FIFO from released lots), `MAINTENANCE_RETURN` (unused parts back into the issuing lot at the issue's unit value) | `MAINTENANCE_EXPENSE` (Dr on issue, Cr on return, against the item's inventory account: `FG_INVENTORY` for finished goods, otherwise `RAW_INVENTORY`); `MAINTENANCE_LABOUR_CLEARING` (Cr on labour at the tenant labour rate, Dr when hours are reversed) |

## Error responses (current behaviour)

| Cause | Status | Body |
|---|---|---|
| `Store.require(false, msg)` | 409 | `application/problem+json`, message in `detail` |
| `Store.one(...)` finds nothing | 404 | `application/problem+json`, `detail: "Record not found in your organization"` |
| Bean Validation failure / malformed JSON / missing header | 400 | `application/problem+json` (`detail` is generic) |
| `Actor.allow(...)` denied | 403 | `{"message":"Your role cannot perform this action"}` |
| Missing/expired CSRF token | 403 | `{"message":"Access denied or session token expired. Refresh and try again."}` |
| Temporary password not yet replaced (`SessionGuard`) | 403 | `{"message":"Change your temporary password to continue","code":"PASSWORD_CHANGE_REQUIRED"}` |
| Not signed in, account deactivated or session revoked | 401 | `{"message":"Please sign in"}` |
| Too many sign-in attempts (`LoginRateLimit`, per address) | 429 | `{"message":"Too many sign-in attempts. Try again in one minute."}` |
| Too many wrong current passwords (`PasswordAttemptLimit`, per account, V9) | 429 | `application/problem+json`, `detail` "Too many incorrect current passwords. Try again in N minutes." |
| Database trigger `RAISE EXCEPTION` (SQLSTATE P0001) | 409 | `application/problem+json`, the trigger's message in `detail` |
| Constraint violation or `check_violation` trigger | 409 | `{"message":"This transaction violates a record, quantity or accounting constraint. …"}` |
| Other database failure | 500 | `application/problem+json`, generic `detail` (no SQL) |

Spring Boot's problem-details handler (`spring.mvc.problemdetails.enabled=true`) answers `ResponseStatusException` and
validation errors before `platform.ApiErrors`; the web client shows `message`, falling back to `detail`. Keep a single
`@RestControllerAdvice`.
