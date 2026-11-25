# Architecture decisions — foundation release

## Scope and boundaries

One React workspace and one Java 21 / Spring Boot modular monolith own the commercial core. The shop-floor view deliberately uses the same typed API and domain services, with a larger control layout. Separate shop-floor and platform-admin applications are later deployment boundaries, not empty services in this repository.

The API is split into domain packages under `lk.dexlanka.erp`, each with its own controller, command records and transactional service (layout and conventions: [backend developer guide](../../services/erp-api/README.md)). `platform` owns identity (`SecurityConfig`, `ErpUser`, `AuthController`), action authorization (`Actor`), error mapping (`ApiErrors`) and the posting primitives (`Store`: tenant lock, idempotency, stock ledger, journals, audit/outbox); `catalog` owns organization, users and master data including BOMs; `procurement` owns purchasing, receipt and invoice matching; `quality` owns incoming/final inspection decisions; `sales` owns customer demand and dispatch; `manufacturing` owns order snapshots, reservations, material issue and output; `maintenance` (V11) owns machine downtime, maintenance work orders, preventive plans, spare-part issues and the machine transitions into and out of MAINTENANCE; `reporting` supplies bounded read models — `StateQuery` composes the `/api/state` snapshot from the `StateContributor` sections that each domain registers, plus MO details and MRP; `demo` owns the embedded demonstration database and seed data. Cost posting is inside the business transaction. Spring transaction proxies surround each domain command; read models use repeatable-read snapshots.

## ADR-001: PostgreSQL owns business state

`NUMERIC(20,6)` and Java `BigDecimal` represent quantities and monetary values. A stock-ledger insert validates its item's unit/location and atomically changes a constrained quantity/value balance. No browser calculation can change authoritative stock. Approved BOMs and their component rows are immutable through database triggers. Order material snapshots remain stable across later revisions.

Every posted mutation records an audit entry and outbox event in the same transaction. A deferred database trigger enforces journal balance. Corrections use compensating ledger/journal transactions; update/delete triggers reject edits to posted stock, journals and audit history. Period-close checks prevent late posting. This is a manufacturing subledger, not a complete statutory accounting product.

## ADR-002: Serialized tenant commands for the first milestone

A tenant row is locked before every command. Quantity balance rows are locked during allocation/depletion. Concurrent commands within a company therefore serialize, including competing stock reservations and repeated completions. This simple, reviewed boundary prevents overselling and makes retry behavior deterministic. It is intentionally conservative: benchmark it before promising hundreds of simultaneous posting operators. A later migration can replace the broad lock with ordered item/warehouse partition locks without changing API semantics.

An `Idempotency-Key` (8–120 ASCII letters/digits/underscore/hyphen) is stored with the action, canonical command hash and result. Successful repeats return the prior result; reuse for a different action/payload conflicts. A failed command rolls back its key, stock, journals, audit and outbox together. Keys are retained for the life of the associated operational history until an archival policy is approved.

## ADR-003: Application isolation + relational constraints

Tenant ownership comes exclusively from the authenticated server principal, never from request JSON. Every operational lookup uses that owner; composite foreign keys prevent cross-tenant references. Sessions contain a user principal, are HTTP-only, expire after 30 minutes, and rotate on login. Unsafe HTTP methods require a CSRF token. Production secure cookies require HTTPS. Login attempts are limited per process/IP.

ADMIN, PLANNER, WAREHOUSE, OPERATOR, QUALITY, SALES and VIEWER roles control backend actions. An order creator cannot approve their own manufacturing/purchase order, including an administrator. ADMIN can create another authorized account. The explicit demo profile offers fictional persona switching; the production endpoint returns 404.

This release supports **one operational plant per tenant** and two initially provisioned warehouses. Users have tenant-wide read access to the operational read model; costs are not a confidential per-field security boundary, although financial report aggregates and the user directory have role restrictions. Since V8, administrators reset passwords by issuing a one-time temporary password, new and reset accounts must choose their own password before working (forced rotation), and deactivation, resets and password changes revoke existing sessions: every authenticated request compares the session's `session_version` with `users.session_version` (`platform.SessionGuard`, a database check rather than an in-memory session registry, so it holds across restarts and API processes). The last active ADMIN cannot be demoted or deactivated. Configurable plant/warehouse scopes, row-level security, MFA, email-based self-service recovery, centralized rate limiting and a separate support/admin console remain release blockers for the full commercial specification. Never advertise them as implemented.

## ADR-004: Local writer, honest connectivity

The explicit demonstration profile uses file-backed embedded PostgreSQL, loopback binding and `.local/postgres`. Production uses an external PostgreSQL service. Browser requests go through a same-origin proxy. A working LAN server can operate without public internet; offline browser writes, distributed replication and licensing are not implemented.

Outbox rows are marked `LOCAL_ONLY`. The UI reports `NOT_CONFIGURED`, zero cloud acknowledgements and no successful synchronization timestamp. No worker changes these fields or simulates cloud acknowledgements. Future synchronization must assign one ledger writer per factory/warehouse partition, include event IDs/schema versions/checkpoints, use transactional inbox deduplication and reject competing cloud writes to an offline-owned partition. Transfers must explicitly use in-transit stock; conflicts require authorized review, never last-write-wins quantity replacement.

## ADR-005: Cost and quantity policy

- FIFO allocation within each item across quality-released lots. Quarantined/rejected lots cannot satisfy demand.
- Flat BOM: required = material quantity × order output / (base output × expected yield). Kilograms round **up** to six decimals; pieces round **up** to a whole piece. No implicit cross-unit conversion. Multi-level subassemblies are not supported, so circular manufacturing references cannot be authored.
- All planned materials are issued before this milestone's order release. Their actual FIFO value enters WIP; material consumption beyond the approved snapshot is blocked pending a controlled additional-issue workflow.
- Each output posting adds explicitly entered labour/machine costs to remaining WIP, allocates WIP proportionally to output quantity versus unfinished quantity, assigns good output to finished inventory and scrap to scrap expense. Remaining WIP stays in WIP.
- The final output absorbs allocation residuals. Stock movements retain six-decimal value deltas independently of display unit cost, so full lot depletion removes the remaining exact value rather than recalculating from a rounded unit price.
- A rejected final lot remains nondispatchable, with its full value retained in held finished inventory pending a future authorized disposition/rework process. An incoming rejected lot is returned through a reversing stock/journal movement and reopens the purchase remainder.
- Dispatch recognizes revenue/receivables and COGS. Taxes, currency conversion and statutory invoicing are not implemented; rates are not hardcoded.

## ADR-006: Date and observation semantics

All posting/observation timestamps are PostgreSQL `timestamptz`; the business timezone is Asia/Colombo. Required-by dates are calendar dates. Dashboard output is selected from dated production observations, while revenue follows journal entry dates. Demonstration historical observations are backfilled, explicitly marked as demo, and posted on initialization day. Dashboard yield = good / (good + scrap); it is not OEE or machine efficiency.

## Future integration contracts

Adapters must own external transport and retries and call validated commands; they may never replace ledger quantities. Suitable boundaries include accounting export, barcode input, email delivery, approved business messaging and machine observations. No external vendor or government API is fabricated. No AI service receives customer data in this release.

## References

- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [PostgreSQL explicit locking](https://www.postgresql.org/docs/current/explicit-locking.html)
