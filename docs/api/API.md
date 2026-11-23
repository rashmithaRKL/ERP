# HTTP contract

Base: `/api`, same origin. Machine-readable OpenAPI: `.openapi/openapi.json` in the repository root.

Authentication uses an HTTP-only session cookie. Fetch `GET /auth/csrf` to receive `{token,headerName}` and attach that header on every unsafe request, including login/logout. Form login is `POST /auth/login` with URL-encoded `email` and `password`. Refresh the CSRF token after login/logout. `GET /auth/me` returns the authenticated identity and organization. The explicit demo profile additionally supports `POST /auth/demo?role=admin|planner|warehouse|operator|quality`; this returns 404 outside demo.

All business mutations require a unique `Idempotency-Key` header; keep it across retries of the **same** request. Different payload/action with the same key returns 409. Body decimal values may be JSON strings to avoid browser floating-point conversions. Responses use six-decimal PostgreSQL numeric values; UI quantities are display-only. Record IDs are opaque strings. Tenant IDs in request JSON are not accepted or used for authorization.

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/state?days=7|14|30|90` | Tenant read snapshot; all signed-in roles; financial aggregates PLANNER/ADMIN, user directory ADMIN |
| GET | `/manufacturing/{id}` | Tenant-owned snapshot/materials/actuals/audit |
| POST | `/manufacturing` | Create snapshotted order; PLANNER |
| POST | `/manufacturing/{id}/actions/{action}` | Validated lifecycle transition; roles depend on action |
| POST | `/manufacturing/{id}/complete` | Partial/final output, scrap and actual conversion cost; OPERATOR |
| POST | `/purchases` | Draft purchase; PLANNER/WAREHOUSE |
| POST | `/purchases/{id}/approve` | Independent approval; PLANNER |
| POST | `/purchases/{id}/receive` | Partial goods receipt and incoming QC; WAREHOUSE |
| POST | `/receipts/{id}/invoice` | Match approved PO price/accepted receipt to supplier reference; ADMIN |
| POST | `/quality/{id}/inspect` | Finalize pending inspection; QUALITY |
| GET | `/sales/orders?status=&q=&limit=&before=` | Sales orders, newest first, keyset-paged (`limit` 1–200, default 25; `before` = previous page's `next`). `status`: `all` (default), `open` (CONFIRMED+PARTIAL) or a status code; `q` matches number, customer, customer reference, line product name/SKU. Returns `{items, next, total, counts}` (`counts` per status for the same search) |
| GET | `/sales/orders/{id}` | `{order, lines, deliveries, returns, manufacturing, lots, activity, credit}`; `lots` are released, unreserved lots of the open lines' products (FIFO); `credit` = `{limit, receivables, exceeded}` |
| POST | `/sales/orders` | Create a draft multi-line order; SALES/PLANNER |
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
| POST | `/boms` | Draft single-level material formula; PLANNER |
| POST | `/boms/{id}/approve` | Freeze approved revision; PLANNER |
| POST | `/partners` | Customer/supplier; optional `phone`, `billingAddress`, `shippingAddress`, `taxId`, `creditLimit` (customers); SALES(customer), WAREHOUSE(supplier), PLANNER |
| POST | `/machines` | Register idle work center; PLANNER |
| POST | `/organization` | Update current organization/plant; ADMIN |
| POST | `/users` | Create bcrypt-backed tenant account; ADMIN |

### Inventory operations (`/inventory/*`)

| Method | Path | Purpose / allowed roles (ADMIN also allowed) |
|---|---|---|
| GET | `/inventory/warehouses` | Warehouses with lots in stock, quarantined lots, open counts and `production_output` flag; all roles |
| POST | `/inventory/warehouses` | Create `{name, code, kind}` (kind `RAW`, `FINISHED`, `WIP`, `QUARANTINE`, `OTHER`); PLANNER/WAREHOUSE |
| POST | `/inventory/warehouses/{id}` | Edit `{name, code, kind, active}`; PLANNER/WAREHOUSE |
| GET / POST | `/inventory/settings` | Read / set `{approvalThreshold}` (default 50,000); set is ADMIN only |
| GET | `/inventory/valuation` | Value per warehouse and quality status, and per inventory account; ADMIN/PLANNER/WAREHOUSE |
| GET | `/inventory/lots?warehouseId&itemId&status&q&includeEmpty&movable&limit&before` | Lots with balances (available = on hand − reserved) |
| GET | `/inventory/lots/{id}` | Lot, movements, lots transferred from it, inspections |
| GET | `/inventory/ledger?warehouseId&itemId&lotId&reference&q&from&to&limit&before` | Stock ledger; dates are Asia/Colombo calendar days |
| GET | `/inventory/adjustments?status&reason&q&limit&before`, `/inventory/adjustments/{id}` | Adjustments; detail adds lines, ledger, journal (ADMIN/PLANNER only) and audit events |
| POST | `/inventory/adjustments` | Draft `{reason, notes, lines[]}`; WAREHOUSE/PLANNER |
| POST | `/inventory/adjustments/{id}/post` | Post, or `APPROVAL_REQUIRED` above the threshold; WAREHOUSE/PLANNER |
| POST | `/inventory/adjustments/{id}/approve` | Post an adjustment awaiting approval; a person other than its creator and submitter; WAREHOUSE/PLANNER |
| POST | `/inventory/adjustments/{id}/cancel` | Cancel a draft or an adjustment awaiting approval; WAREHOUSE/PLANNER |
| GET | `/inventory/transfers?status&warehouseId&q&limit&before`, `/inventory/transfers/{id}` | Transfers; detail adds lines, ledger pair, audit events |
| POST | `/inventory/transfers`, `/inventory/transfers/{id}/post`, `/inventory/transfers/{id}/cancel` | Draft `{fromWarehouseId, toWarehouseId, notes, lines[{lotId, quantity}]}`, post, cancel; WAREHOUSE/PLANNER |
| GET | `/inventory/counts?status&warehouseId&q&limit&before`, `/inventory/counts/{id}` | Counts (`status=OPEN` = COUNTING + REVIEW); detail hides system quantities of a blind count while COUNTING |
| POST | `/inventory/counts` | Start `{warehouseId, itemId?, itemType?, blind, notes}`: snapshots every countable lot; WAREHOUSE/PLANNER |
| POST | `/inventory/counts/{id}/entries` | `{entries[{lineId, countedQty}]}` while COUNTING; WAREHOUSE/PLANNER |
| POST | `/inventory/counts/{id}/submit`, `/reopen`, `/post`, `/cancel` | COUNTING → REVIEW → POSTED (variance adjustment), REVIEW → COUNTING, cancel; WAREHOUSE/PLANNER |

Lists return `{items, next}`; pass `next` as `before` for the next page (keyset on `created_at, id`; `limit` 1–200, default 50). Adjustment line kinds: `DECREASE` / `INCREASE` (`lotId`, at the lot's current cost; decreases never go below the reserved quantity) and `FOUND` (`itemId`, `warehouseId`, `unitCost`, optional `lotNumber`: a new released lot). Reasons restrict kinds: `DAMAGE`, `EXPIRY`, `SAMPLE` decrease only; `FOUND` increases or adds found stock; `COUNT_VARIANCE`, `OTHER` any. The approval rule compares the **gross** value (increases + decreases) with the threshold. Posting values each line like the existing depletion (balance average value; a full depletion removes the exact remaining value) and posts one balanced journal entry per inventory account and direction against `INVENTORY_ADJUSTMENT`. Transfers post a value-neutral `TRANSFER_OUT`/`TRANSFER_IN` ledger pair and no journal (inventory accounts follow the item type, not the warehouse). Receipts awaiting incoming inspection (quarantined `PURCHASE` lots) cannot be adjusted, transferred or counted. Count posting compares counted quantities with the system quantity at posting time and returns `moved` (lots that changed after the snapshot) and the generated `adjustment`.

```json
{"reason":"COUNT_VARIANCE","notes":"Recount of bay 4","lines":[{"kind":"DECREASE","lotId":"opaque-lot","quantity":"12"},{"kind":"FOUND","itemId":"opaque-item","warehouseId":"opaque-warehouse","quantity":"5","unitCost":"125","lotNumber":null}]}
```

Lifecycle actions: `approve` (PLANNER, distinct from creator); `reserve` (PLANNER/WAREHOUSE); `issue` (WAREHOUSE); `start`, `pause`, `resume` (OPERATOR/PLANNER); `close`, `cancel` (PLANNER). Cancellation is available only before material issue, with reservations safely released. Issued order cancellation is blocked until a controlled return/variance procedure exists.

Examples:

```json
{"bomId":"opaque-approved-revision","machineId":"opaque-work-center","salesOrderId":null,"quantity":"1000","priority":"NORMAL","dueDate":"2026-11-05","salesOrderLineId":"opaque-sales-line"}
```

`salesOrderLineId` (optional) plans the order for a line of a CONFIRMED/PARTIAL sales order: the BOM product must match the line's product and planned quantity (non-cancelled orders, less scrap) cannot exceed the line quantity. A bare `salesOrderId` still works when that order has exactly one line of the BOM product.

```json
{"customerId":"opaque-customer","customerReference":"PO-4471","dueDate":"2026-12-05","notes":"",
 "lines":[{"id":null,"productId":"opaque-bottle","quantity":"6000","unitPrice":"27.5","discountPct":"5"},
          {"id":null,"productId":"opaque-cap","quantity":"12000","unitPrice":"7.8","discountPct":"0"}]}
```

```json
{"reference":"DN-2026-01","lots":[{"lotId":"opaque-lot-a","quantity":"4000"},{"lotId":"opaque-lot-b","quantity":"500"}]}
```

Per delivery, revenue/receivable = quantity × unit price × (1 − discount); the delivery that completes a line absorbs the rounding residual so line revenue equals the line total. A customer return reverses `credit_amount` (Dr SALES_REVENUE / Cr ACCOUNTS_RECEIVABLE) and `cost_amount` (Dr FG_INVENTORY / Cr COST_OF_GOODS_SOLD) for the returned share of the delivery; returning the rest of a delivery reverses exactly what remains.

```json
{"goodQty":"400","scrapQty":"5","labourCost":"2500.00","machineCost":"1800.00","notes":"Five startup rejects; partial run"}
```

```json
{"productId":"opaque-finished-item","revision":"R04","outputQty":"1000","yieldFactor":"0.98","lines":[{"itemId":"opaque-resin","quantity":"22"}]}
```

```json
{"unit":"bag","factor":"25","purchase":true,"sales":false}
```

Units: stock, BOM quantities and documents are stored in the item's base unit. `GET /items/{id}/convert?quantity=3&unit=bag` returns `{"baseQuantity":75,"baseUnit":"kg","exact":true,...}`. Other features convert with `catalog.UnitService.toBase(actor, itemId, quantity, unit)` (or `toBaseExact`, which refuses rounding) inside their own transaction; document integration is part of the V9 integration pass.

Errors use HTTP 400 (invalid input), 401 (unauthenticated), 403 (role/CSRF), 404 (missing/unowned record), 409 (business state/quantity/reference conflict), 429 (login limit), and sanitized server errors. Business/validation errors return `{message}`. A lost response is resolved by retrying with the same key, not creating another logical transaction.

`/state` keys `sales` (one summary row per order: totals, line count, aggregated product names, summed quantity/delivered/returned with `uom` when all lines share one) and `salesLines` (open lines of confirmed orders with `planned` quantity, for the manufacturing-order dialog) replace the former single-item `sales` rows.

The workspace snapshot caps each operational list at 250, BOM lines at 1,000 and audit at 100. UI pagination applies inside those bounds; exports contain the loaded records. Replace this contract with cursor-based list/report endpoints before large-customer deployment.
