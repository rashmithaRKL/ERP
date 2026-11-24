# DexLanka Manufacturing Pro: web app

React 19 + TypeScript + Vite. The UI talks to the Spring Boot API in `services/erp-api` through the Vite dev proxy (`/api` → `127.0.0.1:8080`).

## Source layout

```text
src/
  main.tsx              providers (react-query, router, motion) and global CSS imports
  styles.css            global styles (class names are shared by every page; do not rename)
  app/
    App.tsx             shell: login gate, sidebar, header, page heading, <Routes>, toast, shared dialogs
    routes.tsx          the page registry (single source for navigation, headings and routes)
    lazyPage.tsx        lazyPage() code-split pages and preloadWhenIdle()
    PageLoader.tsx      PageSkeleton (data and chunk loading) and PageErrorBoundary
    workspace.tsx       useWorkspace() / useStateData(): session, shared data and action helpers
    CommandPalette.tsx, AttentionCenter.tsx, ProfileDialog.tsx, HelpDialog.tsx, Login.tsx
  pages/                one component per route (OverviewPage, SalesPage, ...), each its own lazy chunk
  features/<domain>/    domain pieces shared by several pages (order drawer, BOM editor, form openers, ...)
  components/           generic UI: Badge, Empty, Overlay, FormDialog + field helpers, Panel, Progress, Stat, Table
  motion/               motion constants, MotionProvider, PageTransition, Tabs, motion.css
  lib/                  api.ts (request/session/token, payload types), format.ts (fmt, money, date, humanizeText,
                        codeLabel, csv, ...) with format.test.ts, rows.ts
```

## How to add a feature page

1. **Create the page** in `src/pages/MyPage.tsx` with a default-exported component. Keep the file to the component (put exported helpers in `features/<domain>/`) so Fast Refresh keeps working.

   ```tsx
   import { useQuery } from '@tanstack/react-query';
   import { useWorkspace } from '../app/workspace';
   import { Panel, Table } from '../components';
   import { request } from '../lib/api';
   import type { Row } from '../lib/api';
   import { money } from '../lib/format';

   export default function MaintenancePage() {
     const { can, postForm } = useWorkspace();
     const plans = useQuery({ queryKey: ['maintenance', 'plans'], queryFn: () => request<Row[]>('/maintenance/plans') });
     return (
       <Panel title="Maintenance plans" subtitle="Preventive work per machine">
         <Table rows={plans.data || []} search columns={[{ label: 'Machine', key: 'machine' }, { label: 'Cost', render: (r) => money(r.cost) }]} />
       </Panel>
     );
   }
   ```

2. **Register it** with one entry in `src/app/routes.tsx`. The sidebar, favorites, command palette, breadcrumb, page heading and `<Routes>` are all derived from this array. Pages are code-split: declare the page with `lazyPage` next to the others (never import a page statically, or it is pulled back into the entry chunk):

   ```tsx
   const MaintenancePage = lazyPage(() => import('../pages/MaintenancePage'));
   // …
   {
     path: '/maintenance',
     label: 'Maintenance',            // nav, breadcrumb, title and CSV file name
     icon: Wrench,                    // lucide-react icon
     roles: ['ADMIN', 'PLANNER'],     // who sees it in navigation (omit for everyone); it is not a route guard
     group: 'MANUFACTURING',          // sidebar section from navGroups, or null to keep it out of the main nav
     page: MaintenancePage,
     description: 'Keep every machine ready for the next run.',
     exportRows: (data) => data.machines,   // optional: rows for the heading's Export button (default: orders)
     action: { label: 'New plan', roles: ['PLANNER'], run: (ws) => createPlan(ws) }, // optional heading button
   },
   ```

   The shell renders the page heading, demo ribbon and context bar (period selector) above your component, and only renders your component once `/api/state` has loaded. Your page starts with the content below the context bar.

   **Lazy loading.** Each page is its own chunk. The current page's chunk downloads while the session and `/api/state` load; afterwards the shell preloads the pages the role can open, one at a time while the browser is idle (skipped with Save-Data). Hovering or focusing a sidebar/favorite link, or a page in the command palette, preloads that page immediately (`preloadRoute(path)`). A page whose chunk has loaded renders synchronously; otherwise the routed area shows `PageSkeleton` (it fades in after 140 ms, so fast loads never flash it). If a chunk cannot be downloaded (offline, a new release replaced the hashed files) or the page throws while rendering, `PageErrorBoundary` shows a "Try again / Reload" panel inside the shell instead of a blank screen; it resets on navigation. Feature CSS is imported once in `main.tsx` (before `styles.css`), so the stylesheet cascade does not depend on which page loaded first; import a new feature stylesheet there too.

## `useWorkspace()`

Shared state and helpers from `src/app/workspace.tsx`. The members pages normally use:

| Member | Purpose |
| --- | --- |
| `user` | Signed-in user (`role`, `name`, `tenant`) |
| `can(...roles)` | `true` for ADMIN or any listed role. `can()` means admin only |
| `allowed(roles)` | Exact role check without the implicit ADMIN (used for navigation) |
| `data` / `useStateData()` | The shared `/api/state` payload. `useStateData()` returns it non-null inside pages |
| `summary` | Derived lists and figures: `active` orders, MRP `shortages`, `pendingQc`, `output`, `scrap`, `yieldPct` |
| `days` / `setDays` | Reporting period from the header selector |
| `mutate(url, body?)` | POST, then refresh data and toast. `busy` is true while it runs. Disable buttons with `disabled={busy}` |
| `postForm(title, url, fields, description?, submit?)` / `openForm(spec)` | Open the shared form dialog |
| `notify(message)` | Toast. Text containing "success" or "downloaded" gets the success style |
| `refresh()` | Invalidate the `['state']` and `['order']` queries and the paged `['manufacturing']`, `['quality']` and `['reports']` lists |
| `openOrder(id)` | Open the manufacturing order drawer |
| `openBomEditor()` | Open the BOM revision editor |
| `search` / `setSearch` | Workspace text filter used with `filterRows()` from `lib/rows.ts`. It resets on navigation |
| `favorites` / `toggleFavorite(path)` | Favorite pages |

Members after the "Overlay state" comment in the `Workspace` type (`toast`, `form`, `orderId`, `actionError`, `bomEditor` and their closers) belong to the shell, which renders those overlays. Pages should use the helpers above instead.

Page-local UI state (selected tab, local filters) belongs in the page with `useState`. Each route remounts on navigation, so it resets the same way the shell's state does.

## Fetching data

- Use `@tanstack/react-query` with `request<T>(url, body?, idempotencyKey?)` from `lib/api.ts`. `request` prefixes `/api`, sends cookies, adds the CSRF header and an `Idempotency-Key` on POST, and throws an `Error` carrying the server's message.
- **New features call their own endpoints with their own query keys** (for example `['maintenance', 'plans']`). Do not grow `/api/state` or its `State` type: that payload is shared by every page and refetched after every transaction. The order drawer (`features/manufacturing/OrderDrawer.tsx`, key `['order', id]`) is the reference example.
- After a write, invalidate your own keys (`queryClient.invalidateQueries({ queryKey: ['maintenance'] })`). `workspace.refresh()` covers `['state']`, `['order']` and the V9 paged lists (`['manufacturing']`, `['quality']`, `['reports']`).
- Keyset-paged lists with previous/next paging use `useCursorPage(key, path, filters, limit)` with `PageFooter` and `PageState` from `features/lists/cursorPage.tsx` (Manufacturing orders, Shop floor, Quality control, Costs & reports). The cursor stack resets when the filters change.
- Approve buttons follow the tenant's approval policy: `useApprovalPolicy('MANUFACTURING_ORDER' | 'PURCHASE_ORDER' | 'STOCK_ADJUSTMENT')` from `features/approvals/policies.ts` returns the approver roles (`GET /api/approval-policies`); pass them to `can(...)` and hide the button for the document's creator.
- Formatting helpers live in `lib/format.ts`: `fmt`, `num`, `money`, `date`, `text`, `today`, `labelState`, `codeLabel`, `humanizeText`, `csv`. Show audit action codes with `auditLabel()` from `features/audit/auditLabel.ts`.
- **Server-written prose** (audit/activity `detail`, e.g. `Gross value Rs. 72750.000000 exceeds the Rs. 50000.000000 approval threshold`) is displayed through `humanizeText(detail)`, never `text(detail)`: money after `Rs.` gets at most 2 decimals and no trailing zeros (like `money()`), other numbers are grouped and trimmed without rounding (`WIP 221301.740000` → `WIP 221,301.74`), and `UPPER_SNAKE`/status codes become sentence case (`MATERIALS_RESERVED → RELEASED` → `Materials reserved → Released`). Document numbers, lots, dates, times, barcodes and years are left alone. It is render-time only; stored audit text never changes.
- **Field, account and status codes as labels** use `codeLabel(code)`: `wip_cost` → "WIP cost", `estimated_cost` → "Estimated cost", `COGS` → "COGS". Acronyms (WIP, FIFO, MO, PO, SO, QC, BOM, GRN, GRNI, COGS, MRP, SKU, LKR) stay upper-case; extend `ACRONYMS` in `lib/format.ts` (shared with `auditLabel`). Do not title-case labels with CSS `text-transform: capitalize`; the `.capitalize` class now produces sentence case.
- **Status colours** for text, badges and chips come from the `--tone-green|blue|amber|red|gray|violet|teal` tokens in `styles.css`, which meet WCAG AA (4.5:1) on their tints, `--surface` and `--soft` in both themes. Status must always be readable as text too (a badge label, not only a dot or colour).

## Forms: `FormDialog`

Describe the fields and let the shell render the dialog:

```ts
import { dateField, option, qtyField, selectField } from '../components';

postForm(
  'New maintenance plan',
  '/maintenance/plans',
  [
    selectField('machineId', 'Work center', option(data.machines, 'name')),
    { ...qtyField('cost', 'Estimated cost (LKR)'), min: '0' },
    { name: 'notes', label: 'Notes', type: 'textarea', optional: true },
    dateField,
  ],
  'Plans require a planner approval.',
  'Create plan',
);
```

`Field` supports `type` (`text` by default, or `number`, `date`, `email`, `password`, `textarea`), `options` (renders a `<select>`), `defaultValue`, `min`, `step`, `hint` and `optional`. The dialog keeps one idempotency key per opening, so retrying after an error cannot double-post. On success it calls `refresh()` and shows the success toast. For non-POST or multi-step flows, call `openForm({ title, fields, run: (values, key) => ... })`. For editors that need dynamic rows, write a dedicated dialog on top of `Overlay` (see `features/boms/BomDialog.tsx`) and render it inside an `<AnimatePresence>`.

### Multi-line documents: `LineItemsEditor`

`components/LineItemsEditor.tsx` is the controlled line editor used by the sales order editor (and meant for purchase orders): item picker, quantity (whole numbers for `pcs` items), unit price, optional discount %, display-only line total and running totals, add/remove rows with motion. Enter moves to the next line (adding one after the last), Ctrl+Delete removes the current line. Helpers (`newLine`, `lineNet`, types) live in `components/lineItems.ts`.

```tsx
const [lines, setLines] = useState<LineItem[]>([newLine()]);
<LineItemsEditor
  lines={lines}
  onChange={setLines}
  options={items.map((i) => ({ value: i.id, label: text(i.name), unit: text(i.uom) }))}
  itemLabel="Material"
  priceLabel="Unit cost"
  discount={false}
/>;
```

Send `lines.map(({ id, itemId, quantity, unitPrice, discountPct }) => …)` to the API; the server recomputes totals.

Optional `units={(line) => UnitChoice[] | undefined}` adds a unit picker to the quantity cell (bound to `line.unit`; the quantity step follows the chosen unit's `decimals`). The purchase-order editor (`features/purchasing/PurchaseOrderEditor.tsx`) feeds it the purchase units of `GET /items/{id}/units` (`purchaseUnits`) and the sales-order editor the sales units (`salesUnits`, V9); both rescale the unit price when the unit changes and show the base quantity.

## Tables: `Table`

```tsx
<Table
  rows={rows}
  search                                   // optional search box plus record count
  placeholder="Search plans…"
  onRow={(r) => openOrder(r.id)}           // optional clickable rows
  compact                                  // no pagination footer
  pageSize={8}
  columns={[
    { label: 'Plan', key: 'number' },      // a key makes the column sortable and renders row[key]
    { label: 'Status', key: 'status', render: (r) => <Badge value={r.status} /> },
  ]}
/>
```

Rows need a unique `id`. Give every column a `label` (it is the header, the `data-label` of each cell and the card label on phones); a column of row buttons may use `''`. Optional `label` names the table for screen readers. Headers have `scope="col"` and sorted columns expose `aria-sort`; rows with `onRow` are focusable and open with Enter or Space.

Layout is handled by the shared table layer (`Table.tsx` + `styles.css`), the same for every page and drawer:

- Inside dialogs and drawers, cells are tighter and headers, `.cell-stack` secondary lines, `.table-progress` captions and `.notes-cell` wrap, so document lines, receipts and returns fit a drawer at 1280 px.
- A table that is still wider than its container scrolls sideways with a sticky first column, a shadow on the scrolled edge and a fade on the edge with more columns; the scroll area becomes a focusable, labelled region only while it scrolls (keyboard users can scroll it with the arrow keys).
- At phone width (≤ 650 px) tables in dialogs and drawers stack into one card per row, each value labelled by its column header. Page tables keep horizontal scrolling with the sticky first column.

Wrap sections in `Panel` (`title`, `subtitle`, `action`, `className`). Use `Stat` inside a `motion.div` with the `stagger` variants for KPI rows, `Tabs` from `motion/motion.tsx` for filter tabs and drawer sections (WAI-ARIA tabs: one tab stop, Arrow Left/Right, Home and End move and select), and `Empty` for empty states.

## Scripts

Run from the repository root:

```sh
npm ci --no-audit --no-fund
npm run typecheck                    # tsc --noEmit
npm run build                        # tsc -b && vite build
npm run test -w apps/erp-web         # vitest run: unit tests next to the code (*.test.ts), e.g. lib/format.test.ts
npm run format -w apps/erp-web       # prettier --write "src/**/*.{ts,tsx}"
npm run format:check -w apps/erp-web
npm run dev                          # Vite on 127.0.0.1:5173 (needs the API on :8080; see the root README)
```

Build output: an entry chunk (shell, shared components, drawers opened from anywhere), vendor chunks `react` (react, react-dom, scheduler), `motion` and `charts` (recharts and its dependencies), and one chunk per page. `manualChunks` in `vite.config.ts` assigns vendor chunks by npm package; React has its own chunk because Rollup otherwise placed React internals in `charts`, which made the entry load the charts chunk eagerly. Keep every chunk under Vite's 500 kB warning, and check that `dist/index.html` preloads only `react` and `motion` (charts load with Overview and Costs & reports).

Prettier is configured in `.prettierrc` (`singleQuote`, `printWidth: 110`). CSS files are not formatted.
