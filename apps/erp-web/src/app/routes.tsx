import {
  Activity,
  Boxes,
  CalendarRange,
  Cpu,
  Factory,
  FileChartColumn,
  Gauge,
  GitBranch,
  Layers,
  LayoutDashboard,
  Package,
  Settings2,
  ShieldCheck,
  ShoppingBag,
  ShoppingCart,
  Workflow,
  Wrench,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { matchPath } from 'react-router-dom';
import type { Row, State } from '../lib/api';
import type { Workspace } from './workspace';
import { createOrder } from '../features/manufacturing/forms';
import { createWorkCenter } from '../features/machines/forms';
import { createItem } from '../features/products/forms';
import { createPurchase } from '../features/purchasing/forms';
import { createSalesOrder } from '../features/sales/forms';
import { requestNewRouting } from '../features/routings/events';
import { lazyPage } from './lazyPage';
import type { LazyPage } from './lazyPage';

// Each page is its own chunk, downloaded on first visit or earlier (hover/focus in navigation, idle time).
const AdministrationPage = lazyPage(() => import('../pages/AdministrationPage'));
const BomsPage = lazyPage(() => import('../pages/BomsPage'));
const InventoryPage = lazyPage(() => import('../pages/InventoryPage'));
const MaintenancePage = lazyPage(() => import('../pages/MaintenancePage'));
const ManufacturingPage = lazyPage(() => import('../pages/ManufacturingPage'));
const OverviewPage = lazyPage(() => import('../pages/OverviewPage'));
const PerformancePage = lazyPage(() => import('../pages/PerformancePage'));
const PlanningPage = lazyPage(() => import('../pages/PlanningPage'));
const ProductsPage = lazyPage(() => import('../pages/ProductsPage'));
const PurchasingPage = lazyPage(() => import('../pages/PurchasingPage'));
const QualityPage = lazyPage(() => import('../pages/QualityPage'));
const ReportsPage = lazyPage(() => import('../pages/ReportsPage'));
const RoutingsPage = lazyPage(() => import('../pages/RoutingsPage'));
const SalesPage = lazyPage(() => import('../pages/SalesPage'));
const SchedulePage = lazyPage(() => import('../pages/SchedulePage'));
const ShopFloorPage = lazyPage(() => import('../pages/ShopFloorPage'));
const WorkCentersPage = lazyPage(() => import('../pages/WorkCentersPage'));

/** Sidebar sections, in display order. */
export const navGroups = ['WORKSPACE', 'MANUFACTURING', 'BUSINESS'] as const;
export type NavGroup = (typeof navGroups)[number];

/** Primary button in the page heading. Shown when `workspace.can(...roles)` (ADMIN always included). */
export type PageAction = { label: string; roles: string[]; run: (workspace: Workspace) => void };

export type PageRoute = {
  path: string;
  /** Navigation, breadcrumb, command palette and page title text. */
  label: string;
  icon: LucideIcon;
  /** Roles that see the page in the sidebar, favorites and command palette (omit for everyone). Not a route guard. */
  roles?: string[];
  /** Sidebar section. `null` keeps the page out of the main navigation (Administration lives in the sidebar footer). */
  group: NavGroup | null;
  /** Code-split page (`lazyPage(() => import('../pages/MyPage'))`), rendered once `/api/state` has loaded. */
  page: LazyPage;
  /** Page heading subtitle. */
  description?: string;
  /** Overrides the `<h1>` (defaults to `label`). */
  heading?: string;
  /** Overrides the eyebrow above the `<h1>` (defaults to the company name). */
  eyebrow?: string;
  /** Rows downloaded by the heading's Export button (defaults to manufacturing orders). */
  exportRows?: (data: State) => Row[];
  action?: PageAction;
};

const newOrder: PageAction = {
  label: 'New manufacturing order',
  roles: ['PLANNER'],
  run: (workspace) => createOrder(workspace),
};
const newPurchase: PageAction = {
  label: 'New purchase order',
  roles: ['PLANNER', 'WAREHOUSE'],
  run: (workspace) => createPurchase(workspace),
};

/**
 * The single page registry. The sidebar, favorites, command palette, page heading and `<Routes>` are all
 * derived from it, so adding a page means adding one file in `pages/` and one entry here.
 */
export const routes: PageRoute[] = [
  {
    path: '/',
    label: 'Overview',
    icon: LayoutDashboard,
    group: 'WORKSPACE',
    page: OverviewPage,
    heading: 'Factory overview',
    eyebrow: 'YOUR FACTORY, IN FOCUS',
    description: 'Everything you need to keep production moving.',
    action: newOrder,
  },
  {
    path: '/sales',
    label: 'Sales orders',
    icon: ShoppingBag,
    roles: ['ADMIN', 'PLANNER', 'SALES', 'WAREHOUSE'],
    group: 'WORKSPACE',
    page: SalesPage,
    description: 'Connect customer demand to production and delivery.',
    exportRows: (data) => data.sales,
    action: {
      label: 'New sales order',
      roles: ['PLANNER', 'SALES'],
      run: (workspace) => {
        createSalesOrder(workspace);
      },
    },
  },
  {
    path: '/inventory',
    label: 'Inventory',
    icon: Boxes,
    group: 'WORKSPACE',
    page: InventoryPage,
    description: 'Every material, every lot, every movement accounted for.',
    exportRows: (data) => data.lots,
  },
  {
    path: '/purchasing',
    label: 'Purchasing',
    icon: ShoppingCart,
    roles: ['ADMIN', 'PLANNER', 'WAREHOUSE'],
    group: 'WORKSPACE',
    page: PurchasingPage,
    description: 'Keep materials moving, from supplier to factory floor.',
    exportRows: (data) => data.purchases,
    action: newPurchase,
  },
  {
    path: '/manufacturing',
    label: 'Manufacturing orders',
    icon: Factory,
    group: 'MANUFACTURING',
    page: ManufacturingPage,
    description: 'Plan, track and reconcile every production run.',
    action: newOrder,
  },
  {
    path: '/planning',
    label: 'Material planning',
    icon: Layers,
    roles: ['ADMIN', 'PLANNER', 'WAREHOUSE'],
    group: 'MANUFACTURING',
    page: PlanningPage,
    description: 'See what you need. Know what to order.',
    exportRows: (data) => data.mrp,
    action: newPurchase,
  },
  {
    path: '/boms',
    label: 'Bills of materials',
    icon: GitBranch,
    roles: ['ADMIN', 'PLANNER'],
    group: 'MANUFACTURING',
    page: BomsPage,
    description: 'Approved formulas and the materials behind every product.',
    exportRows: (data) => data.boms,
    action: { label: 'New BOM revision', roles: ['PLANNER'], run: (workspace) => workspace.openBomEditor() },
  },
  {
    path: '/routings',
    label: 'Routings & shifts',
    icon: Workflow,
    roles: ['ADMIN', 'PLANNER'],
    group: 'MANUFACTURING',
    page: RoutingsPage,
    description: 'How every product is made, and when each work center can make it.',
    action: {
      label: 'New routing',
      roles: ['PLANNER'],
      run: () => {
        requestNewRouting();
      },
    },
  },
  {
    path: '/schedule',
    label: 'Production schedule',
    icon: CalendarRange,
    roles: ['ADMIN', 'PLANNER', 'OPERATOR', 'VIEWER'],
    group: 'MANUFACTURING',
    page: SchedulePage,
    description: 'Every operation on finite capacity: who runs what, when, and what will be late.',
    action: {
      label: 'Run scheduler',
      roles: ['PLANNER'],
      // Loaded on demand so the schedule feature stays out of the entry chunk.
      run: (workspace) => {
        void import('../features/schedule/forms').then((forms) => forms.runScheduler(workspace));
      },
    },
  },
  {
    path: '/shopfloor',
    label: 'Shop floor',
    icon: Cpu,
    group: 'MANUFACTURING',
    page: ShopFloorPage,
    description: 'Keep your next operation front and center.',
    action: newOrder,
  },
  {
    path: '/quality',
    label: 'Quality control',
    icon: ShieldCheck,
    group: 'MANUFACTURING',
    page: QualityPage,
    description: 'The right quality decision, before stock moves forward.',
    exportRows: (data) => data.inspections,
  },
  {
    path: '/machines',
    label: 'Work centers',
    icon: Activity,
    group: 'MANUFACTURING',
    page: WorkCentersPage,
    description: 'A shared view of your machines and work centers.',
    action: { label: 'New work center', roles: ['PLANNER'], run: (workspace) => createWorkCenter(workspace) },
  },
  {
    path: '/maintenance',
    label: 'Maintenance',
    icon: Wrench,
    roles: ['ADMIN', 'PLANNER', 'MAINTENANCE', 'OPERATOR'],
    group: 'MANUFACTURING',
    page: MaintenancePage,
    description: 'Keep every machine ready for the next run.',
    exportRows: (data) => [...(data.maintenance?.breakdowns || []), ...(data.maintenance?.overdue || [])],
    action: {
      label: 'Request maintenance',
      roles: ['OPERATOR', 'PLANNER', 'MAINTENANCE'],
      // Loaded on demand so the maintenance feature stays out of the entry chunk.
      run: (workspace) => {
        void import('../features/maintenance/forms').then((forms) => forms.requestMaintenance(workspace));
      },
    },
  },
  {
    path: '/performance',
    label: 'Performance (OEE)',
    icon: Gauge,
    roles: ['ADMIN', 'PLANNER', 'MAINTENANCE', 'QUALITY', 'VIEWER'],
    group: 'MANUFACTURING',
    page: PerformancePage,
    heading: 'Performance (OEE)',
    description: 'Availability, performance and quality of every work center, measured from real records.',
    action: {
      label: 'Close a day',
      roles: ['PLANNER'],
      // Loaded on demand so the performance feature stays out of the entry chunk.
      run: (workspace) => {
        void import('../features/performance/api').then((m) => m.closeDay(workspace));
      },
    },
  },
  {
    path: '/products',
    label: 'Products & materials',
    icon: Package,
    roles: ['ADMIN', 'PLANNER', 'WAREHOUSE'],
    group: 'BUSINESS',
    page: ProductsPage,
    description: 'The building blocks of your manufacturing operation.',
    exportRows: (data) => data.items,
    action: { label: 'New item', roles: ['PLANNER'], run: (workspace) => createItem(workspace) },
  },
  {
    path: '/reports',
    label: 'Costs & reports',
    icon: FileChartColumn,
    roles: ['ADMIN', 'PLANNER'],
    group: 'BUSINESS',
    page: ReportsPage,
    description: 'Understand where every rupee goes.',
  },
  {
    path: '/settings',
    label: 'Administration',
    icon: Settings2,
    group: null,
    page: AdministrationPage,
    description: 'Your organization, deployment and operational history.',
  },
];

/** Pages listed in the sidebar sections, favorites and command palette. */
export const navItems = routes.filter((route) => route.group !== null);

/** The registry entry rendered for `pathname`, using the same matching as `<Routes>`. */
export const findRoute = (pathname: string) =>
  routes.find((route) => matchPath({ path: route.path, caseSensitive: true, end: true }, pathname));

/**
 * Starts downloading the page chunk for `pathname` (navigation hover/focus, command palette, first load). Failures are
 * ignored here; rendering the page retries and reports them.
 */
export function preloadRoute(pathname: string) {
  findRoute(pathname)
    ?.page.preload()
    .catch(() => undefined);
}
