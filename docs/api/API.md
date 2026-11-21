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
| POST | `/sales` | Confirm demand; SALES/PLANNER |
| POST | `/sales/{id}/dispatch` | Released stock dispatch and revenue/COGS; WAREHOUSE |
| POST | `/items` | Product/raw material/packaging master; PLANNER |
| POST | `/boms` | Draft single-level material formula; PLANNER |
| POST | `/boms/{id}/approve` | Freeze approved revision; PLANNER |
| POST | `/partners` | Customer/supplier; SALES(customer), WAREHOUSE(supplier), PLANNER |
| POST | `/machines` | Register idle work center; PLANNER |
| POST | `/organization` | Update current organization/plant; ADMIN |
| POST | `/users` | Create bcrypt-backed tenant account; ADMIN |

Lifecycle actions: `approve` (PLANNER, distinct from creator); `reserve` (PLANNER/WAREHOUSE); `issue` (WAREHOUSE); `start`, `pause`, `resume` (OPERATOR/PLANNER); `close`, `cancel` (PLANNER). Cancellation is available only before material issue, with reservations safely released. Issued order cancellation is blocked until a controlled return/variance procedure exists.

Examples:

```json
{"bomId":"opaque-approved-revision","machineId":"opaque-work-center","salesOrderId":null,"quantity":"1000","priority":"NORMAL","dueDate":"2026-11-05"}
```

```json
{"goodQty":"400","scrapQty":"5","labourCost":"2500.00","machineCost":"1800.00","notes":"Five startup rejects; partial run"}
```

```json
{"productId":"opaque-finished-item","revision":"R04","outputQty":"1000","yieldFactor":"0.98","lines":[{"itemId":"opaque-resin","quantity":"22"}]}
```

Errors use HTTP 400 (invalid input), 401 (unauthenticated), 403 (role/CSRF), 404 (missing/unowned record), 409 (business state/quantity/reference conflict), 429 (login limit), and sanitized server errors. Business/validation errors return `{message}`. A lost response is resolved by retrying with the same key, not creating another logical transaction.

The workspace snapshot caps each operational list at 250, BOM lines at 1,000 and audit at 100. UI pagination applies inside those bounds; exports contain the loaded records. Replace this contract with cursor-based list/report endpoints before large-customer deployment.
