# HTTP contract

Base: `/api`, same origin. Machine-readable OpenAPI: `.openapi/openapi.json` in the repository root.

Authentication uses an HTTP-only session cookie. Fetch `GET /auth/csrf` to receive `{token,headerName}` and attach that header on every unsafe request, including login/logout. Form login is `POST /auth/login` with URL-encoded `email` and `password`; it answers `{authenticated:true, mustChangePassword}` and records the account's last sign-in. Only active accounts can sign in. Refresh the CSRF token after login/logout. `GET /auth/me` returns the authenticated identity and organization plus `mustChangePassword`, `lastLoginAt` and `passwordChangedAt`. The explicit demo profile additionally supports `POST /auth/demo?role=admin|planner|warehouse|operator|quality`; this returns 404 outside demo (409 when an administrator deactivated that persona).

Every authenticated request is revalidated against the account (`platform.SessionGuard`): a deactivated account, or a session older than the account's last password reset/change, is signed out (401 on that request); a role or name change made by an administrator applies to the user's next request. While `mustChangePassword` is true (after an administrator reset, and for accounts created through `POST /users`), every route except `/auth/me`, `/auth/csrf`, `/auth/config`, `/auth/logout`, `/auth/password` and `/health` answers 403 `{"message":"Change your temporary password to continue","code":"PASSWORD_CHANGE_REQUIRED"}`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/password` | `{currentPassword, newPassword}` (16–128 characters, not the email address, not the current password); any signed-in role; clears `mustChangePassword`, signs out the user's other sessions and keeps this one (new session id); audited `PASSWORD_CHANGED`. Wrong current password → 409; after 5 wrong current passwords within 15 minutes the account's further attempts (even correct ones) get 429 until the oldest failure is 15 minutes old (V9; in memory per process, like the login limit); a successful change clears the failures |

All business mutations require a unique `Idempotency-Key` header; keep it across retries of the **same** request. Different payload/action with the same key returns 409. Body decimal values may be JSON strings to avoid browser floating-point conversions. Responses use six-decimal PostgreSQL numeric values; UI quantities are display-only. Record IDs are opaque strings. Tenant IDs in request JSON are not accepted or used for authorization.

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/state?days=7|14|30|90` | Tenant read snapshot; all signed-in roles; financial aggregates PLANNER/ADMIN |
| GET | `/approval-policies` | V9: `{items:[{documentType, approvalRequired, amountThreshold, approverRoles}]}` for `MANUFACTURING_ORDER`, `PURCHASE_ORDER`, `STOCK_ADJUSTMENT` (built-in default when none is stored); all roles, so screens show approve buttons to the approver roles |
| GET | `/manufacturing/orders?status=&q=&limit=&before=` | V9: manufacturing orders, newest first, keyset-paged (`limit` 1–200, default 25). `status`: `all` (default), a group `active` (not completed/closed/cancelled), `floor` (RELEASED, IN_PROGRESS, ON_HOLD, PARTIALLY_COMPLETED, QUALITY_REVIEW: the shop floor) or `production` (IN_PROGRESS, PARTIALLY_COMPLETED), or one status code; `q` matches number, product name/SKU, work center code and customer. Returns `{items, next, total, counts}`: rows have the `orders` columns of `/state` plus `parent_number`; `total` counts the filter, `counts` are the tenant's orders per status (unfiltered); all roles |
| GET | `/manufacturing/{id}` | Tenant-owned snapshot/materials/actuals/audit |
| POST | `/manufacturing` | Create snapshotted order; optional `parentOrderId` (V7) makes it a child order. V9: when the `MANUFACTURING_ORDER` approval policy needs no second person for the estimated cost (switched off, or cost at/below the threshold) the order is created APPROVED and audited `MO_AUTO_APPROVED` ("Auto-approved by policy…"); otherwise it is a DRAFT awaiting approval; PLANNER |
| POST | `/manufacturing/{id}/children` | V7: child orders for the semi-finished shortages of a DRAFT/APPROVED order `{machineId?, priority?, itemIds?}`; returns `{parentId, created, skipped}`; PLANNER |
| GET | `/manufacturing/{id}/tree` | V7: `{order, parent, ancestors, children (nested), descendants, shortages, canPlanChildren}`; all roles |
| GET | `/planning/mrp` | V7: multi-level plan `{today, summary, rows (with pegging), plannedProduction, purchases}`; ADMIN/PLANNER/WAREHOUSE |
| POST | `/manufacturing/{id}/actions/{action}` | Validated lifecycle transition; roles depend on action |
| POST | `/manufacturing/{id}/complete` | Partial/final output, scrap and actual conversion cost; OPERATOR |
| POST | `/purchases` | Original single-item contract: creates a purchase order with one base-unit line and submits it for approval; PLANNER/WAREHOUSE |
| POST | `/purchases/{id}/approve` | Independent approval (same command as `/purchases/orders/{id}/approve`); PLANNER |
| POST | `/purchases/{id}/receive` | Original single-lot receipt `{quantity, warehouseId, supplierLot}` of the order's only open line; WAREHOUSE |
| POST | `/receipts/{id}/invoice` | Original whole-receipt match `{reference}` at the order price (creates a matched supplier invoice); ADMIN |
| POST | `/quality/{id}/inspect` | Finalize pending inspection `{result, notes, measurements?}`; V12: measurements are required when an inspection plan governs it, a critical characteristic out of specification fails it, and a FAILED decision raises an NCR (see Quality management below); QUALITY |
| GET | `/quality/inspections?status=&type=&q=&limit=&before=` | V9: inspections, newest first, keyset-paged. `status` `all`/PENDING/PASSED/FAILED, `type` INCOMING/FINAL/RETURN/OPENING/REWORK (V12), `q` matches lot number, item name/SKU and notes. `{items, next, total, counts}`; rows add `sku`, `uom`, `lot_status` and `source_number` (MO, PO, SO or, V12, rework order number), and V12 `plan_id` (pending inspections governed by a plan), `ncr_id`, `ncr_number`; `counts` per status are tenant-wide; all roles |
| GET | `/reports/costs?status=&q=&limit=&before=` | V9: manufacturing cost register, newest first, keyset-paged: estimated, material, conversion, actual, WIP, finished and scrap cost per order; `status` `all`/`active`/a code, `q` matches number, product name/SKU. `{items, next, total, summary}`; `summary` = tenant-wide `{orders, estimated_cost, material_cost, conversion_cost, wip_cost, finished_cost, scrap_cost}`; ADMIN/PLANNER |
| GET | `/reports/journal?account=&q=&from=&to=&limit=&before=` | V9: posted journal lines, newest entry first, debits before credits (keyset on entry `created_at, id`, line `debit, id`; `limit` default 50). `account` exact, `q` matches reference/description, `from`/`to` inclusive Asia/Colombo dates (400 when malformed, 409 when `to` < `from`). `{items, next, totals:{debit, credit}, accounts}` (`accounts` = posted account codes, first page only); ADMIN/PLANNER |
| GET | `/sales/orders?status=&q=&limit=&before=` | Sales orders, newest first, keyset-paged (`limit` 1–200, default 25; `before` = previous page's `next`). `status`: `all` (default), `open` (CONFIRMED+PARTIAL) or a status code; `q` matches number, customer, customer reference, line product name/SKU. Returns `{items, next, total, counts}` (`counts` per status for the same search) |
| GET | `/sales/orders/{id}` | `{order, lines, deliveries, returns, manufacturing, lots, activity, credit}`; `lots` are released, unreserved lots of the open lines' products (FIFO); `credit` = `{limit, receivables, exceeded}` |
| POST | `/sales/orders` | Create a draft multi-line order `{customerId, customerReference?, dueDate, notes?, lines:[{id?, productId, quantity, unitPrice, discountPct?, unit?}]}`; V9: `quantity`/`unitPrice` are in `unit` (default the base unit; otherwise a conversion flagged for sales or a same-kind standard unit), converted exactly to `base_quantity` (409 when not exact or not a sales unit); SALES/PLANNER |
| POST | `/sales/orders/{id}` | Replace a draft's header and lines (lines that keep their `id` keep their identity); SALES/PLANNER |
| POST | `/sales/orders/{id}/confirm` | DRAFT → CONFIRMED; returns `warnings` when open receivables + order total exceed the customer's credit limit (not blocked); SALES/PLANNER |
| POST | `/sales/orders/{id}/cancel` | `{reason}`; DRAFT/CONFIRMED with nothing delivered and no linked MO in progress (RELEASED…QUALITY_REVIEW); returns `warnings` for linked MOs still open; SALES/PLANNER |
| POST | `/sales/orders/{id}/close` | `{reason}`; PARTIAL → CLOSED (close short); SALES/PLANNER |
| POST | `/sales/orders/{id}/lines/{lineId}/dispatch` | `{reference, lots:[{lotId, quantity}]}`; released lots of the line's product; one delivery + COGS and revenue/receivable journals per lot; WAREHOUSE |
| POST | `/sales/deliveries/{id}/returns` | `{quantity, reason}`; customer return ≤ delivered − returned: quarantine lot, reversing journals, RETURN inspection; SALES/PLANNER/WAREHOUSE |
| POST | `/sales` | Original single-item contract: creates a CONFIRMED order with one line; SALES/PLANNER |
| POST | `/sales/{id}/dispatch` | Original single-lot dispatch `{lotId, quantity, reference, lineId?}`; without `lineId` the only open line of the lot's product is used; WAREHOUSE |
| GET | `/partners?type=&q=&status=&limit=&before=` | Partner directory by name, keyset-paged; `type` CUSTOMER/SUPPLIER, `status` all/active/inactive, `q` matches name/email/phone/tax ID; rows include `open_documents` and (customers) `receivables` |
| GET | `/partners/{id}` | `{partner, documents, activity}` (last 10 orders or purchases, partner audit events) |
| POST | `/partners/{id}` | Edit name, email, phone, addresses, tax ID, payment days, credit limit (customers), active; audited; SALES(customer), WAREHOUSE(supplier), PLANNER |
| POST | `/items` | Create item (`RAW_MATERIAL`/`SEMI_FINISHED`/`FINISHED_GOOD`/`PACKAGING`, any active tenant unit, optional `description`, `categoryId`, `reorderQuantity`, `leadTimeDays`); PLANNER |
| GET | `/items?q=&type=&category=&active=&limit=&after=` | Item page ordered by SKU (keyset cursor `after` = `next`), `total` and type/active `facets`; `q` matches SKU/name/description or an exact barcode; `category` includes sub-categories (`none` = uncategorized); all roles |
| GET | `/items/{id}` | Item detail: usage locks, category path, units, conversions, barcodes, variant family, stock by warehouse/lot, where-used, producing BOMs, recent movements, item history; all roles |
| GET | `/items/{id}/movements?limit=&before=` | Stock ledger of the item, newest first, keyset cursor; all roles |
| GET | `/items/{id}/units` | Units a quantity of the item can be entered in (base, item conversions with purchase/sales flags, same-kind standard units) with factors; all roles |
| GET | `/items/{id}/convert?quantity=&unit=` | The conversion service: `{quantity, unit, factor, baseQuantity, baseUnit, baseDecimals, exact}`, half-up at the base unit's precision; 409 when no conversion exists or the quantity has more decimals than the unit allows; all roles |
| GET | `/items/by-barcode?code=` | Barcode lookup: item, scanned unit and base quantity per scan; 404 when unknown; all roles |
| POST | `/items/{id}` | Edit (full replacement of `sku`, `name`, `description`, `categoryId`, `uom`, `standardCost`, `reorderLevel` = safety stock, `reorderQuantity`, `leadTimeDays`); SKU/base unit only while unused; audited; PLANNER |
| POST | `/items/{id}/deactivate`, `/items/{id}/activate` | Lifecycle; inactive items are rejected on new BOMs, BOM approval, MOs, purchases and sales; PLANNER |
| POST | `/items/{id}/conversions` | Set `{unit, factor, purchase, sales}` (1 unit = factor × base unit); PLANNER |
| POST | `/items/{id}/conversions/{unit}/remove` | Remove a conversion (409 while a barcode scans as that unit); PLANNER |
| POST | `/items/{id}/barcodes`, `/items/{id}/barcodes/{barcodeId}/remove` | Add `{barcode, unit?}` (unique per tenant) / remove; PLANNER |
| GET / POST | `/units`, `/units/{code}` | List; create `{code, name, kind, decimals, ratio?}`; update `{name, decimals, active}` (decimals fixed once used); PLANNER for writes |
| GET / POST | `/item-categories`, `/item-categories/{id}` | Tree-ordered list with paths and item counts; create `{name, parentId?, description?}`; update `{name, parentId, description, active}` (cycles rejected); PLANNER for writes |
| GET / POST | `/item-templates`, `/item-templates/{id}` | Variant families: list/detail; create `{code, name, description, type, uom, categoryId, attributes:[{name, values}]}`; update `{name, description, categoryId, attributes}` (attribute names fixed and used values kept once variants exist); PLANNER for writes |
| POST | `/item-templates/{id}/variants` | Generate variants `{variants:[{values:{attr:value}, sku, name?, standardCost, reorderLevel?}]}` — each becomes an ordinary item; PLANNER |
| POST | `/boms` | Draft material formula (components may be semi-finished items with their own BOM); PLANNER |
| POST | `/boms/{id}/approve` | Freeze approved revision; V7: 409 when a semi-finished component has no approved BOM or when the revision would close a cycle at any depth; PLANNER |
| POST | `/boms/{id}/copy` | V7: `{revision}`: an APPROVED revision becomes a new DRAFT (same output, yield, components; `source_bom_id` recorded); PLANNER |
| POST | `/boms/{id}` | V7: replace a DRAFT's `{outputQty, yieldFactor, lines}`; approved revisions are immutable (409); PLANNER |
| GET | `/boms/{id}/explosion?quantity=` | V7: indented multi-level explosion (default quantity = base output); ADMIN/PLANNER/WAREHOUSE |
| GET | `/boms/explosion?productId=&quantity=` | V7: explosion of a product's active (latest approved) revision; ADMIN/PLANNER/WAREHOUSE |
| GET | `/boms/where-used?itemId=` | V7: multi-level where-used up to the end products; ADMIN/PLANNER/WAREHOUSE |
| GET | `/boms/compare?from=&to=` | V7: component diff of two revisions of the same product; ADMIN/PLANNER/WAREHOUSE |
| GET | `/boms/{id}/cost` | V7: standard-cost roll-up (per-unit cost per level, total, direct components); ADMIN/PLANNER/WAREHOUSE |
| POST | `/partners` | Customer/supplier; optional `phone`, `billingAddress`, `shippingAddress`, `taxId`, `creditLimit` (customers); SALES(customer), WAREHOUSE(supplier), PLANNER |
| POST | `/machines` | Register idle work center; PLANNER |
| POST | `/organization` | Update current organization/plant; ADMIN |
| POST | `/users` | Create a bcrypt-backed tenant account `{email, name, password (16–128), role}`; the account must choose its own password at first sign-in; an email already in use → 409; ADMIN |

### Procurement documents (`/purchases/*`, V6)

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/purchases/orders?status=&q=&limit=&before=` | Purchase orders, newest first, keyset-paged. `status`: `all` (default), `open` (APPROVED+PARTIAL), `action` (DRAFT+SUBMITTED) or a status code; `q` matches number, supplier, supplier reference, line item name/SKU. Returns `{items, next, total, counts}`; rows carry `line_count`, `lines_received`, `progress` (average line %), summed `quantity`/`received`/`returned` with `uom` when all lines share one, `awaiting_qc` receipts and `variances` (invoices with variance) |
| GET | `/purchases/orders/{id}` | `{order, lines, receipts, invoices, invoiceLines, returns, activity, warehouses}`; lines add `outstanding`, `received_in_unit`, `awaiting_qc`, `invoiced`, `expected`; receipts add the QC decision, `invoiceable` and `returnable`; `warehouses` = active RAW warehouses for receiving |
| POST | `/purchases/orders` | Create a draft `{supplierId?, supplierReference?, dueDate, notes?, lines:[{id?, itemId, quantity, unit?, unitCost, expectedDate?}]}`; PLANNER/WAREHOUSE |
| POST | `/purchases/orders/{id}` | Replace a draft (lines that keep their `id` keep their identity); PLANNER/WAREHOUSE |
| POST | `/purchases/orders/{id}/submit` | DRAFT → SUBMITTED; needs an active supplier, at least one line and active items. V9: when the `PURCHASE_ORDER` approval policy needs no second person for the order total, the order goes straight to APPROVED (`approved_by` empty, audited `PO_AUTO_APPROVED`) and the response `status` is `APPROVED`; PLANNER/WAREHOUSE |
| POST | `/purchases/orders/{id}/approve` | SUBMITTED → APPROVED by an approver role of the `PURCHASE_ORDER` policy (default PLANNER; ADMIN always; 403 otherwise) who neither created nor submitted it (409); supplier and items must still be active |
| POST | `/purchases/orders/{id}/cancel` | `{reason}`; DRAFT/SUBMITTED/APPROVED (or PARTIAL whose receipts were all rejected) with nothing received; PLANNER, WAREHOUSE for drafts |
| POST | `/purchases/orders/{id}/close` | `{reason}`; PARTIAL → CLOSED (close short); the remainder leaves MRP supply; PLANNER |
| POST | `/purchases/orders/{id}/receipts` | `{warehouseId, deliveryNote, lines:[{lineId, quantity, unit?, supplierLot?}]}`: one goods receipt (shared `GRN-…` number) into an **active RAW** warehouse; per line a quarantined lot at the line's base unit cost, a `GOODS_RECEIPT` ledger entry, Dr RAW_INVENTORY / Cr GRNI and an INCOMING inspection; WAREHOUSE |
| POST | `/purchases/orders/{id}/invoices` | `{supplierReference, invoiceDate, notes?, lines:[{receiptId, quantity, unitPrice}]}` (base unit); ADMIN |
| POST | `/purchases/receipts/{id}/returns` | `{quantity, reason, debitNote}`: released stock back to the supplier; WAREHOUSE |
| POST | `/purchases/recommendations` | `{lines:[{itemId, quantity, supplierId?}], dueDate?}`: draft orders from planning shortages, one per supplier; returns `{purchases:[{id, number, supplierId, supplier, lines, total}], count}`; PLANNER/WAREHOUSE |
| GET | `/purchases/receipts?status=&q=&limit=&before=` | Goods receipts; `status` `all`, `QUARANTINE`, `RELEASED`, `REJECTED` or `uninvoiced`; `{items, next}` |
| GET | `/purchases/invoices?status=&q=&limit=&before=` | Supplier invoices; `status` `all`, `MATCHED`, `VARIANCE`; `{items, next}` |
| GET | `/purchases/returns?q=&limit=&before=` | Supplier returns; `{items, next}` |
| GET | `/purchases/sources?items=a,b` | Per item: preferred supplier (the active supplier it was last ordered from; drafts and cancelled orders ignored), that line's base unit cost, standard cost and lead time |

Lifecycle: `DRAFT` (editable; the supplier may be empty, e.g. a planning draft) → `SUBMITTED` (awaiting independent approval) → `APPROVED` → `PARTIAL` → `RECEIVED`; `CANCELLED` while nothing is received; `CLOSED` short from `PARTIAL`. An incoming QC failure returns the lot to the supplier (reversing stock and GRNI exactly as before V6), takes the quantity back from the order line and reopens the order (`PARTIAL`, or `APPROVED` when nothing is left received; closed orders stay closed). Units: a line is entered in the item's base unit, an item conversion flagged for purchasing or a same-kind standard unit (`g` for a `kg` item); `UnitService.toBaseExact` converts it (a quantity that is not exact in the base unit is refused) and `base_unit_cost = unitCost / factor` (6 dp) becomes the lot cost. Inactive suppliers and items are refused on new orders, submission and approval with a 409 naming them.

Invoice matching (three-way: order price, released receipt, invoice): each line bills ≤ the receipt's open quantity (received − invoiced − returned before invoicing). Goods-received clearing is released at the receipt's posted cost (Dr GRNI / Cr ACCOUNTS_PAYABLE — the original posting); the line that invoices the rest of a receipt releases exactly the clearing left. The difference between the billed amount and that cost posts to `PURCHASE_PRICE_VARIANCE` (Dr when billed above the order price, Cr when below) against ACCOUNTS_PAYABLE, so payables equal the supplier's bill; lots and inventory are not revalued. An invoice whose unit price differs from the order or that bills less than the open quantity has status `VARIANCE` (lines show `price_variance` and `quantity_variance`). A supplier reference can be used once per supplier.

Supplier returns: only quality-released receipt lots; quantity ≤ received − returned and ≤ the lot's unreserved stock. The stock leaves at the lot's balance value (`SUPPLIER_RETURN`, a full depletion removes the exact remaining value). The part not yet invoiced reverses clearing like a QC rejection (Dr GRNI / Cr RAW_INVENTORY); the part already invoiced is settled by the debit note (Dr ACCOUNTS_PAYABLE / Cr RAW_INVENTORY). The order line's `returned` grows; received (and MRP supply) is unchanged.

```json
{"supplierId":"opaque-supplier","supplierReference":"Q-2611-04","dueDate":"2026-12-05","notes":"",
 "lines":[{"id":null,"itemId":"opaque-resin","quantity":"4","unit":"bag","unitCost":"12000","expectedDate":null},
          {"id":null,"itemId":"opaque-carton","quantity":"300","unit":null,"unitCost":"30","expectedDate":"2026-12-01"}]}
```

```json
{"warehouseId":"opaque-raw-warehouse","deliveryNote":"DN-7781","lines":[{"lineId":"opaque-line-1","quantity":"2","unit":"bag","supplierLot":"SUP-LOT-1"}]}
```

```json
{"supplierReference":"INV-88213","invoiceDate":"2026-11-23","notes":null,"lines":[{"receiptId":"opaque-receipt","quantity":"100","unitPrice":"10.5"}]}
```

### Inventory operations (`/inventory/*`)

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/inventory/warehouses` | Warehouses with lots in stock, quarantined lots, open counts and `production_output` flag; all roles |
| POST | `/inventory/warehouses` | Create `{name, code, kind}` (kind `RAW`, `FINISHED`, `WIP`, `QUARANTINE`, `OTHER`); PLANNER/WAREHOUSE |
| POST | `/inventory/warehouses/{id}` | Edit `{name, code, kind, active}`; PLANNER/WAREHOUSE |
| GET / POST | `/inventory/settings` | Read / set `{approvalThreshold}` (default 50,000) — the threshold of the `STOCK_ADJUSTMENT` approval policy (V8; `0` when the policy has no threshold). The read also returns `approvalRequired` and `approverRoles`; set is ADMIN only and keeps the policy's other fields |
| GET | `/inventory/valuation` | Value per warehouse and quality status, and per inventory account; ADMIN/PLANNER/WAREHOUSE |
| GET | `/inventory/lots?warehouseId&itemId&status&q&includeEmpty&movable&limit&before` | Lots with balances (available = on hand − reserved) |
| GET | `/inventory/lots/{id}` | Lot, movements, lots transferred from it, inspections |
| GET | `/inventory/ledger?warehouseId&itemId&lotId&reference&q&from&to&limit&before` | Stock ledger; dates are Asia/Colombo calendar days |
| GET | `/inventory/adjustments?status&reason&q&limit&before`, `/inventory/adjustments/{id}` | Adjustments; detail adds lines, ledger, journal (ADMIN/PLANNER only) and audit events |
| POST | `/inventory/adjustments` | Draft `{reason, notes, lines[]}`; WAREHOUSE/PLANNER |
| POST | `/inventory/adjustments/{id}/post` | Post, or `APPROVAL_REQUIRED` when the `STOCK_ADJUSTMENT` approval policy asks for a second person (default: gross value above the threshold); WAREHOUSE/PLANNER |
| POST | `/inventory/adjustments/{id}/approve` | Post an adjustment awaiting approval; a person other than its creator and submitter with one of the policy's approver roles (default WAREHOUSE/PLANNER) |
| POST | `/inventory/adjustments/{id}/cancel` | Cancel a draft or an adjustment awaiting approval; WAREHOUSE/PLANNER |
| GET | `/inventory/transfers?status&warehouseId&q&limit&before`, `/inventory/transfers/{id}` | Transfers; detail adds lines, ledger pair, audit events |
| POST | `/inventory/transfers`, `/inventory/transfers/{id}/post`, `/inventory/transfers/{id}/cancel` | Draft `{fromWarehouseId, toWarehouseId, notes, lines[{lotId, quantity}]}`, post, cancel; WAREHOUSE/PLANNER |
| GET | `/inventory/counts?status&warehouseId&q&limit&before`, `/inventory/counts/{id}` | Counts (`status=OPEN` = COUNTING + REVIEW); detail hides system quantities of a blind count while COUNTING |
| POST | `/inventory/counts` | Start `{warehouseId, itemId?, itemType?, blind, notes, categoryId?}`: snapshots every countable lot; V9: `categoryId` limits the count to items of that category and all of its sub-categories (combinable with `itemType`; list and detail rows carry `category_id`, `category`); WAREHOUSE/PLANNER |
| POST | `/inventory/counts/{id}/entries` | `{entries[{lineId, countedQty}]}` while COUNTING; WAREHOUSE/PLANNER |
| POST | `/inventory/counts/{id}/submit`, `/reopen`, `/post`, `/cancel` | COUNTING → REVIEW → POSTED (variance adjustment), REVIEW → COUNTING, cancel; WAREHOUSE/PLANNER |

Lists return `{items, next}`; pass `next` as `before` for the next page (keyset on `created_at, id`; `limit` 1–200, default 50). Adjustment line kinds: `DECREASE` / `INCREASE` (`lotId`, at the lot's current cost; decreases never go below the reserved quantity) and `FOUND` (`itemId`, `warehouseId`, `unitCost`, optional `lotNumber`: a new released lot). Reasons restrict kinds: `DAMAGE`, `EXPIRY`, `SAMPLE` decrease only; `FOUND` increases or adds found stock; `COUNT_VARIANCE`, `OTHER` any. The approval rule compares the **gross** value (increases + decreases) with the threshold. Posting values each line like the existing depletion (balance average value; a full depletion removes the exact remaining value) and posts one balanced journal entry per inventory account and direction against `INVENTORY_ADJUSTMENT`. Transfers post a value-neutral `TRANSFER_OUT`/`TRANSFER_IN` ledger pair and no journal (inventory accounts follow the item type, not the warehouse). Receipts awaiting incoming inspection (quarantined `PURCHASE` lots) cannot be adjusted, transferred or counted. Count posting compares counted quantities with the system quantity at posting time and returns `moved` (lots that changed after the snapshot) and the generated `adjustment`.

```json
{"reason":"COUNT_VARIANCE","notes":"Recount of bay 4","lines":[{"kind":"DECREASE","lotId":"opaque-lot","quantity":"12"},{"kind":"FOUND","itemId":"opaque-item","warehouseId":"opaque-warehouse","quantity":"5","unitCost":"125","lotNumber":null}]}
```

### Multi-level BOMs and planning (V7)

A `SEMI_FINISHED` component whose product has an approved revision is the next level. The **active** revision of a product is its most recently created approved revision; explosion, cost roll-up, child orders and MRP use it. Revisions are approved bottom-up: approval rejects a semi-finished component without an approved BOM and any path of approved revisions that leads back to the product (`"This revision would create a BOM cycle: CMP → PRE → CMP"`).

Explosion quantities follow the manufacturing snapshot at every level: `line quantity × parent quantity / (output × yield)` of that level's revision, rounded up to whole pieces for `pcs` and to 6 decimals otherwise; the next level scales from the rounded quantity (exactly what a child order would snapshot). Nodes carry `level`, `quantity`, `exactQuantity`, `perUnit`, `standardCost`, `extendedCost` (rolled up for expanded nodes), the expanded `bomId`/`revision`, `missingBom`/`cycle` flags and `children`; the response adds `materials` (bought-in totals across levels), `materialCost`, `unitCost`, `depth` and `warnings`. The cost roll-up values bought-in items at standard cost and semi-finished items at their active revision's rolled-up cost (`levels` = bought-in cost per unit of product consumed at each level; they add up to `unitCost`; `singleLevelCost` uses the direct components' item standard costs). Compare reports per-unit quantities (output and yield applied) so a changed base output alone is not a component change; changes are `ADDED`, `REMOVED`, `CHANGED`, `UNCHANGED`.

Child orders: `POST /manufacturing/{id}/children` creates, for each semi-finished material of a DRAFT/APPROVED order with `shortage = required − reserved/issued − released unreserved stock left after earlier-due open orders (their requirements less their own children's supply) − pending supply of its open children (still to produce + output in final QC)`, one draft child order: quantity = shortage, the component's active revision, the parent's work center/priority unless given, due `lead_time_days` of the component before the parent. 409 when nothing is short. An explicit child is `POST /manufacturing` with `parentOrderId`: the parent must be DRAFT/APPROVED, consume the product, be due no earlier than the child, and the child must not be linked to a sales line. The link never changes (DB trigger). Children are ordinary orders: independent approval, and their released output lots satisfy the parent's normal reservation.

MRP (`GET /planning/mrp`, and the `mrp` rows of `/state`): gross demand = unreserved snapshots of DRAFT/APPROVED orders (need date = order due date) + dependent demand of planned production (need date = its start). Per item, by low-level code: `shortage = gross + safety stock (active items) − released unreserved stock − stock in inspection − open MO quantity still to produce − incoming purchase supply`, lot-for-lot, whole pieces rounded up. Open demand is pegged in need-date order before safety stock; a pure safety-stock shortfall is needed today. `action`: `PRODUCE` (semi-finished with an approved BOM → `plannedProduction`, start = need − lead time), `PURCHASE` (bought-in → `purchases`, order date = need − lead time), `NO_BOM` (semi-finished without a BOM; listed as a purchase), `REVIEW` (inactive item with open demand), `NONE`. Rows: `level, gross, independent_demand, dependent_demand, available, inspection, incoming, on_order, safety, shortage, action, bom_id, need_date, start_date, late, drivers, supply_orders, pegging[]`; planned production adds `parent_orders` (DRAFT/APPROVED orders whose uncovered demand drives it, candidates for `parentOrderId`).

```json
{"machineId":null,"priority":"HIGH","itemIds":["opaque-semi-finished-item"]}
```
### Quality management (`/quality/*`, V12)

Reads are open to every signed-in role (the Quality control screen gates actions to QUALITY/ADMIN; PLANNER/WAREHOUSE view). Lists are keyset-paged (`limit` 1–200, default 25, `before` = previous `next`) and return `{items, next, total[, counts]}`.

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/quality/plans?status=&type=&q=` | Inspection plans: `status` `active` (default)/`inactive`/`all`, `type` INCOMING/FINAL/RETURN/REWORK; rows add item/category names and characteristic counts |
| GET | `/quality/plans/{id}` | `{plan, characteristics}` (current version) |
| POST | `/quality/plans` | `{name, itemId \| categoryId, inspectionType, notes?, characteristics:[{name, kind VARIABLE\|ATTRIBUTE, unit?, nominal?, lowerLimit?, upperLimit?, sampleSize, critical, defectCode?, method?}]}`; one active plan per item (or category) and type; VARIABLE needs a limit and 1–20 readings; QUALITY |
| POST | `/quality/plans/{id}` | `{name, notes?, active, characteristics}`: replaces the characteristic set (old ones are retired, measurements keep them), `version` + 1; QUALITY |
| GET | `/quality/inspections/{id}/plan` | `{inspection, plan \| null, characteristics}`: the plan that governs the decision (item plan before category plan, nearest category first; RETURN and REWORK inspections fall back to the FINAL plan) |
| GET | `/quality/inspections/{id}/measurements` | `{inspection, items}`: measurements with `readings` and the specification snapshot (`plan_version`, limits, `critical`, `conforming`, `note`) |
| GET / POST | `/quality/defect-codes`, `/quality/defect-codes/{code}` | Catalogue `{items}` (seeded per tenant: INSP, DIM, VIS, LEAK, CONT, MAT, PACK, DOC, OTHER); create/edit `{code, name, description?, active}`; system code `INSP` stays active; QUALITY |
| GET | `/quality/ncrs?status=&severity=&defect=&item=&source=&q=` | NCRs; `status` `all`/`active` (open + dispositioned)/a status; rows add item, lot, defect name, `dispositioned`, `pending`; `counts` per status and `OPEN_<severity>` (tenant-wide) |
| GET | `/quality/ncrs/{id}` | `{ncr, dispositions, capas, activity}`; the NCR row adds lot status/balance, inspection, MO, supplier, customer return and rework source |
| POST | `/quality/ncrs` | Raise `{source PRODUCTION\|SUPPLIER\|CUSTOMER\|INTERNAL\|AUDIT\|RECALL, severity, defectCode, title, description, containment?, itemId?, lotId?, quantity, manufacturingOrderId?, supplierId?}`. With a lot: quantity ≤ its balance, no pending inspection, at most one open NCR per lot, links derived from the lot's source; a RELEASED lot is put on hold (QUARANTINE, `holdApplied`). Without a lot the NCR closes directly; QUALITY |
| POST | `/quality/ncrs/{id}` | Edit an OPEN NCR `{severity, defectCode, title, description, containment?}`; QUALITY |
| POST | `/quality/ncrs/{id}/dispositions` | `{type, quantity, note, instructions?}`; open NCR with a lot; the non-rejected dispositions never exceed the affected quantity. `USE_AS_IS` → PENDING_APPROVAL; `SCRAP` → posted now (ledger `QUALITY_SCRAP`, Dr `QUALITY_LOSS` / Cr inventory account; closed period → 409); `RETURN_TO_SUPPLIER` (purchased lots, ≤ receipt quantity not yet returned) → AWAITING_RETURN; `REWORK` → opens `RW-…` and moves the quantity into rework WIP. Returns `{id, status, value, ncrStatus, reworkOrderId?, reworkNumber?}`; QUALITY |
| POST | `/quality/dispositions/{id}/decision` | `{decision APPROVE\|REJECT, note}` on a pending concession; the approver must differ from the requester (ADMIN included); QUALITY |
| POST | `/quality/dispositions/{id}/return` | `{debitNote, reason?}`: posts the supplier return through `procurement.SupplierReturnService` (same `SR-…` document, `SUPPLIER_RETURN` ledger, GRNI / accounts-payable postings, receipt and order-line `returned`); WAREHOUSE |
| POST | `/quality/ncrs/{id}/close` | `{note}`: DISPOSITIONED (no open rework order), or OPEN with nothing to disposition; QUALITY |
| POST | `/quality/ncrs/{id}/cancel` | `{reason}`: OPEN without dispositions; a containment hold is lifted; QUALITY |
| GET | `/quality/rework?status=&q=` | Rework orders (`all`/OPEN/COMPLETED) with input/output lots and the output inspection status |
| GET | `/quality/rework/{id}` | `{rework, materials, links, activity}` (`links` = `rework_lot_links`) |
| POST | `/quality/rework/{id}/complete` | `{outputQuantity ≤ input, labourCost, materials?:[{lotId, quantity}], note?}`: materials from RELEASED lots (unreserved), output lot `source_type REWORK` in QUARANTINE with a REWORK inspection, value = input + material + labour; QUALITY/OPERATOR/PLANNER |
| GET | `/quality/summary?from=&to=` | Inclusive Asia/Colombo dates (default the last 30 days): `{openBySeverity, raised, scrapValue, scrapDispositions, reworkCost, reworkInputValue, reworkCompleted, concessions, topDefects}` |
| GET | `/quality/lots/{id}` | `{ncrs, reworkOrigin, reworkedInto}` for the lot drawer |
| GET | `/quality/people` | Active users `{items:[{id, name, role}]}` for CAPA owner pickers |
| GET | `/quality/capas?status=&q=&overdue=` | CAPAs (`all`/`active`/a status; `overdue=true` keeps CAPAs past their target or with an overdue action); rows add owner, action counts, `overdue_actions`, linked NCR numbers; `counts` adds `OVERDUE_ACTIONS` |
| GET | `/quality/capas/{id}` | `{capa, ncrs, actions (with overdue), activity}` |
| GET | `/quality/capa-actions?status=&overdue=&mine=` | Actions across CAPAs |
| POST | `/quality/capas` | `{title, problem, source NCR\|AUDIT\|CUSTOMER\|SUPPLIER\|INTERNAL, ownerId, dueDate?, ncrIds?}`; QUALITY |
| POST | `/quality/capas/{id}` | `{title, problem, ownerId, dueDate?, rootCauseCategory? MAN\|MACHINE\|METHOD\|MATERIAL\|MEASUREMENT\|ENVIRONMENT, whys?[≤5], rootCause?, ncrIds?}` (not CLOSED/CANCELLED); QUALITY |
| POST | `/quality/capas/{id}/actions` | `{type CORRECTIVE\|PREVENTIVE, description, ownerId, dueDate}` (OPEN/IN_PROGRESS); QUALITY |
| POST | `/quality/capa-actions/{id}/complete`, `/cancel` | `{note}`; complete: the action's owner (any role) or QUALITY; cancel: QUALITY |
| POST | `/quality/capas/{id}/start`, `/submit`, `/verify`, `/cancel` | `start {note}` OPEN → IN_PROGRESS (root-cause category + analysis and an action needed); `submit {note}` IN_PROGRESS → VERIFICATION (all actions done or cancelled, one done; QUALITY or the owner); `verify {effective, note}` by a different person than the owner: effective → CLOSED, not effective → IN_PROGRESS; `cancel {note}` also cancels open actions; QUALITY |

Inspection decisions with a plan: `POST /quality/{id}/inspect` takes `measurements:[{characteristicId, values?, nonconforming?, note?}]`, one per active characteristic of the governing plan (VARIABLE: exactly `sample_size` readings; ATTRIBUTE: nonconforming units ≤ sample size). A critical characteristic out of specification makes the decision FAILED whatever `result` says; a non-critical one passes only with a `note` on that measurement. Without a plan, measurements are refused and the Phase 1 decision is unchanged. Every FAILED decision raises an NCR (source INSPECTION, severity CRITICAL when a critical characteristic failed, else MAJOR; defect from the failing characteristic, else `INSP`); the response adds `ncrId`, `ncrNumber`. A failed incoming lot is still returned to the supplier in full, and that return is recorded on its NCR as an automatic, posted RETURN_TO_SUPPLIER disposition (NCR DISPOSITIONED).

`/state` key `qualityAlerts` (order 2200, every role): `{open_ncrs, open_critical_ncrs, pending_concessions, awaiting_returns, open_rework, overdue_capa_actions, my_overdue_capa_actions}`.

```json
{"result":"PASSED","notes":"Walls within specification","measurements":[
  {"characteristicId":"opaque-wall","values":["0.45","0.44","0.46"],"nonconforming":null,"note":null},
  {"characteristicId":"opaque-weight","values":["22.9","22.1"],"nonconforming":null,"note":"Heavier neck accepted (drawing rev B)"},
  {"characteristicId":"opaque-leak","values":null,"nonconforming":0,"note":null}]}
```

```json
{"type":"REWORK","quantity":"200","note":"Sound bottles: sort and relabel","instructions":"100% shoulder gauge"}
```

```json
{"outputQuantity":"200","labourCost":"1800","materials":[{"lotId":"opaque-label-lot","quantity":"200"}],"note":"Relabelled"}
```

### Administration (`/admin/*`, ADMIN only)

| Method | Path | Purpose |
|---|---|---|
| GET | `/admin/users?q&role&status&limit&before` | Users by name (keyset on `lower(name), id`); `q` matches name/email, `status` `ACTIVE`/`INACTIVE`. Rows: `id, email, name, role, active, created_at, last_login_at, password_changed_at, deactivated_at, must_change_password`; `summary` = `{total, active, inactive, admins, pending_password}` |
| POST | `/admin/users/{id}` | `{name, role}`; a role change applies to the user's next request; the last active ADMIN cannot be demoted (409); audited `USER_UPDATED` |
| POST | `/admin/users/{id}/deactivate`, `/reactivate` | Deactivation blocks sign-in and signs out open sessions; not yourself, not the last active ADMIN (409); audited |
| POST | `/admin/users/{id}/reset-password` | Issues a one-time temporary password, returned once as `temporaryPassword` (an idempotent replay returns `null`; the password is never stored in clear text); the user must change it at the next sign-in and open sessions end; not for yourself; audited `USER_PASSWORD_RESET` |
| GET | `/admin/approval-policies` | `{items}`: one policy per `MANUFACTURING_ORDER`, `PURCHASE_ORDER`, `STOCK_ADJUSTMENT` with `approvalRequired, amountThreshold, approverRoles, customized, wired, updatedAt, updatedBy` (`wired` is true for all three since V9) |
| POST | `/admin/approval-policies/{documentType}` | `{approvalRequired, amountThreshold (null = every document), approverRoles ⊆ PLANNER, WAREHOUSE, QUALITY, SALES, OPERATOR}`; ADMIN always approves, a creator never approves their own document; audited `APPROVAL_POLICY_UPDATED`. In effect for every document type (V9): stock adjustments and count variances when posted (gross value), manufacturing orders when created (estimated cost), purchase orders when submitted (order total). A document at or below the threshold, or any document while approval is off, takes effect directly (`MO_AUTO_APPROVED` / `PO_AUTO_APPROVED` / posted adjustment); approve rights always follow the current approver roles, so a document already waiting is never stranded |
| POST | `/admin/imports/{kind}/validate` | `kind` = `items`, `partners` or `opening-stock`. Dry run of `{fileName, content}` (CSV text): runs the whole import in a rolled-back transaction and returns `{rows, validRows, valid, errors:[{row, column, message}], preview, summary, alreadyImported}`; no Idempotency-Key needed, nothing is kept |
| POST | `/admin/imports/{kind}/commit` | Imports the same file in one transaction, only when every row is valid (409 with the first error otherwise). Idempotent per file content: an already imported file returns its batch with `duplicate: true`. Audited `IMPORT_COMMITTED` plus the per-record audit of each created record |
| GET | `/admin/imports?limit&before` | Committed batches, newest first |
| GET | `/admin/imports/{kind}/template` | CSV template (`text/csv` attachment) |
| GET | `/admin/audit?actor&action&q&from&to&limit&before` | Audit trail, newest first (keyset on `created_at, id`); `actor` and `action` exact, `q` matches reference or detail, `from`/`to` inclusive Asia/Colombo dates |
| GET | `/admin/audit/facets` | `{actors, actions}` for the filter pickers |
| GET | `/admin/audit/export?actor&action&q&from&to` | The same filter as a streamed CSV (UTF-8 with BOM, at most 50,000 rows; header `X-Export-Truncated`); formula-like cells are prefixed with `'`; the export is audited `AUDIT_EXPORTED` |
| GET | `/admin/periods` | `{items, today, latestClosed, postingBlocked}`; items: `end_date, start_date, closed, closed_by, closed_at, reopened_by, reopened_at, reopen_reason, journal_entries, journal_total` |
| POST | `/admin/periods/close` | `{endDate}`: closes the period ending then; periods close in date order, never after today, a reopened earlier period first; audited `PERIOD_CLOSED` |
| POST | `/admin/periods/reopen` | `{reason}` (10–400 characters): reopens the most recently closed period; audited `PERIOD_REOPENED` |

Import columns (header names are matched case-insensitively; spaces and hyphens read as underscores; comma or semicolon separated; at most 2,000 data rows and 2,000,000 characters):

- `items`: `sku, name, type, uom, standard_cost` required; `reorder_level, reorder_quantity, lead_time_days, category` (active category name), `description` optional. Rows go through `ItemService.create`.
- `partners`: `type` (CUSTOMER/SUPPLIER), `name` required; `email, phone, payment_days` (default 30), `credit_limit` (customers), `tax_id, billing_address, shipping_address` optional. A name that already exists for the same type is a row error.
- `opening-stock`: `item_sku, warehouse_code, quantity` (base unit, unit precision), `unit_cost` required; `lot_number` (generated `OPN-…` when blank, unique), `quality_status` (`RELEASED` default or `QUARANTINE`, which adds a pending `OPENING` inspection) optional. Each row creates a lot (`source_type OPENING`, source = batch number) and one `OPENING_STOCK_IMPORT` ledger receipt; the batch posts one balanced journal per inventory account (`RAW_INVENTORY`, `FG_INVENTORY` for finished goods) against `OPENING_EQUITY`. A closed period rejects the file.

Accounting periods: a period ends on `end_date` and starts the day after the previous period's end. Posting semantics are unchanged: every posting is dated now, and `Store.openPeriod` rejects postings while a closed period covers the current date, so closing a past period locks it and closing a period that ends today stops all postings for the rest of the day.

Lifecycle actions: `approve` (an approver role of the `MANUFACTURING_ORDER` policy — default PLANNER, ADMIN always — distinct from the creator; orders the policy auto-approves are created APPROVED); `reserve` (PLANNER/WAREHOUSE); `issue` (WAREHOUSE); `start`, `pause`, `resume` (OPERATOR/PLANNER); `close`, `cancel` (PLANNER). Cancellation is available only before material issue, with reservations safely released. Issued order cancellation is blocked until a controlled return/variance procedure exists.

Examples:

```json
{"bomId":"opaque-approved-revision","machineId":"opaque-work-center","salesOrderId":null,"quantity":"1000","priority":"NORMAL","dueDate":"2026-11-05","salesOrderLineId":"opaque-sales-line"}
```

`salesOrderLineId` (optional) plans the order for a line of a CONFIRMED/PARTIAL sales order: the BOM product must match the line's product and planned quantity (non-cancelled orders, less scrap) cannot exceed the line's base quantity. A bare `salesOrderId` still works when that order has exactly one line of the BOM product.

```json
{"customerId":"opaque-customer","customerReference":"PO-4471","dueDate":"2026-12-05","notes":"",
 "lines":[{"id":null,"productId":"opaque-bottle","quantity":"120","unit":"box","unitPrice":"1375","discountPct":"5"},
          {"id":null,"productId":"opaque-cap","quantity":"12000","unit":null,"unitPrice":"7.8","discountPct":"0"}]}
```

```json
{"reference":"DN-2026-01","lots":[{"lotId":"opaque-lot-a","quantity":"4000"},{"lotId":"opaque-lot-b","quantity":"500"}]}
```

Sales lines (V9) store the entered `quantity`, `unit`, `factor` (base units per entered unit), `base_quantity` (exact), `unit_price` per entered unit and `base_unit_price` (= unit price / factor, 6 dp); `line_total` = quantity × unit price × (1 − discount). Dispatch, `delivered`/`returned`, `outstanding`, manufacturing demand and planning are base quantities; the detail's lines add `uom` (base unit). Per delivery, revenue/receivable = base quantity ÷ factor × unit price × (1 − discount), rounded once; the delivery that completes a line absorbs the rounding residual so line revenue equals the line total. A customer return reverses `credit_amount` (Dr SALES_REVENUE / Cr ACCOUNTS_RECEIVABLE) and `cost_amount` (Dr FG_INVENTORY / Cr COST_OF_GOODS_SOLD) for the returned share of the delivery; returning the rest of a delivery reverses exactly what remains.

```json
{"goodQty":"400","scrapQty":"5","labourCost":"2500.00","machineCost":"1800.00","notes":"Five startup rejects; partial run"}
```

```json
{"productId":"opaque-finished-item","revision":"R04","outputQty":"1000","yieldFactor":"0.98","lines":[{"itemId":"opaque-resin","quantity":"22"}]}
```

```json
{"unit":"bag","factor":"25","purchase":true,"sales":false}
```

Units: stock, BOM quantities and documents are stored in the item's base unit. `GET /items/{id}/convert?quantity=3&unit=bag` returns `{"baseQuantity":75,"baseUnit":"kg","exact":true,...}`. Other features convert with `catalog.UnitService.toBase(actor, itemId, quantity, unit)` (or `toBaseExact`, which refuses rounding) inside their own transaction; purchase-order lines and goods receipts use it since V6, sales-order lines since V9 (supplier invoice lines are still entered in the base unit).

Errors use HTTP 400 (invalid input), 401 (unauthenticated), 403 (role/CSRF/password change required), 404 (missing/unowned record), 409 (business state/quantity/reference conflict), 429 (login limit; repeated wrong current passwords), and sanitized 500s. The body depends on where the error is raised:

| Cause | Status | Body |
|---|---|---|
| Business rule (`Store.require`), unknown or foreign record (`Store.one`), bad date | 409 / 404 / 400 | `application/problem+json`: `{type, title, status, detail, instance}`; the message is in `detail` |
| Bean Validation failure, malformed JSON, missing `Idempotency-Key` | 400 | `application/problem+json`; `detail` is generic |
| Business rule raised by a database trigger (SQLSTATE P0001, e.g. immutable history, approved BOMs, item identity, last administrator) | 409 | `application/problem+json`; `detail` is the trigger's message |
| Other database constraint violation | 409 | `{"message":"This transaction violates a record, quantity or accounting constraint…"}` |
| Role not allowed | 403 | `{"message":"Your role cannot perform this action"}` |
| Missing/expired CSRF token | 403 | `{"message":"Access denied or session token expired. Refresh and try again."}` |
| Temporary password not yet replaced | 403 | `{"message":"Change your temporary password to continue","code":"PASSWORD_CHANGE_REQUIRED"}` |
| Not signed in, or session revoked | 401 | `{"message":"Please sign in"}` |
| Login rate limit | 429 | `{"message":"Too many sign-in attempts. Try again in one minute."}` |
| Password change attempt limit (V9) | 429 | `application/problem+json`; `detail` "Too many incorrect current passwords. Try again in N minutes." |

Clients should show `message`, falling back to `detail`. A lost response is resolved by retrying with the same key, not creating another logical transaction.

`/state` key `purchases` is one summary row per purchase order (supplier, status, total, `line_count`, aggregated item names as `item`, summed base `quantity`/`received`/`returned` with `uom` when all lines share one); it replaces the former single-item rows. MRP `incoming` = open base quantity on lines of APPROVED/PARTIAL orders.
`/state` key `bomCosts` (V7) carries the roll-up of every revision (`bom_id, unitCost, singleLevelCost, levels, depth, missingBoms, cycle, active, source_bom_id, source_revision, semi_finished`); `orders` rows carry `parent_order_id`; `mrp` rows keep their original columns and add the multi-level ones listed above (without `pegging`).

`/state` keys `sales` (one summary row per order: totals, line count, aggregated product names, summed base quantity/delivered/returned with `uom` when all lines share one) and `salesLines` (open lines of confirmed orders with `planned` quantity, for the manufacturing-order dialog; `quantity` is the base quantity, V9 adds `entered_quantity` and `unit`) replace the former single-item `sales` rows.

The workspace snapshot caps each operational list at 250 and BOM lines at 1,000. UI pagination applies inside those bounds; exports contain the loaded records. Lists move to cursor-based endpoints feature by feature. Since V8 the snapshot no longer contains the `audit` key (`GET /admin/audit`); V9 removed `users` (`GET /admin/users`), `receipts` (`GET /purchases/receipts`), `ledger` (`GET /inventory/ledger`) and `journal` (`GET /reports/journal`). The Manufacturing orders, Shop floor, Quality control and Costs & reports screens read `GET /manufacturing/orders`, `/quality/inspections`, `/reports/costs` and `/reports/journal`; `orders`, `inspections`, `lots`, `sales`, `purchases` remain for the dashboard, attention counts, pickers and exports.
