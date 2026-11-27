export type Value = string | number | boolean | null;
export type Row = { id: string; [key: string]: Value };
export type State = {
  company: Row;
  factories: Row[];
  warehouses: Row[];
  partners: Row[];
  machines: Row[];
  items: Row[];
  orders: Row[];
  boms: Row[];
  bomLines: Row[];
  /** One summary row per sales order (lines aggregated); documents come from `GET /sales/orders`. */
  sales: Row[];
  /** Open lines of confirmed sales orders, for planning manufacturing against a customer line. */
  salesLines?: Row[];
  /** One summary row per purchase order (lines aggregated); documents come from `GET /purchases/orders`. */
  purchases: Row[];
  lots: Row[];
  inspections: Row[];
  mrp: Row[];
  trend: { day: string; completed: number; scrap: number }[];
  metrics: Row;
  sync: Row;
  /** ADMIN and PLANNER only. */
  financial?: Row;
  // Removed in V9 (screens read them server-paged): `users` (GET /admin/users), `receipts` (GET /purchases/receipts),
  // `ledger` (GET /inventory/ledger) and `journal` (GET /reports/journal).
  /** V7: standard-cost roll-up of every BOM revision through all levels (BOM cards). */
  bomCosts?: BomCost[];
  /** V11: maintenance attention counts; screens read `GET /maintenance/**`. */
  maintenance?: MaintenanceSummary;
  /** V12 quality attention counts (open / open critical NCRs, pending concessions and supplier returns, open rework,
   * overdue CAPA actions, the caller's overdue CAPA actions). */
  qualityAlerts?: Record<string, number>;
  /** V13: the published schedule run and its late/unscheduled order counts with up to five late orders. */
  schedule?: ScheduleSummary;
  /** V15: yesterday's measured plant OEE (null when no work center has a measured OEE that day). */
  performance?: PlantOee | null;
  /** V14 recall attention: open (not contained) and contained recalls, customers still to notify on contained
   * recalls, and up to five active recalls (most severe first); screens read `GET /recalls`. */
  recalls?: RecallSummary;
};
/** `schedule` state key (V13): published run (null when none), late and unscheduled open orders, five latest late orders. */
export type ScheduleSummary = {
  published: Row | null;
  late_orders: number;
  unscheduled_orders: number;
  late: Row[];
  /** V16: changes to orders, operations, maintenance windows and downtime since the published run was created. */
  changes_since_publish?: number;
  /** V16: the newest of those changes (ISO timestamp), or null. */
  last_change_at?: string | null;
};
/** `performance` state key (V15): ratios 0..1; the Performance page reads `GET /performance/**`. */
export type PlantOee = {
  day: string;
  oee: number | null;
  availability: number | null;
  performance: number | null;
  quality: number | null;
  timeCoverage: number | null;
  outputCoverage: number | null;
  utilization: number | null;
  measuredDays: number;
  closedDays: number;
  warningCount: number;
  workCenters: number;
};
/** `recalls` state key (V14). */
export type RecallSummary = { open: number; contained: number; pending_customers: number; active: Row[] };
/** `maintenance` state key (V11): counts plus up to five open breakdowns and overdue preventive plans. */
export type MaintenanceSummary = {
  open_breakdowns: number;
  open_downtime: number;
  overdue_plans: number;
  awaiting_approval: number;
  in_progress: number;
  breakdowns: Row[];
  overdue: Row[];
};
/** One `bomCosts` row: per-unit material cost by level, total, depth and multi-level warnings. */
export type BomCost = {
  id: string;
  bom_id: string;
  unitCost: number;
  singleLevelCost: number;
  levels: { level: number; cost: number }[];
  depth: number;
  missingBoms: string[];
  cycle: boolean;
  truncated: boolean;
  active: boolean;
  source_bom_id: string | null;
  source_revision: string | null;
  semi_finished: number;
};
export type User = {
  id: string;
  name: string;
  email: string;
  role: string;
  tenant: Row;
  /** Set after an administrator password reset (and for new accounts): the app shows the forced change screen. */
  mustChangePassword?: boolean;
  lastLoginAt?: string | null;
  passwordChangedAt?: string | null;
  /** V17 two-step sign-in: the code step of this sign-in is still due (the app shows the code screen). */
  mfaPending?: boolean;
  /** V17: an administrator who must set up two-step sign-in before using the workspace. */
  mfaSetupRequired?: boolean;
  mfaEnabled?: boolean;
  mfaEnabledAt?: string | null;
  mfaRequired?: boolean;
  recoveryCodesLeft?: number;
};
/** Window event fired by `request` when the server signed this session out or requires a password change. */
export const SESSION_CHANGED = 'dex:session-changed';
let csrf: { token: string; headerName: string } | undefined;
export async function token() {
  const res = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
  if (!res.ok) throw new Error('The server is unavailable. Start the API and try again.');
  csrf = await res.json();
}
export async function request<T>(url: string, body?: unknown, key?: string): Promise<T> {
  if (body !== undefined && !csrf) await token();
  const res = await fetch(`/api${url}`, {
    method: body === undefined ? 'GET' : 'POST',
    credentials: 'same-origin',
    headers:
      body === undefined
        ? {}
        : {
            'Content-Type': 'application/json',
            [csrf!.headerName]: csrf!.token,
            'Idempotency-Key': key || crypto.randomUUID(),
          },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    // The server ended this session (deactivated account, password changed or reset elsewhere) or wants a new
    // password or the two-step sign-in code first: let the app shell re-check the session instead of showing errors on every request.
    if (
      (res.status === 401 && !url.startsWith('/auth/')) ||
      ['PASSWORD_CHANGE_REQUIRED', 'MFA_REQUIRED', 'MFA_SETUP_REQUIRED'].includes(err.code)
    )
      window.dispatchEvent(new Event(SESSION_CHANGED));
    throw new Error(err.message || err.detail || `Request failed (${res.status})`);
  }
  return res.status === 204 ? (undefined as T) : res.json();
}
export async function session(): Promise<User | null> {
  const res = await fetch('/api/auth/me');
  if (res.ok) return res.json();
  if (res.status !== 401) throw new Error('Unable to load your session.');
  const config = await request<{ demo: boolean }>('/auth/config');
  if (config.demo) {
    await request('/auth/demo', {});
    await token();
    return request<User>('/auth/me');
  }
  return null;
}
