# HTTP contract

Base: `/api`, same origin. Machine-readable OpenAPI: `.openapi/openapi.json` in the repository root.

Authentication uses an HTTP-only session cookie. Fetch `GET /auth/csrf` to receive `{token,headerName}` and attach that header on every unsafe request, including login/logout. Form login is `POST /auth/login` with URL-encoded `email` and `password`; it answers `{authenticated:true, mfaRequired, mustChangePassword}` and records the account's last sign-in. V17: when the account has two-step sign-in on, `mfaRequired` is `true`, the last sign-in is not yet recorded and the session waits for the code step (see Two-step sign-in below). Only active accounts can sign in. Refresh the CSRF token after login/logout. `GET /auth/me` returns the authenticated identity and organization plus `mustChangePassword`, `lastLoginAt` and `passwordChangedAt`; V17 adds `mfaEnabled`, `mfaEnabledAt`, `mfaRequired` (the policy requires two-step sign-in for this account), `recoveryCodesLeft` (0 when off), `mfaPending` (the code step of this sign-in is still due) and `mfaSetupRequired` (a password session of an account the policy requires to use two-step sign-in, without it set up). The explicit demo profile additionally supports `POST /auth/demo?role=admin|planner|warehouse|operator|quality|maintenance|finance` (V24 adds `finance`); this returns 404 outside demo (409 when an administrator deactivated that persona).

Every authenticated request is revalidated against the account (`platform.SessionGuard`): a deactivated account, or a session older than the account's last password reset/change, is signed out (401 on that request); a role or name change made by an administrator applies to the user's next request. While `mustChangePassword` is true (after an administrator reset, and for accounts created through `POST /users`), every route except `/auth/me`, `/auth/csrf`, `/auth/config`, `/auth/logout`, `/auth/password` and `/health` answers 403 `{"message":"Change your temporary password to continue","code":"PASSWORD_CHANGE_REQUIRED"}`. V17 adds two more gates, checked before (`MFA_REQUIRED`) and after (`MFA_SETUP_REQUIRED`) the password-change gate; see Two-step sign-in.

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/password` | `{currentPassword, newPassword}` (16–128 characters, not the email address, not the current password); any signed-in role; clears `mustChangePassword`, signs out the user's other sessions and keeps this one (new session id); audited `PASSWORD_CHANGED`. Wrong current password → 409; after 5 wrong current passwords within 15 minutes the account's further attempts (even correct ones) get 429 until the oldest failure is 15 minutes old (V9; in memory per process, like the login limit); a successful change clears the failures |

All business mutations require a unique `Idempotency-Key` header; keep it across retries of the **same** request. Different payload/action with the same key returns 409. Body decimal values may be JSON strings to avoid browser floating-point conversions. Responses use six-decimal PostgreSQL numeric values; UI quantities are display-only. Record IDs are opaque strings. Tenant IDs in request JSON are not accepted or used for authorization.

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/state?days=7|14|30|90` | Tenant read snapshot; all signed-in roles; financial aggregates PLANNER/ADMIN (V24: and FINANCE); V24 `accounting` attention counts ADMIN/FINANCE only (`awaiting_approval`, `awaiting_my_approval`, `drafts`, `needs_review`, up to five `awaiting` journals) |
| GET | `/approval-policies` | V9: `{items:[{documentType, approvalRequired, amountThreshold, approverRoles}]}` for `MANUFACTURING_ORDER`, `PURCHASE_ORDER`, `STOCK_ADJUSTMENT` and (V24, last) `MANUAL_JOURNAL` (built-in default when none is stored); all roles, so screens show approve buttons to the approver roles |
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
| GET | `/reports/costs?status=&q=&limit=&before=` | V9: manufacturing cost register, newest first, keyset-paged: estimated, material, conversion, actual, WIP, finished and scrap cost per order; `status` `all`/`active`/a code, `q` matches number, product name/SKU. `{items, next, total, summary}`; `summary` = tenant-wide `{orders, estimated_cost, material_cost, conversion_cost, wip_cost, finished_cost, scrap_cost}`; ADMIN/PLANNER (V24: and FINANCE) |
| GET | `/reports/journal?account=&q=&from=&to=&postedFrom=&postedTo=&source=&limit=&before=` | V9: posted journal lines, newest entry first, debits before credits (keyset on entry `created_at, id`, line `debit, id`; `limit` default 50). `account` exact, `q` matches reference/description, `from`/`to` inclusive Asia/Colombo days the entry was recorded (400 when malformed, 409 when `to` < `from`). V24: `postedFrom`/`postedTo` filter the entries' posting dates (inclusive; 409 when reversed), `source` = `SYSTEM`, `MANUAL` or `REVERSAL` (case-insensitive; 409 otherwise); rows add `posting_date`, `source`, `reverses_entry_id` and the line `memo`. `{items, next, totals:{debit, credit}, accounts}` (`accounts` = posted account codes, first page only); ADMIN/PLANNER (V24: and FINANCE) |
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
| POST | `/items` | Create item (`RAW_MATERIAL`/`SEMI_FINISHED`/`FINISHED_GOOD`/`PACKAGING`/`SPARE_PART` (V11: maintenance stock, never a BOM component), any active tenant unit, optional `description`, `categoryId`, `reorderQuantity`, `leadTimeDays`); PLANNER |
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
| POST | `/users` | Create a bcrypt-backed tenant account `{email, name, password (16–128), role}` (roles ADMIN, PLANNER, WAREHOUSE, OPERATOR, QUALITY, SALES, VIEWER, MAINTENANCE); the account must choose its own password at first sign-in; an email already in use → 409; ADMIN |

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

### Maintenance and downtime (`/maintenance/*`, V11)

Date-times in bodies are ISO-8601 with an offset (`2026-11-25T08:30:00+05:30`, `…Z`); without an offset they are read as Asia/Colombo local time; 400 when malformed. Downtime may not start or end in the future (5 minutes of clock tolerance). Query dates `from`/`to` are inclusive Asia/Colombo calendar days. Lists return `{items, next}` (keyset; pass `next` as `before`; `limit` 1–200, default 25; 409 outside). All reads are open to every signed-in role of the tenant.

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/maintenance/work-orders?status=&type=&machine=&q=&limit=&before=` | Work orders, newest first. `status` = `OPEN` (REQUESTED, APPROVED, SCHEDULED, IN_PROGRESS) or one code; `type` CORRECTIVE/PREVENTIVE; `q` matches number, title, machine code. `{items, next, counts}` (`counts` per status, tenant-wide). Rows: number, type, status, priority, title, machine, plan, due date, window, assignee, requester/approver names, timestamps, `labour_hours, labour_cost, parts_cost, total_cost`, checklist progress, `open_downtime` |
| GET | `/maintenance/work-orders/{id}` | `{order, checklist, parts, labour, downtime, journal, events}`; `parts` rows carry `returnable` for issues |
| POST | `/maintenance/work-orders` | Corrective request `{machineId, title, description?, priority? (NORMAL/HIGH/URGENT; default HIGH with a breakdown), breakdown, downtimeStartedAt?, downtimeEventId?, manufacturingOrderId?}`. `breakdown: true` also opens a BREAKDOWN (unplanned) downtime event linked to the order (409 when the machine already has open downtime: link it with `downtimeEventId` instead); OPERATOR/PLANNER/MAINTENANCE |
| POST | `/maintenance/work-orders/{id}/approve` | `{scheduledStart?, scheduledEnd?, assigneeId?}`: REQUESTED → APPROVED, or SCHEDULED with a window; a different person than the requester (409 also for ADMIN); PLANNER |
| POST | `/maintenance/work-orders/{id}/schedule` | `{scheduledStart, scheduledEnd, assigneeId?}`: APPROVED/SCHEDULED → SCHEDULED (window ≤ 31 days); PLANNER |
| POST | `/maintenance/work-orders/{id}/start` | APPROVED/SCHEDULED → IN_PROGRESS. 409 while an MO is IN_PROGRESS/PARTIALLY_COMPLETED on the machine (pause it first) or another order is in progress there. Machine → MAINTENANCE; the work is covered by open downtime (the order's own, an unlinked open breakdown, or a new event: PLANNED_MAINTENANCE for preventive/scheduled work, BREAKDOWN for unscheduled corrective work; any other open event of the machine is closed at the start); MAINTENANCE |
| POST | `/maintenance/work-orders/{id}/progress` | `{checklist?:[{index, result: PASS/FAIL/NA, note}], findings?, actions?}` while IN_PROGRESS; MAINTENANCE |
| POST | `/maintenance/work-orders/{id}/labour` | `{hours (±, 2 dp), technicianId?, notes?}` while IN_PROGRESS, costed at the tenant labour rate; posts Dr `MAINTENANCE_EXPENSE` / Cr `MAINTENANCE_LABOUR_CLEARING` (reversed for negative hours; total never below 0); closed period → 409; MAINTENANCE |
| POST | `/maintenance/work-orders/{id}/parts` | `{itemId, quantity, notes?}`: issues a `SPARE_PART` item from released, unreserved lots, oldest first (ledger `MAINTENANCE_ISSUE`; Dr `MAINTENANCE_EXPENSE` / Cr the item's inventory account); APPROVED/SCHEDULED/IN_PROGRESS; returns `{movements, value}`; MAINTENANCE |
| POST | `/maintenance/work-orders/{id}/parts/{movementId}/return` | `{quantity, notes?}`: unused parts of one issue back into its lot at the issue's unit value (ledger `MAINTENANCE_RETURN`, reversed journal); before completion; MAINTENANCE |
| POST | `/maintenance/work-orders/{id}/complete` | `{findings, actions?, checklist?, meterReading?}`: every checklist item needs a result; machine → IDLE, the order's open downtime closes now; a preventive order reschedules its plan to today + interval; MAINTENANCE |
| POST | `/maintenance/work-orders/{id}/cancel` | `{reason}` before work starts and after every issued part is returned; cancelling a preventive order skips that occurrence (plan due date + one interval); PLANNER |
| GET | `/maintenance/plans?machine=&due=&horizon=&active=&limit=&before=` | Plans by next due date (keyset on `next_due_date, id`). `due` = `overdue` (before today) or `due` (≤ today + `horizon`, default 7); `active` `true` (default), `false` or `all`. Rows add `days_until_due`, `due_state` (OVERDUE/DUE/UPCOMING/INACTIVE), `open_order`, `meter_reading`, `checklist`; `{items, next, counts:{overdue, due_soon, active}, today}` |
| GET | `/maintenance/plans/{id}` | `{plan, orders (last 20), events}` |
| POST | `/maintenance/plans`, `/maintenance/plans/{id}` | Create `{machineId, title, description?, checklist:[text], intervalDays (1–3650), intervalHours?, nextDueDate, estimatedHours?, priority?}` / update (same without `machineId`, plus `active`); PLANNER |
| POST | `/maintenance/plans/generate` | `{horizonDays (0–31)}`: one PREVENTIVE order per active plan due within the horizon, SCHEDULED on the due date at 08:00 Asia/Colombo (or the next full hour once that passed) for the plan's estimated hours, approved by the caller, checklist copied. Never while the plan has an open order; unique per plan and due date (DB index). `{created, skipped}`; PLANNER |
| GET | `/maintenance/downtime?machine=&reason=&from=&to=&status=&history=&limit=&before=` | Downtime events, newest start first; effective events only unless `history=true` (adds SUPERSEDED/VOID). Rows: number, machine, reason, planned, start/end, status, `minutes` (open: until now), MO/work order links, `corrects`/`corrected_by`, reporter/closer |
| POST | `/maintenance/downtime` | `{machineId, reason, planned?, startedAt?, endedAt?, manufacturingOrderId?, notes?}`; without `endedAt` the event stays OPEN (one per machine). Reasons: BREAKDOWN (always unplanned), PLANNED_MAINTENANCE (always planned), CHANGEOVER (default planned), MATERIAL_SHORTAGE, NO_OPERATOR, QUALITY_HOLD, UTILITIES, OTHER (default unplanned). Overlap with another effective event of the machine → 409; OPERATOR/PLANNER/MAINTENANCE |
| POST | `/maintenance/downtime/{id}/close` | `{endedAt?, notes?}` (default now); refused while the linked work order is in progress; OPERATOR/PLANNER/MAINTENANCE |
| POST | `/maintenance/downtime/{id}/correct` | `{reason, planned?, startedAt, endedAt, notes?, correctionReason, withdraw}`: a CLOSED event becomes SUPERSEDED and the corrected event (CLOSED, or VOID with `withdraw`) is recorded with `corrects_id`; PLANNER |
| GET | `/maintenance/machines`, `/maintenance/machines/{id}` | Every machine with open downtime, open/in-progress orders, next PM due and overdue plans; one machine with `{machine, openDowntime, orders, plans, meter, runningOrders, metrics (last 30 days), history (15)}` |
| GET | `/maintenance/machines/{id}/history?limit=&before=` | Downtime events, work orders, status changes and meter readings of a machine, newest first (`kind`, `at`, `reference`, `code`, `status`, `detail`, `value`, `flag`, `actor`, and V16 `source`: for STATUS rows MAINTENANCE_ORDER, MANUAL, OPERATION or ORDER — production changes carry the order number in `reference` — null for other kinds) |
| POST | `/maintenance/machines/{id}/status` | `{status: IDLE/RUNNING, reason}`: a planner's correction; 409 while a work order is in progress, and RUNNING while downtime is open; MAINTENANCE is entered only by starting an order; PLANNER |
| POST | `/maintenance/machines/{id}/meter` | `{reading, notes?}`: run-hour meter (never backwards); a run-hour plan whose interval is reached since its last service is due today; returns `{plansDue}`; MAINTENANCE/PLANNER/OPERATOR |
| GET | `/maintenance/metrics?from=&to=&machine=` | Default the last 30 days (≤ 366). `{from, to, periodMinutes, machines:[{id, code, events, failures, downtimeMinutes, plannedMinutes, unplannedMinutes, workingMinutes, operatingMinutes, mttrMinutes, mtbfHours}], pareto:[{reason, minutes, events, share, cumulative}], totals}`. Durations are clipped to the period (open events run to now); failures = BREAKDOWN events starting in the period; MTTR = mean full length of closed failures (minutes); V16: `workingMinutes` = the work center's shift working time in the period (`routing.CalendarService`; around the clock without a calendar), `operatingMinutes` = working time − the downtime inside it, MTBF = operating minutes / 60 / failures (hours, null without failures; the fleet figure sums operating time over all failures). `periodMinutes` and downtime minutes stay calendar time |
| GET | `/maintenance/spare-parts`, `/maintenance/technicians` | Active spare parts with released available stock; active MAINTENANCE/ADMIN users (assignees) |
| GET / POST | `/maintenance/settings` | `{labourRate}` (LKR per hour, default 1,500; `customized`, `updatedAt`, `updatedBy`); set is ADMIN only |

```json
{"machineId":"opaque-machine","title":"Hydraulic pressure drops","description":"","priority":"URGENT","breakdown":true,"downtimeStartedAt":"2026-11-25T07:40:00+05:30","downtimeEventId":null,"manufacturingOrderId":null}
```

```json
{"machineId":"opaque-machine","reason":"CHANGEOVER","planned":null,"startedAt":"2026-11-24T13:00:00+05:30","endedAt":"2026-11-24T13:40:00+05:30","manufacturingOrderId":null,"notes":"Mould change"}
```

`/state` key `maintenance` (order 2100): `{open_breakdowns, open_downtime, overdue_plans, awaiting_approval, in_progress, breakdowns (≤ 5 open BREAKDOWN events), overdue (≤ 5 overdue plans)}` for the overview and attention center.

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

### Genealogy and recalls (`/traceability/*`, `/recalls/*`, V14)

Every signed-in role may trace and read recalls; recall commands are QUALITY (ADMIN also allowed). The trace engine
(`traceability.TraceService`) walks the `lot_genealogy_links` view with depth-limited, cycle-safe recursive CTEs.

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/traceability/search?q=` | Entry points matching `q` (≥ 2 characters): lots (number), manufacturing orders, goods receipts (GRN number), purchase orders (approved or later), sales orders (number or customer reference), delivery references, suppliers (name) — `{items:[{type, id, label, detail}]}`, a few of each type |
| GET | `/traceability/trace?type=&id=&direction=&depth=&from=&to=` | The genealogy around an entry point. `type` LOT (default; `id` = lot id or number), MO (id or number), RECEIPT (GRN number), PO, SO, DELIVERY (delivery reference) or SUPPLIER (+ receipt dates `from`/`to`, Asia/Colombo, default the last 90 days); `direction` BOTH (default)/BACKWARD/FORWARD; `depth` 1–30 lot-to-lot hops (default 12). Returns `{root, direction, depth, truncated, nodes, edges, tree:{backward, forward}, summary}` (see below); 404 for another tenant's record |
| GET | `/recalls?status=&severity=&q=&limit=&before=` | Recalls, newest first, keyset-paged; `status` `all`/`active` (OPEN + CONTAINED)/a status, `q` matches number, title, reason and suspect lot numbers; rows add `pending_customers`, `suspect_lots`, `created_by_name`, `overridden` |
| GET | `/recalls/{id}` | Header (defect, supplier, item, user names) + `suspects`, `lots` (snapshot `quantity`/`lot_status`, `relation` SUSPECT/DOWNSTREAM/SOURCE/SIBLING, `depth`, `containment`, `ncr`/`ncr_status`, live `current_status`/`current_quantity`), `shipments`, `customers` (contact, notification), `closeCheck {pendingCustomers, unsettledLots, releasedSinceContainment}` |
| GET | `/recalls/{id}/notifications/export` | Notification list as CSV (UTF-8 BOM; one row per affected delivery with the customer's contact and notification status); the download is audited (`RECALL_NOTIFICATIONS_EXPORTED`) |
| POST | `/recalls` | Open `{title, reason, severity, scope FORWARD\|FORWARD_AND_SIBLINGS, basis LOTS\|SUPPLIER, lotIds?, supplierId?, itemId?, receivedFrom?, receivedTo?, defectCode, depth?}`: LOTS takes 1–200 suspect lots; SUPPLIER takes the supplier and receipt dates (409 when nothing was received). Computes and stores the affected set; returns `{id, number, status, suspectLots, affectedLots, inStockLots, shipments, customers, truncated}`; QUALITY |
| POST | `/recalls/{id}/refresh` | Recompute the affected set of an OPEN recall (supplier recalls also re-resolve their suspect lots); QUALITY |
| POST | `/recalls/{id}/contain` | OPEN → CONTAINED: recompute, then raise a `source RECALL` NCR (recall severity and defect code, quantity = the lot's balance) through `quality.NcrService#raise` on every affected **released** lot in stock — the NCR puts it on hold. Lots already quarantined/rejected (ALREADY_HELD), under an open NCR (UNDER_NCR) or awaiting an inspection (AWAITING_INSPECTION) are skipped and listed. The snapshot is frozen afterwards. Returns `{heldLots, skippedLots, …}`; QUALITY |
| POST | `/recalls/{id}/customers/{customerId}/notify` | `{notifiedOn, contact, notes?}`: PENDING → NOTIFIED (once, never changed); CONTAINED recalls only; the date may not be in the future; QUALITY |
| POST | `/recalls/{id}/close` | `{note?, overrideReason?}`: CONTAINED → CLOSED when every customer is notified and every lot the recall held has its RECALL NCR DISPOSITIONED or CLOSED. Otherwise `overrideReason` is required and the closing user must differ from the one who opened the recall (also a database `CHECK`); QUALITY |
| POST | `/recalls/{id}/cancel` | `{reason}`: OPEN → CANCELLED (nothing was held); QUALITY |

Trace payload: `nodes[]` carry `id` (`lot:…`, `mo:…`, `rw:…`, `receipt:…`, `delivery:…`, `origin:…`, `out:<kind>:<reference>`,
and for document roots `grn:…`, `po:…`, `so:…`, `dref:…`, `supplier:…`), `kind` (LOT, MANUFACTURING_ORDER, REWORK_ORDER,
RECEIPT, DELIVERY, SUPPLIER_RETURN, SCRAP, MAINTENANCE, ADJUSTMENT, OPENING, FOUND, GOODS_RECEIPT, PURCHASE_ORDER,
SALES_ORDER, DELIVERY_NOTE, SUPPLIER), `label`, `detail`, `level` (root 0, backward negative, forward positive: the
left-to-right column), `side` (ROOT/BACKWARD/FORWARD), `ref` and kind-specific fields (lots: item, warehouse, `onHand`,
`status`, `openNcr`, `more` = links beyond the depth limit; orders: status and quantities; receipts: PO and supplier;
deliveries: sales order, customer, quantity, returned). `edges[]` always point in the direction the material moved:
`{from, to, kind RECEIVED|ISSUED|PRODUCED|TRANSFERRED|REWORK_INPUT|REWORK_MATERIAL|REWORKED|DELIVERED|RETURNED|SUPPLIER_RETURN|SCRAP|MAINTENANCE|ADJUSTMENT|OPENING|FOUND, quantity, uom, at, reference}`.
`tree.backward`/`tree.forward` are pre-order rows `{node, indent, edge, quantity, uom, at, reference, repeated}` (a node
reached again is listed once more as `repeated` and not expanded). At most 2,000 lots per direction (`truncated`).

`/state` key `recalls` (order 2400, every role): `{open, contained, pending_customers, active:[{id, number, title, severity, status, created_at, pending_customers}]}` (pending customers counted on contained recalls; up to five active recalls, most severe first).

```json
{"title":"rPET resin LPT-R2611-31 · non-food-grade flake","reason":"Supplier notice","severity":"CRITICAL",
 "scope":"FORWARD","basis":"LOTS","lotIds":["<lot id>"],"supplierId":null,"itemId":null,"receivedFrom":null,
 "receivedTo":null,"defectCode":"CONT","depth":null}
```

### Administration (`/admin/*`, ADMIN only; accounting periods also FINANCE since V24)

| Method | Path | Purpose |
|---|---|---|
| GET | `/admin/users?q&role&status&limit&before` | Users by name (keyset on `lower(name), id`); `q` matches name/email, `status` `ACTIVE`/`INACTIVE`. Rows: `id, email, name, role, active, created_at, last_login_at, password_changed_at, deactivated_at, must_change_password, mfa_enabled, mfa_enabled_at` (the last two V17); `summary` = `{total, active, inactive, admins, pending_password}` |
| POST | `/admin/users/{id}` | `{name, role}`; a role change applies to the user's next request; the last active ADMIN cannot be demoted (409); audited `USER_UPDATED` |
| POST | `/admin/users/{id}/deactivate`, `/reactivate` | Deactivation blocks sign-in and signs out open sessions; not yourself, not the last active ADMIN (409); audited |
| POST | `/admin/users/{id}/reset-password` | Issues a one-time temporary password, returned once as `temporaryPassword` (an idempotent replay returns `null`; the password is never stored in clear text); the user must change it at the next sign-in and open sessions end; not for yourself; audited `USER_PASSWORD_RESET` |
| POST | `/admin/users/{id}/reset-mfa` | V17: turns off another user's two-step sign-in (lost phone): removes the encrypted secret and every recovery code and signs out the user's open sessions; returns the user row (`mfa_enabled: false`). Not for yourself (409, use the profile menu), 409 when two-step sign-in is not on, 404 for a user of another tenant; an administrator must set it up again at the next password sign-in; audited `USER_MFA_RESET` |
| GET | `/admin/approval-policies` | `{items}`: one policy per `MANUFACTURING_ORDER`, `PURCHASE_ORDER`, `STOCK_ADJUSTMENT` (the operational documents; the V24 `MANUAL_JOURNAL` policy is read from `GET /accounting/policy` and `GET /approval-policies`) with `approvalRequired, amountThreshold, approverRoles, customized, wired, updatedAt, updatedBy` (`wired` is true for all three since V9) |
| POST | `/admin/approval-policies/{documentType}` | `{approvalRequired, amountThreshold (null = every document), approverRoles ⊆ PLANNER, WAREHOUSE, QUALITY, SALES, OPERATOR}`; ADMIN always approves, a creator never approves their own document; audited `APPROVAL_POLICY_UPDATED`. In effect for every document type (V9): stock adjustments and count variances when posted (gross value), manufacturing orders when created (estimated cost), purchase orders when submitted (order total). A document at or below the threshold, or any document while approval is off, takes effect directly (`MO_AUTO_APPROVED` / `PO_AUTO_APPROVED` / posted adjustment); approve rights always follow the current approver roles, so a document already waiting is never stranded. V24: `documentType` may be `MANUAL_JOURNAL` (manual journals when submitted, amount = total debits; approver roles ⊆ `FINANCE`, 409 otherwise); `FINANCE` is refused (409) as an approver of the other documents |
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

Accounting periods: a period ends on `end_date` and starts the day after the previous period's end. System postings are dated today (V24: `posting_date`, the Asia/Colombo date) and `Store.openPeriod` rejects them while a closed period covers the current date, so closing a past period locks it and closing a period that ends today stops all postings for the rest of the day. V24: manual journals and reversals carry their own posting date, which must be after the latest closed period's end (checked by the service and by the database trigger `journal_entries_open_period`). ADMIN and FINANCE close and reopen periods.

### Routings, shift calendars, skills and operations (V10)

Every role reads these endpoints (the user skill matrix is ADMIN/PLANNER); writes are PLANNER (ADMIN also allowed) except the operation commands (OPERATOR/PLANNER). All writes take an `Idempotency-Key`. Times are local Asia/Colombo `HH:mm`; weekdays are ISO numbers (1 = Monday).

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/routings?status=&productId=&q=&limit=&before=` | Routing revisions, newest first, keyset-paged. `status` `all` (default), `DRAFT`, `APPROVED` or `current`; `q` matches number, revision, product SKU/name. `{items, next, total, counts:{total, draft, approved, current}}`; rows add `sku`, `product`, `uom`, `current`, `operation_count`, summed `setup_minutes` and `run_minutes_per_unit`, author/approver names |
| GET | `/routings/{id}` | `{routing, operations, revisions, orders, events}`: operations in sequence with work center (`machine_code`, `machine_kind`), skill (`skill_code`, `skill_level`) and `alternates:[{id, code, name, status}]`; `revisions` of the same product; `orders` = manufacturing orders that snapshotted it |
| GET | `/routings/compare?from=&to=` | Operations of two revisions of one product matched by sequence: `{from, to, operations:[{seq, status: ADDED/REMOVED/CHANGED/SAME, from, to, changes}], summary}` |
| POST | `/routings` | Draft `{productId, revision, notes?, operations:[{seq, name, machineId, alternateMachineIds?, setupMinutes, runMinutesPerUnit, crewSize, skillId?, skillLevel?, inspectionPoint?, instructions?}]}` for an active FINISHED_GOOD/SEMI_FINISHED product; revision unique per product (case-insensitive); sequences unique; alternates of the same machine kind as the primary; required skills active; PLANNER |
| POST | `/routings/{id}` | Replace a DRAFT's `{revision, notes, operations}` (approved revisions → 409); PLANNER |
| POST | `/routings/{id}/approve` | DRAFT → APPROVED by a person other than its creator and last editor (ADMIN included; 409 otherwise); at least one operation, active product and skills; the revision becomes the product's current routing; PLANNER |
| POST | `/routings/{id}/copy` | `{revision}`: an APPROVED revision copied into a new DRAFT (operations and alternates, `source_routing_id`); PLANNER |
| POST | `/routings/{id}/make-current` | An APPROVED revision becomes the product's current routing (what new manufacturing orders snapshot); PLANNER |
| GET | `/calendars` | `{calendars, patterns, workCenters}`: calendars with `pattern_ids`/`pattern_codes`, `assigned`, `upcoming_exceptions`; patterns with `weekday_list`, `paid_minutes`, `overnight`; every work center with its assigned and effective calendar and `source` (ASSIGNED, PLANT_DEFAULT, NONE) |
| GET | `/calendars/{id}?from=&to=` | `{calendar, patterns, exceptions, workCenters}`; exceptions dated `from`..`to` (default today − 30 days .. today + 365) |
| GET | `/calendars/available-minutes?machineId=&from=&to=` | Available minutes per day `from`..`to` (inclusive, at most 62 days) of one work center or (without `machineId`) all: `{from, to, workCenters:[{machine_id, code, calendar_id, calendar_code, source, minutes, days:[{date, minutes, closed, closedReason, intervals:[{start, end, kind, label}]}]}]}`; same rules as `routing.CalendarService` |
| POST | `/calendars/patterns`, `/calendars/patterns/{id}` | Create `{code, name, startTime, endTime, breakStart?, breakMinutes, weekdays}` / update (code fixed). `endTime <= startTime` = overnight; the break must lie inside the shift (409); `breakStart` empty = centred; PLANNER |
| POST | `/calendars`, `/calendars/{id}` | Create `{code, name, patternIds, plantDefault}` / update `{name, patternIds, plantDefault}`; `plantDefault` replaces the tenant's current plant default; PLANNER |
| POST | `/calendars/{id}/exceptions` | `{date, kind: CLOSED/OVERTIME, startTime?, endTime?, reason}`: a holiday (one per date) or overtime (start and end required, end ≤ start = next day); PLANNER |
| POST | `/calendars/{id}/exceptions/{exceptionId}/remove` | Remove a holiday or overtime; PLANNER |
| POST | `/calendars/assignments` | `{machineId, calendarId?}`: the work center's calendar; empty `calendarId` returns it to the plant default; PLANNER |
| GET | `/skills` | Catalogue `{skills}` with `holders`, `expired`, `expiring` (within 30 days), `routings` requiring it |
| GET | `/skills/users?q=&limit=&before=` | Active users by name (keyset) with `skills:[{skill_id, code, level, certified_until, expired}]`; ADMIN/PLANNER (403 otherwise) |
| GET | `/skills/mine` | The signed-in user's skills |
| POST | `/skills`, `/skills/{id}` | Create `{code, name, description?}` (code unique, upper-cased) / update `{name, description, active}`; PLANNER |
| POST | `/skills/assignments` | `{userId, skillId, level (1–5), certifiedUntil?, notes?}`: creates or replaces a user's skill (active user and skill); `certifiedUntil` is the last valid day; PLANNER |
| POST | `/skills/assignments/remove` | `{userId, skillId}`; 409 when the user does not hold it; PLANNER |
| GET | `/manufacturing/{id}/operations` | `{routing, operations, logs}` of an order (`routing` null and no operations for an order without a routing). Operation rows: plan (`seq`, `name`, `machine_id`, `planned_machine_id`, `planned_quantity`, `setup_minutes`, `run_minutes_per_unit`, `planned_run_minutes`, `planned_minutes`, `crew_size`, skill, `inspection_point`, `instructions`), execution (`status`, `activity`, `good_qty`, `scrap_qty`, `operator_id`, `ready_at`, `started_at`, `finished_at`) and `actual_setup_minutes`, `actual_run_minutes`, `actual_labour_minutes` (closed time logs), `open_since` |
| GET | `/manufacturing/orders?…&routing=` | V10 addition to the V9 list: `routing` `none` (orders without operations: the shop floor's order cards) or `routed`; rows add `operation_count` |
| GET | `/operations/work-centers` | Every work center with `ready`, `running`, `paused` routed-operation counts, `legacy_orders` (open orders without a routing) split by V16 into `legacy_running` (in progress or partially completed), `legacy_paused` (on hold) and `legacy_ready` (released): each such order is one implicit operation, and the Shop floor adds them to the chip counts; `available_minutes_today`, `calendar_code`, `calendar_source` |
| GET | `/operations/queue?machineId=&limit=&before=` | The work center's IN_PROGRESS, PAUSED and READY operations plus READY operations it may run as the routing work center or an alternate (`alternate: true`); order: running → paused → ready, then priority (URGENT, HIGH, NORMAL), due date, order age, sequence; keyset-paged (default 24). Rows add the order (`order_number`, `order_status`, `priority`, `due_date`, `product`, `uom`), `input_qty` (previous operation's good quantity, or the order quantity), `operation_count`, `operation_position` (V16: 1-based position among the order's non-cancelled operations, shown as "Step 2 of 2 · Op 20") and the caller's `skill_check` (QUALIFIED, MISSING, EXPIRED, LEVEL_TOO_LOW, NOT_REQUIRED) |
| GET | `/operations/{id}` | `{operation, order, logs, reports, machines}`; `machines` = the work centers the operation may start on |
| GET | `/operations/time-logs?machineId=&orderId=&operationId=&limit=&before=` | Time log rows newest first (keyset on `started_at, id`): `activity` (SETUP/RUN), `started_at`, `ended_at`, `end_reason`, `minutes` (machine), `labour_minutes`, `crew_size`, `user_name`, `skill_override_reason` |
| POST | `/operations/{id}/start` | `{machineId?, activity?, overrideReason?}`: READY → IN_PROGRESS on the planned work center or an alternate; refused while the work center is MAINTENANCE or already runs an operation, while the order is not RELEASED/IN_PROGRESS/PARTIALLY_COMPLETED (ON_HOLD: resume it first), or when the user lacks the required skill (PLANNER/ADMIN may override with `overrideReason`, recorded and audited `OP_SKILL_OVERRIDDEN`). Default `activity`: SETUP when setup minutes are planned and none were logged, else RUN. The first operation of a RELEASED order starts the order (MO_START). Opens a time log; IDLE work center → RUNNING; OPERATOR/PLANNER |
| POST | `/operations/{id}/pause` | `{reason?}`: IN_PROGRESS → PAUSED; closes the time log; the work center becomes IDLE when nothing else runs there; OPERATOR/PLANNER |
| POST | `/operations/{id}/resume` | `{activity?, overrideReason?}`: PAUSED → IN_PROGRESS (same checks as start; default activity = the paused one); OPERATOR/PLANNER |
| POST | `/operations/{id}/setup-done` | IN_PROGRESS in SETUP: closes the SETUP log, opens a RUN log; OPERATOR/PLANNER |
| POST | `/operations/{id}/report` | `{goodQty, scrapQty, notes?}` on a running or paused operation: whole pieces for `pcs`, scrap needs notes, cumulative good + scrap ≤ input; OPERATOR/PLANNER |
| POST | `/operations/{id}/finish` | `{goodQty, scrapQty, notes?}` (zero when already reported; the operation needs some reported quantity): → DONE, closes the time log, the next operation becomes READY, the work center IDLE when free; OPERATOR/PLANNER |

Operations lifecycle: an order created for a product with a current approved routing snapshots it (`routing_id`, `routing_revision`, PENDING operations with planned minutes for the order quantity; the order's work center replaces the routing's work center where it is that work center or an alternate). Issue (RELEASED) makes the first operation READY; finishing one makes the next READY (strictly sequential, no overlap). Pausing the order (ON_HOLD) pauses its running operations; cancelling it cancels them. Output (`POST /manufacturing/{id}/complete`) stays the stock and cost posting: for a routed order the cumulative good output cannot exceed the last operation's good quantity and the final output needs every operation DONE (409). Orders without a routing behave exactly as before V10. Operations never post stock or journals.

```json
{"productId":"opaque-bottle","revision":"R01","notes":"","operations":[
 {"seq":10,"name":"Extrusion blow moulding","machineId":"bm01","alternateMachineIds":["bm02"],"setupMinutes":"45","runMinutesPerUnit":"0.05","crewSize":"1","skillId":"opaque-skill","skillLevel":2,"inspectionPoint":false,"instructions":"Check wall thickness"},
 {"seq":20,"name":"Label, inspect & pack","machineId":"pk01","alternateMachineIds":[],"setupMinutes":"15","runMinutesPerUnit":"0.02","crewSize":"2","skillId":null,"skillLevel":null,"inspectionPoint":true,"instructions":""}]}
```

```json
{"code":"NIGHT","name":"Night shift","startTime":"22:00","endTime":"06:00","breakStart":"02:00","breakMinutes":30,"weekdays":[1,2,3,4,5]}
```

### Finite-capacity scheduling (`/schedule/*`, V13)

Writes are PLANNER (ADMIN also allowed) and take an `Idempotency-Key`. ADMIN and PLANNER read every run; other roles read only the PUBLISHED run (`/runs/{id}`, `/gantt`, `/late`, `/capacity`; 403 for drafts and superseded runs), `GET /schedule`, `/schedule/next` and `/schedule/orders/{id}`. Times in responses are UTC ISO-8601 instants; query dates `from`/`to` are inclusive Asia/Colombo calendar days (at most 42). Scheduling rules: see the backend README (`scheduling` package) and `ScheduleEngine`.

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/schedule` | `{published, stale, draft, pins, defaults, now}`: the published run header (null when none); V16 `stale` = `{changes, last_change_at}` (null without a published run), the changes a new run would see since the published run was created (see the `schedule` state key); the newest draft and the active pin count (planners only), the run defaults (`horizonDays` 28, `breakdownHours` 8, `useAlternates` true, `includeDrafts` false) |
| GET | `/schedule/runs?status=&limit=&before=` | Runs newest first (keyset on `created_at, id`; `status` DRAFT/PUBLISHED/SUPERSEDED/DISCARDED): number, status, horizon (`horizon_start`, `horizon_end`, `horizon_days`), parameters, `base_run_id`/`base_number`, counts (`orders_count`, `operations_count`, `late_orders`, `unscheduled_orders`, `pins_honoured`, `pin_conflicts`), `makespan_end`, creator/publisher names and times; ADMIN/PLANNER |
| GET | `/schedule/runs/{id}` | `{run, orders, pins}`: every order the run considered in scheduling sequence (`status` SCHEDULED / NO_ROUTING / BEYOND_HORIZON / NO_OPEN_WORK, `projected_start`, `projected_end`, `due_end`, `late`, `lateness_minutes`, `remaining_minutes`, `warnings`, the current order status, `estimate_minutes` used and `current_estimate`), and the pinned operations with `pin_status` HONOURED/CONFLICT and `pin_note` |
| GET | `/schedule/runs/{id}/gantt?from=&to=` | Default: the run's first 7 days. `{run, from, to, now, machines}`; per work center (by code): `working` intervals and `closed` dates (live `routing.CalendarService`), `blocks` the run avoided (MAINTENANCE windows and DOWNTIME, `assumed_end`), `bars` overlapping the window: order, operation (`operation_id` null = the estimated run of an order without a routing), `start_at`, `end_at`, `minutes`, `segments` (working intervals), `late`, `alternate`, `running`, `pin_status`, the current status |
| GET | `/schedule/runs/{id}/late` | `{run, items}`: late orders, latest first, with `projected_end`, `due_date`, `lateness_minutes` (for BEYOND_HORIZON: at least horizon end − due) |
| GET | `/schedule/runs/{id}/capacity?from=&to=` | Default: the run's horizon (≤ 42 days). Per work center and day: `calendar_minutes`, `blocked_minutes` (run blocks inside working time), `available_minutes`, `scheduled_minutes`, `load_pct`, `overloaded` (scheduled > available: only when working time was removed after the run), `closed`; totals per work center |
| GET | `/schedule/runs/{id}/compare?with=` | Against `with`, else the run published when this one was created, else the current published run: `{run, base, summary:{moved, new, dropped, newly_late, no_longer_late, later, earlier}, orders:[{change, delta_minutes, …}], operations:[changes only: MOVED/NEW/DROPPED with work center and start in both runs]}`; ADMIN/PLANNER |
| GET | `/schedule/next?machineId=&limit=` | The published run's operations on a work center that have not ended and are still open, by scheduled start (limit 1–50, default 10): `{run, machine, items}`; every role |
| GET | `/schedule/orders/{id}` | `{published, draft}`: the order's projection (status, projected start/end, lateness, operations with work center and times) in the published run and, for planners, the newest draft |
| GET | `/schedule/pins` | Active pins with order, operation, work center, start, reason, author; ADMIN/PLANNER |
| POST | `/schedule/runs` | `{horizonDays? (1–90), useAlternates?, breakdownHours? (1–168), includeDrafts?, notes?}`: schedules every open operation from now into a new DRAFT run (orders APPROVED … PARTIALLY_COMPLETED, ON_HOLD included; DRAFT orders only with `includeDrafts`); returns `{id, number, status, orders, operations, lateOrders, unscheduledOrders, pinsHonoured, pinConflicts}`; PLANNER |
| POST | `/schedule/runs/{id}/publish` | The newest DRAFT becomes PUBLISHED; the previous PUBLISHED run becomes SUPERSEDED (`superseded_by_run_id`) and older drafts DISCARDED. 409 for a run that is not a draft or when a newer run exists; PLANNER |
| POST | `/schedule/runs/{id}/discard` | DRAFT → DISCARDED; PLANNER |
| POST | `/schedule/pins` | `{orderId?, operationId?, machineId, start, reason?, rerun}`: pins a PENDING/READY operation (or, with `orderId` only, the estimated run of an order without a routing that has not started) to an allowed work center (current, routing or alternate) and start (`yyyy-MM-ddTHH:mm` Asia/Colombo or ISO with offset; moved forward to the next free working minute). Validated by scheduling with it: 409 when the start has passed, is beyond the horizon, the work center cannot run the operation, a predecessor or child order ends later, it overlaps another pin, or another pin would be displaced. Replaces the operation's active pin. `rerun` also creates a DRAFT (`run` in the response); PLANNER |
| POST | `/schedule/pins/{id}/remove` | `{rerun}`: removes an active pin (kept with `removed_at`); 409 when already removed; PLANNER |
| POST | `/schedule/estimates` | `{orderId, minutes (1–525600), notes?}`: remaining-duration estimate of an open order without a routing (409 for routed or closed orders); PLANNER |
| POST | `/schedule/estimates/{orderId}/remove` | Removes the estimate (and the order's pin); 409 when there is none; PLANNER |

```json
{"horizonDays":28,"useAlternates":true,"breakdownHours":8,"includeDrafts":false,"notes":"Week 48"}
```

```json
{"orderId":null,"operationId":"opaque-operation","machineId":"pk01","start":"2026-11-27T14:00","reason":"Customer collection slot","rerun":true}
```

`/state` key `schedule` (V13, every role): `{published: {id, number, created_at, published_at, horizon_start, horizon_end} | null, late_orders, unscheduled_orders, late: [≤ 5 rows: order_id, number, product, priority, due_date, projected_end, lateness_minutes, status], changes_since_publish, last_change_at}`; counts only orders that are still open. V16 stale-schedule hint: `changes_since_publish` counts audited changes after the published run was created (its results are frozen then) that a new run would see — order lifecycle and output, operation start/pause/resume/setup done/report/finish and duration estimates of orders still open or in the run; maintenance work orders approved, scheduled, generated, started, completed or cancelled whose window ends after the run's horizon start (started/completed orders without a window count too); downtime reported, closed, corrected or withdrawn unless it ended before the horizon start; calendars, holidays/overtime, shift-pattern edits, calendar assignments, new work centers and pins. It is tenant-scoped, read from the audit trail (index `audit_tenant_created_id`), 0 without a published run, and a hint only: nothing is re-scheduled automatically.

### Performance: OEE and utilization (`/performance/*`, V15)

Reads are open to every signed-in role of the tenant; closing days is PLANNER (ADMIN also allowed). Dates are Asia/Colombo business days (`yyyy-MM-dd`, inclusive); a range is at most 92 days, default the last 14 days ending today, and never extends past today (today is computed so far). Ratios are `0..1` (6 dp) and `null` when not measurable; minutes have 4 dp. The definitions are in [ARCHITECTURE.md](../architecture/ARCHITECTURE.md#adr-007-oee-method-v15).

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/performance/oee?from=&to=&machine=&bucket=day\|week` | `{from, to, today, bucket, method, periods:[{key, from, to, label, partial}], plant, series, workCenters, coverage, warningTotal, warnings}`. `plant` = time-weighted roll-up of every work-center day in range (`oee, availability, performance, quality, utilization, capacityUtilization, timeCoverage, outputCoverage`, minutes `calendar/scheduled/plannedStop/plannedAll/planned/run/netRun/fullyProductive/setup/changeover/breakdown/otherStop/unrecorded/busy/busyInShift`, counts `good/scrap/total/covered/untimedGood/untimedScrap`, `days, measuredDays, closedDays, statusCounts, warningCount`); `series` = the same per period (trend); `workCenters[]` = `{id, code, name, kind, status, calendarSources, totals, cells[]}` where a day cell is that day's figures (`status` MEASURED/NOT_SCHEDULED/NO_DATA/UNTIMED, `closed`, `version`, `partial`, ratios, minutes, counts, `warnings`) and a week cell is the roll-up of its days; `coverage` = `{timeCoverage, outputCoverage, plannedMinutes, measuredPlannedMinutes, untimedOutput, workCenterDays, closedDays, statusCounts, warningCounts}`; `warnings` (newest day first, ≤ 200) = `{code, severity: warning/info, message, minutes?, quantity?, machineId, machine, day}`. `machine` of another tenant → 404; `bucket` other than day/week, a range over 92 days or ending before it starts → 409; malformed date → 400 |
| GET | `/performance/losses?from=&to=&machine=` | `{from, to, totals, waterfall, sixLosses, pareto, quality}`. `waterfall` = `[{key, label, kind: TOTAL/LOSS, minutes}]` calendar → outside shifts → shift time → planned stops → planned production → availability losses → run → performance losses → net run → quality losses → fully productive (measured work-center days only); `sixLosses` = `[{key, label, category: AVAILABILITY/PERFORMANCE/QUALITY, measured, minutes, source}]` for BREAKDOWNS, SETUP_ADJUSTMENTS, SMALL_STOPS_REDUCED_SPEED, PRODUCTION_REJECTS, STARTUP_REJECTS (`measured: false`, `minutes: null`), OTHER_STOPS, UNRECORDED_IDLE; `pareto` = effective downtime minutes inside shift time by reason `[{reason, minutes, plannedMinutes, events, share, cumulative}]`; `quality` = `{inProcess:{goodCount, scrapCount, goodRate}, untimed:{goodCount, scrapCount}, postProduction:{dispositions, quantity, value, note}, rework:{orders, completed, inputQuantity, outputQuantity, yield}, byWorkCenter}` (post-production = POSTED SCRAP dispositions and rework orders of lots produced by the work centers, attributed to the order's last operation work center) |
| GET | `/performance/days/{machineId}/{date}` | One work center and day: `{machine, date, today, live, snapshot, calendarChanged?, differs?, history}`. `live` = the figures computed now (adds `intervals` = working intervals, `stops` by reason with event numbers, `operations` with run/setup minutes, counts and performance, `warnings`); `snapshot` = the current closed version (same shape plus `closedAt`, `closedBy`, `recalculationReason`) or null; `calendarChanged` / `differs` when the live calculation no longer matches the snapshot; `history` = every version `{version, status: CURRENT/SUPERSEDED, oee_status, ratios, planned_minutes, reason, closed_at, closed_by, superseded_at}`. A future date → 409 |
| POST | `/performance/close` | `{date, machineIds?, recalculate?, reason?}`: freezes the OEE of a completed day (`date` before today, within the last year; 409 otherwise) for the listed work centers (empty = all; a foreign id → 404). Idempotent per work center and day: an already closed day is `unchanged`; with `recalculate: true` and a `reason` (≥ 5 characters, else 409) the current version becomes SUPERSEDED and version n+1 is recorded with the reason. Returns `{date, closed, recalculated, unchanged, snapshots:[{machineId, code, version, result, oee}]}`; audits `OEE_DAY_CLOSED` / `OEE_DAY_RECALCULATED`; PLANNER |

```json
{"date":"2026-11-24","machineIds":null,"recalculate":false,"reason":null}
```

```json
{"date":"2026-11-20","machineIds":["opaque-work-center"],"recalculate":true,"reason":"Late breakdown report corrected"}
```

`/state` key `performance` (order 2500, every role): yesterday's plant roll-up `{day, oee, availability, performance, quality, timeCoverage, outputCoverage, utilization, measuredDays, closedDays, warningCount, workCenters}`, or `null` when no work center has a measured OEE that day.

### Two-step sign-in (`/auth/mfa/*`, V17)

Authenticator-app codes (TOTP, RFC 6238: HMAC-SHA1, 6 digits, 30-second steps, one step either side of the server clock accepted). Each time step is accepted once per account (`users.mfa_last_step`), so a code that already signed someone in is refused until the next one. The secret is stored encrypted (AES-256-GCM under `MFA_ENCRYPTION_KEY`, the user id as associated data, `v1:` + base64), never in clear text. Ten one-time recovery codes (`abcde-fghij`, lower case without `0 o 1 l i`; stored as SHA-256 digests) are issued when two-step sign-in is turned on and when they are replaced; they are returned once only. Recovery codes are accepted case-insensitively and with or without the hyphen.

Sign-in: after a correct password of an account with two-step sign-in on, `POST /auth/login` answers `{"authenticated":true,"mfaRequired":true,"mustChangePassword":…}` and the session is limited to `/auth/me`, `/auth/csrf`, `/auth/config`, `/auth/demo`, `/auth/logout`, `/auth/mfa/verify` and `/health`; everything else answers 403 `{"message":"Enter the code from your authenticator app to finish signing in","code":"MFA_REQUIRED"}`. A waiting sign-in expires 5 minutes after the password was accepted (the session ends; 401). This gate is checked before the temporary-password gate.

Policy (`platform.MfaPolicy`): administrators (role `ADMIN`) of a workspace that is not the demo must use two-step sign-in (`app.mfa.required-for-admins` / `MFA_REQUIRED_FOR_ADMINS`, default `true`). A session that began with a password sign-in of such an administrator without two-step sign-in, including one promoted to ADMIN mid-session, is limited to `/auth/me`, `/auth/csrf`, `/auth/config`, `/auth/demo`, `/auth/logout`, `/auth/password`, `/auth/mfa/setup`, `/auth/mfa/enable` and `/health`; everything else answers 403 `{"message":"Set up two-step sign-in to continue","code":"MFA_SETUP_REQUIRED"}`. Demo workspaces and the demo persona switch (`POST /auth/demo`, not a password sign-in) are not held; anyone may still turn it on. Other roles opt in.

Wrong or reused codes answer 409; after 5 wrong codes (authenticator or recovery) for one account within 15 minutes, further attempts, even correct ones, get 429 until the oldest failure is 15 minutes old (in memory per process, like the password-change limit); a correct code clears the failures. A wrong current password answers 409 and counts toward the password-change attempt limit (V9).

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/mfa/setup` | Any signed-in role. Starts a set-up: `{secret, uri, issuer, account}` — a new 160-bit Base32 secret, the `otpauth://totp/…` URI (issuer `DexLanka Manufacturing Pro`, account = email, SHA1, 6 digits, 30 s), kept in this session until `/enable` confirms it; nothing is stored in the database. 409 when two-step sign-in is already on. No `Idempotency-Key` needed |
| POST | `/auth/mfa/enable` | `{currentPassword, code}` with an authenticator code for the secret from `/setup` in this session (409 without a set-up in progress). Stores the encrypted secret, issues 10 recovery codes, signs out the user's other sessions and keeps this one (new session id). Answers `{status:"ENABLED", recoveryCodes:[…10]}`; an idempotent replay answers `recoveryCodes: null`. Audited `MFA_ENABLED`. Requires `Idempotency-Key` |
| POST | `/auth/mfa/verify` | `{code}` (authenticator code or unused recovery code), only while a sign-in is waiting for its code (409 otherwise). Completes the sign-in under a new session id and records the last sign-in. Answers `{method:"TOTP"\|"RECOVERY_CODE", recoveryCodesLeft, authenticated:true, mustChangePassword}`. A recovery code is spent and audited `MFA_RECOVERY_CODE_USED`. No `Idempotency-Key` needed |
| POST | `/auth/mfa/recovery-codes` | `{currentPassword, code}` (authenticator or recovery code); two-step sign-in must be on. The unused codes stop working and 10 new ones are returned once: `{status:"REGENERATED", recoveryCodes}` (`null` on an idempotent replay). Audited `MFA_RECOVERY_CODES_REGENERATED`. Requires `Idempotency-Key` |
| POST | `/auth/mfa/disable` | `{currentPassword, code}` (authenticator or recovery code). Removes the secret and recovery codes, signs out the user's other sessions and keeps this one: `{status:"DISABLED"}`. 409 while the policy requires two-step sign-in for the account ("Administrators must keep two-step sign-in on…"). Audited `MFA_DISABLED`. Requires `Idempotency-Key` |

An administrator resets another user's two-step sign-in with `POST /admin/users/{id}/reset-mfa` (see Administration). `MFA_ENCRYPTION_KEY` (the base64 of 32 random bytes) is required: the API refuses to start without a valid key; the demo and test profiles ship fixed keys. There is no key rotation: if the key is lost or changed, stored secrets cannot be decrypted and the code step of every affected user fails until an administrator resets their two-step sign-in.

```json
{"currentPassword":"the-current-password","code":"492039"}
```

```json
{"code":"k7m2q-x9wde"}
```

### General ledger (`/accounting/*`, V24)

FINANCE and ADMIN only (403 for every other role); every record is tenant-scoped (404 for another tenant's id). Dates are
`yyyy-MM-dd` posting dates (Asia/Colombo), inclusive; balances are debits minus credits (debit positive). Amounts in
manual journals have at most two decimals (400 otherwise). Commands need an `Idempotency-Key`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/accounting/accounts?asOf=` | Chart of accounts in tree order (type, then code; sub-accounts after their header). `{asOf, items, totals:{debit, credit}, counts:{accounts, needs_review, inactive}}`; rows: `id, code, name, type, contra, normal_balance, parent_id, postable, active, system, needs_review, description, depth, children, posted, on_journals, debit, credit, balance` (own lines up to `asOf`, default today) and `total_debit, total_credit, total_balance` (with sub-accounts) |
| POST | `/accounting/accounts` | `{code, name, type, contra?, header?, parentId?, description?}`; `code` matches `[A-Z0-9][A-Z0-9_.-]{0,39}` (400), unique per tenant (409); a parent is an active header account of the same type (409). Audited `GL_ACCOUNT_CREATED` |
| POST | `/accounting/accounts/{id}` | Full replacement with the same body. 409: a system account's code, type or contra changes, or it becomes a header; a code change of an account with postings or manual journal lines; a header with sub-accounts becoming postable; a postable account with postings becoming a header; a type change with sub-accounts; a parent that is not a header of the type, or is the account or one of its sub-accounts. Clears `needs_review`. Audited `GL_ACCOUNT_UPDATED` with the changes |
| POST | `/accounting/accounts/{id}/deactivate`, `/reactivate` | Deactivate: not a system account, a zero balance, no active sub-accounts, not on a DRAFT/SUBMITTED manual journal (409). Reactivate: the parent must be active. Audited `GL_ACCOUNT_DEACTIVATED` / `GL_ACCOUNT_REACTIVATED` |
| GET | `/accounting/accounts/{id}/ledger?from=&to=&limit=&before=` | Posted lines of the account (a header: of all its sub-accounts) with posting dates in the range, oldest first (`posting_date, created_at, entry, line`), keyset-paged (`limit` 1–200, default 50; `before` = the previous page's `next`, the last line id). `{account, from, to, opening, carried, totals:{debit, credit, lines}, closing, items, next}`; `opening` = balance before `from`, each row's `balance` is the running balance after it (carried across pages); rows: `id, entry_id, account, debit, credit, memo, posting_date, reference, description, source, actor, created_at, reverses_entry_id, reversed_by_entry_id` |
| GET | `/accounting/trial-balance?from=&to=&zero=` | As of `to` (default today) without `from`: cumulative `debit`/`credit` per account and the balance in `closing_debit` or `closing_credit`; for the period `from`..`to`: `opening_debit/opening_credit` (before `from`), the period's `debit`/`credit` and the closing balance. Accounts without activity only with `zero=true` (postable accounts). `{mode:"AS_OF"\|"PERIOD", from, to, items, totals:{opening_debit, opening_credit, debit, credit, closing_debit, closing_credit}, balanced}` |
| GET | `/accounting/journals?status=&q=&limit=&before=` | Manual journals, newest first, keyset-paged (`limit` default 25). `status`: `all` (default), `open` (DRAFT + SUBMITTED) or `DRAFT`, `SUBMITTED`, `POSTED`, `REVERSED`, `CANCELLED` (409 otherwise); `q` matches number and narration. `{items, next, total, counts}`; rows add `created_by_name`, `submitted_by_name`, `approved_by_name` and `can_approve` for the viewer |
| GET | `/accounting/journals/{id}` | The journal with `lines` (account name, type, active, postable), `activity` (its audit events), the people's names, `reversal_date`, `approverRoles` and `can: {edit, submit, approve, return, cancel, reverse}` for the viewer |
| POST | `/accounting/journals` | Draft `{postingDate, narration, lines:[{account, debit?, credit?, memo?}]}` (≤ 200 lines; each line exactly one positive amount, 409 "Line N: …"; the account must exist, be postable and active). Drafts may be incomplete or unbalanced. Returns `{id, number, status, posting_date, total_debit, total_credit, entry_id}`; audited `MANUAL_JOURNAL_CREATED` |
| POST | `/accounting/journals/{id}` | Replaces a DRAFT (same body; 409 otherwise); the editor becomes the last editor (`updated_by`); audited `MANUAL_JOURNAL_UPDATED` |
| POST | `/accounting/journals/{id}/submit` | DRAFT → SUBMITTED. 409 unless at least two lines, debits = credits (to the cent, non-zero), active postable accounts and a posting date outside closed periods. The `MANUAL_JOURNAL` policy decides on the total: when no second person is needed the journal posts at once (`approved_by` empty, audited `MANUAL_JOURNAL_AUTO_APPROVED`); audited `MANUAL_JOURNAL_SUBMITTED` |
| POST | `/accounting/journals/{id}/approve` | SUBMITTED → POSTED: one journal entry with source `MANUAL`, reference = the journal number, dated `posting_date`. 403 for a role the policy does not name (ADMIN always may); 409 for the creator, the last editor or the submitter (ADMIN included), and when a posting rule fails now (e.g. the period was closed meanwhile). Audited `MANUAL_JOURNAL_APPROVED` |
| POST | `/accounting/journals/{id}/return` | `{reason}` (5–400): SUBMITTED → DRAFT, by its creator or submitter (withdraw) or an approver role; audited `MANUAL_JOURNAL_RETURNED` |
| POST | `/accounting/journals/{id}/cancel` | `{reason}`: DRAFT or SUBMITTED → CANCELLED (409 for a posted journal: reverse it); audited `MANUAL_JOURNAL_CANCELLED` |
| GET | `/accounting/entries/{id}` | A posted entry: `reference, description, actor, created_at, posting_date, source, total, lines`, what it reverses (`reverses_entry_id`, `reverses_reference`, `reverses_posting_date`), its reversal (`reversed_by_entry_id`, `reversed_on`), its manual journal (`manual_journal_id`, `manual_journal_number`) and `can_reverse` |
| POST | `/accounting/entries/{id}/reverse` | `{postingDate?, reason}` (5–400): posts a `REVERSAL` entry with the lines' debit and credit swapped, the same reference, dated `postingDate` (default today; on or after the original's posting date, outside closed periods). 409 when the entry is itself a reversal, was already reversed, or names an inactive/header account now. Reversing a manual journal's entry marks the journal REVERSED. Returns `{id, reversesEntryId, reference, postingDate, total}`; audited `JOURNAL_REVERSED` |
| GET | `/accounting/policy` | The `MANUAL_JOURNAL` approval policy `{documentType, approvalRequired, amountThreshold, approverRoles, customized, wired, updatedAt, updatedBy}`; ADMIN changes it with `POST /admin/approval-policies/MANUAL_JOURNAL` |

```json
{"postingDate":"2026-11-27","narration":"Accrued electricity for November 2026",
 "lines":[{"account":"UTILITIES_EXPENSE","debit":"182450.00","credit":null,"memo":"Meter readings"},
          {"account":"ACCRUED_EXPENSES","debit":null,"credit":"182450.00","memo":null}]}
```

```json
{"postingDate":null,"reason":"Duplicate: already included in the electricity accrual"}
```

Every journal line names an account of the tenant's chart (deferred foreign key, checked at commit) that is postable and
active (trigger); no entry is dated inside a closed period (trigger); an entry is reversed at most once (unique index).
System accounts (the posting codes) and the codes reserved for later Phase 4 features are listed in
`services/erp-api/README.md` (Ledger reasons and journal accounts).

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

Errors use HTTP 400 (invalid input), 401 (unauthenticated), 403 (role/CSRF/password change required/two-step sign-in code or set-up required), 404 (missing/unowned record), 409 (business state/quantity/reference conflict), 429 (login limit; repeated wrong current passwords; repeated wrong two-step sign-in codes), and sanitized 500s. The body depends on where the error is raised:

| Cause | Status | Body |
|---|---|---|
| Business rule (`Store.require`), unknown or foreign record (`Store.one`), bad date | 409 / 404 / 400 | `application/problem+json`: `{type, title, status, detail, instance}`; the message is in `detail` |
| Bean Validation failure, malformed JSON, missing `Idempotency-Key` | 400 | `application/problem+json`; `detail` is generic |
| Business rule raised by a database trigger (SQLSTATE P0001, e.g. immutable history, approved BOMs, item identity, last administrator) | 409 | `application/problem+json`; `detail` is the trigger's message |
| Other database constraint violation | 409 | `{"message":"This transaction violates a record, quantity or accounting constraint…"}` |
| Role not allowed | 403 | `{"message":"Your role cannot perform this action"}` |
| Missing/expired CSRF token | 403 | `{"message":"Access denied or session token expired. Refresh and try again."}` |
| Temporary password not yet replaced | 403 | `{"message":"Change your temporary password to continue","code":"PASSWORD_CHANGE_REQUIRED"}` |
| Two-step sign-in code still due (V17) | 403 | `{"message":"Enter the code from your authenticator app to finish signing in","code":"MFA_REQUIRED"}` |
| Administrator without two-step sign-in (V17) | 403 | `{"message":"Set up two-step sign-in to continue","code":"MFA_SETUP_REQUIRED"}` |
| Not signed in, or session revoked | 401 | `{"message":"Please sign in"}` |
| Login rate limit | 429 | `{"message":"Too many sign-in attempts. Try again in one minute."}` |
| Password change attempt limit (V9) | 429 | `application/problem+json`; `detail` "Too many incorrect current passwords. Try again in N minutes." |
| Two-step sign-in code attempt limit (V17) | 429 | `application/problem+json`; `detail` "Too many incorrect codes. Try again in N minutes." |

Clients should show `message`, falling back to `detail`. A lost response is resolved by retrying with the same key, not creating another logical transaction.

`/state` key `purchases` is one summary row per purchase order (supplier, status, total, `line_count`, aggregated item names as `item`, summed base `quantity`/`received`/`returned` with `uom` when all lines share one); it replaces the former single-item rows. MRP `incoming` = open base quantity on lines of APPROVED/PARTIAL orders.
`/state` key `bomCosts` (V7) carries the roll-up of every revision (`bom_id, unitCost, singleLevelCost, levels, depth, missingBoms, cycle, active, source_bom_id, source_revision, semi_finished`); `orders` rows carry `parent_order_id`; `mrp` rows keep their original columns and add the multi-level ones listed above (without `pegging`).

`/state` keys `sales` (one summary row per order: totals, line count, aggregated product names, summed base quantity/delivered/returned with `uom` when all lines share one) and `salesLines` (open lines of confirmed orders with `planned` quantity, for the manufacturing-order dialog; `quantity` is the base quantity, V9 adds `entered_quantity` and `unit`) replace the former single-item `sales` rows.

The workspace snapshot caps each operational list at 250 and BOM lines at 1,000. UI pagination applies inside those bounds; exports contain the loaded records. Lists move to cursor-based endpoints feature by feature. Since V8 the snapshot no longer contains the `audit` key (`GET /admin/audit`); V9 removed `users` (`GET /admin/users`), `receipts` (`GET /purchases/receipts`), `ledger` (`GET /inventory/ledger`) and `journal` (`GET /reports/journal`). The Manufacturing orders, Shop floor, Quality control and Costs & reports screens read `GET /manufacturing/orders`, `/quality/inspections`, `/reports/costs` and `/reports/journal`; `orders`, `inspections`, `lots`, `sales`, `purchases` remain for the dashboard, attention counts, pickers and exports.
