import type { ReactNode } from 'react';
import {
  Activity,
  Boxes,
  Cpu,
  Factory,
  FileChartColumn,
  GitBranch,
  Layers,
  LayoutDashboard,
  Package,
  Settings2,
  ShieldCheck,
  ShoppingBag,
  ShoppingCart,
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
import AdministrationPage from '../pages/AdministrationPage';
import BomsPage from '../pages/BomsPage';
import InventoryPage from '../pages/InventoryPage';
import ManufacturingPage from '../pages/ManufacturingPage';
import OverviewPage from '../pages/OverviewPage';
import PlanningPage from '../pages/PlanningPage';
import ProductsPage from '../pages/ProductsPage';
import PurchasingPage from '../pages/PurchasingPage';
import QualityPage from '../pages/QualityPage';
import ReportsPage from '../pages/ReportsPage';
import SalesPage from '../pages/SalesPage';
import ShopFloorPage from '../pages/ShopFloorPage';
import WorkCentersPage from '../pages/WorkCentersPage';

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
  /** Rendered once the shared `/api/state` payload has loaded. */
  element: ReactNode;
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
    element: <OverviewPage />,
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
    element: <SalesPage />,
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
    element: <InventoryPage />,
    description: 'Every material, every lot, every movement accounted for.',
    exportRows: (data) => data.lots,
  },
  {
    path: '/purchasing',
    label: 'Purchasing',
    icon: ShoppingCart,
    roles: ['ADMIN', 'PLANNER', 'WAREHOUSE'],
    group: 'WORKSPACE',
    element: <PurchasingPage />,
    description: 'Keep materials moving, from supplier to factory floor.',
    exportRows: (data) => data.purchases,
    action: newPurchase,
  },
  {
    path: '/manufacturing',
    label: 'Manufacturing orders',
    icon: Factory,
    group: 'MANUFACTURING',
    element: <ManufacturingPage />,
    description: 'Plan, track and reconcile every production run.',
    action: newOrder,
  },
  {
    path: '/planning',
    label: 'Material planning',
    icon: Layers,
    roles: ['ADMIN', 'PLANNER', 'WAREHOUSE'],
    group: 'MANUFACTURING',
    element: <PlanningPage />,
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
    element: <BomsPage />,
    description: 'Approved formulas and the materials behind every product.',
    exportRows: (data) => data.boms,
    action: { label: 'New BOM revision', roles: ['PLANNER'], run: (workspace) => workspace.openBomEditor() },
  },
  {
    path: '/shopfloor',
    label: 'Shop floor',
    icon: Cpu,
    group: 'MANUFACTURING',
    element: <ShopFloorPage />,
    description: 'Keep your next operation front and center.',
    action: newOrder,
  },
  {
    path: '/quality',
    label: 'Quality control',
    icon: ShieldCheck,
    group: 'MANUFACTURING',
    element: <QualityPage />,
    description: 'The right quality decision, before stock moves forward.',
    exportRows: (data) => data.inspections,
  },
  {
    path: '/machines',
    label: 'Work centers',
    icon: Activity,
    group: 'MANUFACTURING',
    element: <WorkCentersPage />,
    description: 'A shared view of your machines and work centers.',
    action: { label: 'New work center', roles: ['PLANNER'], run: (workspace) => createWorkCenter(workspace) },
  },
  {
    path: '/products',
    label: 'Products & materials',
    icon: Package,
    roles: ['ADMIN', 'PLANNER', 'WAREHOUSE'],
    group: 'BUSINESS',
    element: <ProductsPage />,
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
    element: <ReportsPage />,
    description: 'Understand where every rupee goes.',
  },
  {
    path: '/settings',
    label: 'Administration',
    icon: Settings2,
    group: null,
    element: <AdministrationPage />,
    description: 'Your organization, deployment and operational history.',
  },
];

/** Pages listed in the sidebar sections, favorites and command palette. */
export const navItems = routes.filter((route) => route.group !== null);

/** The registry entry rendered for `pathname`, using the same matching as `<Routes>`. */
export const findRoute = (pathname: string) =>
  routes.find((route) => matchPath({ path: route.path, caseSensitive: true, end: true }, pathname));
