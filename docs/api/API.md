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
| POST | `/items` | Product/raw material/packaging master; PLANNER |
| POST | `/boms` | Draft single-level material formula; PLANNER |
| POST | `/boms/{id}/approve` | Freeze approved revision; PLANNER |
| POST | `/partners` | Customer/supplier; optional `phone`, `billingAddress`, `shippingAddress`, `taxId`, `creditLimit` (customers); SALES(customer), WAREHOUSE(supplier), PLANNER |
| POST | `/machines` | Register idle work center; PLANNER |
| POST | `/organization` | Update current organization/plant; ADMIN |
| POST | `/users` | Create bcrypt-backed tenant account; ADMIN |

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

Errors use HTTP 400 (invalid input), 401 (unauthenticated), 403 (role/CSRF), 404 (missing/unowned record), 409 (business state/quantity/reference conflict), 429 (login limit), and sanitized server errors. Business/validation errors return `{message}`. A lost response is resolved by retrying with the same key, not creating another logical transaction.

`/state` keys `sales` (one summary row per order: totals, line count, aggregated product names, summed quantity/delivered/returned with `uom` when all lines share one) and `salesLines` (open lines of confirmed orders with `planned` quantity, for the manufacturing-order dialog) replace the former single-item `sales` rows.

The workspace snapshot caps each operational list at 250, BOM lines at 1,000 and audit at 100. UI pagination applies inside those bounds; exports contain the loaded records. Replace this contract with cursor-based list/report endpoints before large-customer deployment.
