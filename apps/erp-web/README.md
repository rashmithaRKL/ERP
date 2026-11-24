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
    workspace.tsx       useWorkspace() / useStateData(): session, shared data and action helpers
    CommandPalette.tsx, AttentionCenter.tsx, ProfileDialog.tsx, HelpDialog.tsx, Login.tsx
  pages/                one component per route (OverviewPage, SalesPage, ...)
  features/<domain>/    domain pieces shared by several pages (order drawer, BOM editor, form openers, ...)
  components/           generic UI: Badge, Empty, Overlay, FormDialog + field helpers, Panel, Progress, Stat, Table
  motion/               motion constants, MotionProvider, PageTransition, Tabs, motion.css
  lib/                  api.ts (request/session/token, payload types), format.ts (fmt, money, date, csv, ...), rows.ts
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

2. **Register it** with one entry in `src/app/routes.tsx`. The sidebar, favorites, command palette, breadcrumb, page heading and `<Routes>` are all derived from this array:

   ```tsx
   {
     path: '/maintenance',
     label: 'Maintenance',            // nav, breadcrumb, title and CSV file name
     icon: Wrench,                    // lucide-react icon
     roles: ['ADMIN', 'PLANNER'],     // who sees it in navigation (omit for everyone); it is not a route guard
     group: 'MANUFACTURING',          // sidebar section from navGroups, or null to keep it out of the main nav
     element: <MaintenancePage />,
     description: 'Keep every machine ready for the next run.',
     exportRows: (data) => data.machines,   // optional: rows for the heading's Export button (default: orders)
     action: { label: 'New plan', roles: ['PLANNER'], run: (ws) => createPlan(ws) }, // optional heading button
   },
   ```

   The shell renders the page heading, demo ribbon and context bar (period selector) above your component, and only renders your component once `/api/state` has loaded. Your page starts with the content below the context bar.

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
- Formatting helpers live in `lib/format.ts`: `fmt`, `num`, `money`, `date`, `text`, `today`, `labelState`, `csv`. Show audit action codes with `auditLabel()` from `features/audit/auditLabel.ts`.

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

Rows need a unique `id`. Wrap sections in `Panel` (`title`, `subtitle`, `action`, `className`). Use `Stat` inside a `motion.div` with the `stagger` variants for KPI rows, `Tabs` from `motion/motion.tsx` for filter tabs, and `Empty` for empty states.

## Scripts

Run from the repository root:

```sh
npm ci --no-audit --no-fund
npm run typecheck                    # tsc --noEmit
npm run build                        # tsc -b && vite build (recharts and motion are split into their own chunks)
npm run format -w apps/erp-web       # prettier --write "src/**/*.{ts,tsx}"
npm run format:check -w apps/erp-web
npm run dev                          # Vite on 127.0.0.1:5173 (needs the API on :8080; see the root README)
```

Prettier is configured in `.prettierrc` (`singleQuote`, `printWidth: 110`). CSS files are not formatted.
