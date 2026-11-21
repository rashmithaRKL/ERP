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
| `platform` | `Store` (posting primitives), `Actor` (identity + role checks), `ErpUser`, `SecurityConfig`, `LoginRateLimit`, `AuthController` (`/api/health`, `/api/auth/*`), `ApiErrors` (the single `@RestControllerAdvice`) |
| `catalog` | Organization, user accounts, partners, items, work centers (machines), BOM revisions |
| `procurement` | Purchase orders, approval, goods receipt, supplier invoice matching |
| `quality` | Incoming and final inspections (including the supplier return of a failed incoming lot and MO completion after the last final release) |
| `sales` | Sales orders, dispatch (revenue/receivables and COGS) |
| `manufacturing` | Manufacturing-order lifecycle: snapshot, approval, reservation, issue, start/pause/resume, output, close/cancel |
| `reporting` | `GET /api/state` (`StateQuery` + the `StateContributor` contract), `GET /api/manufacturing/{id}` (`OrderDetailsQuery`), cross-domain sections: inventory (lots, ledger), MRP, dashboard, audit/sync, finance |
| `demo` | `DemoDatabase` (embedded PostgreSQL for the `demo`/`test` profiles), `DemoSeed` (demo tenant, or first-start bootstrap tenant outside demo), `DemoPersonas` |

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
Make a class or method `public` only when another package (or a test in another package) needs it.

Route ownership:

| Controller | Routes under `/api` |
|---|---|
| `platform.AuthController` | `GET /health`, `GET /auth/config`, `GET /auth/csrf`, `GET /auth/me`, `POST /auth/demo` (`/auth/login`, `/auth/logout` are Spring Security filters) |
| `catalog.CatalogController` | `POST /users`, `/organization`, `/partners`, `/machines`, `/items`, `/boms`, `/boms/{id}/approve` |
| `procurement.ProcurementController` | `POST /purchases`, `/purchases/{id}/approve`, `/purchases/{id}/receive`, `/receipts/{id}/invoice` |
| `quality.QualityController` | `POST /quality/{id}/inspect` |
| `sales.SalesController` | `POST /sales`, `/sales/{id}/dispatch` |
| `manufacturing.ManufacturingController` | `POST /manufacturing`, `/manufacturing/{id}/actions/{action}`, `/manufacturing/{id}/complete` |
| `reporting.ReportingController` | `GET /state`, `GET /manufacturing/{id}` |

## How to add a feature module

The examples use a hypothetical inventory-adjustment feature (`InventoryCommands.Adjust`, a `stock_adjustments`
table); the existing services follow exactly the same shapes, so `procurement.ProcurementService` is a good real
reference.

### 0. Reserve your numbers first

Each feature owns one Flyway version and one `/api/state` order range, so parallel branches never collide:

| Migration | Feature (wave plan) | State order range |
|---|---|---|
| `V1`, `V2` | foundation (applied — never edit) | 100–320 |
| `V3` | sales: multi-line sales orders, customer/supplier (partner) editing, customer returns | 1300–1399 |
| `V4` | item master: editing, units/conversions, categories, barcodes, variants, `SEMI_FINISHED` | 1400–1499 |
| `V5` | procurement: multi-line purchase orders, supplier returns | 1500–1599 |
| `V6` | inventory: adjustments, transfers, cycle counts, warehouses | 1600–1699 |
| `V7` | multi-level BOM and MRP explosion | 1700–1799 |
| `V8` | administration: user management, approval policies, CSV imports, audit pagination | 1800–1899 |
| `V9` | integration: pagination rollout, approval and unit wiring | 1900–1999 |

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
| 100–160 | `company`, `users` (ADMIN), `factories`, `warehouses`, `partners`, `machines`, `items` | catalog |
| 170 | `orders` | manufacturing |
| 180–190 | `boms`, `bomLines` | catalog |
| 200 | `sales` | sales |
| 210 | `purchases` | procurement |
| 220 | `lots` | reporting (inventory) |
| 230 | `inspections` | quality |
| 240 | `receipts` | procurement |
| 250 | `ledger` | reporting (inventory) |
| 260 | `mrp` | reporting (planning) |
| 270–280 | `trend`, `metrics` | reporting (dashboard) |
| 290–300 | `audit`, `sync` | reporting (audit) |
| 310–320 | `financial`, `journal` (ADMIN, PLANNER) | reporting (finance) |

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

A new table of posted history gets the same protection by reusing the function:
`CREATE TRIGGER <table>_immutable BEFORE UPDATE OR DELETE ON <table> FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();`.
Trigger exceptions and constraint violations surface as `DataIntegrityViolationException` → HTTP 409
*"This transaction violates a record, quantity or accounting constraint…"*, and roll back the whole command.

### 9. Demo data

`demo.DemoSeed` seeds the base demo tenant once, when `app.demo=true`; never edit it for a feature. A feature that needs
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

## Error responses (current behaviour)

| Cause | Status | Body |
|---|---|---|
| `Store.require(false, msg)` | 409 | `application/problem+json`, message in `detail` |
| `Store.one(...)` finds nothing | 404 | `application/problem+json`, `detail: "Record not found in your organization"` |
| Bean Validation failure / malformed JSON / missing header | 400 | `application/problem+json` (`detail` is generic) |
| `Actor.allow(...)` denied | 403 | `{"message":"Your role cannot perform this action"}` |
| Missing/expired CSRF token | 403 | `{"message":"Access denied or session token expired. Refresh and try again."}` |
| Not signed in | 401 | `{"message":"Please sign in"}` |
| Constraint/trigger violation | 409 | `{"message":"This transaction violates a record, quantity or accounting constraint. …"}` |

Spring Boot's problem-details handler (`spring.mvc.problemdetails.enabled=true`) answers `ResponseStatusException` and
validation errors before `platform.ApiErrors`; the web client shows `message`, falling back to `detail`. Keep a single
`@RestControllerAdvice`.
