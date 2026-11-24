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
  /** One row per received purchase-order line and lot (V6). */
  receipts: Row[];
  ledger: Row[];
  mrp: Row[];
  trend: { day: string; completed: number; scrap: number }[];
  metrics: Row;
  sync: Row;
  financial?: Row;
  journal?: Row[];
  users?: Row[];
  /** V7: standard-cost roll-up of every BOM revision through all levels (BOM cards). */
  bomCosts?: BomCost[];
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
    // password first: let the app shell re-check the session instead of showing errors on every request.
    if ((res.status === 401 && !url.startsWith('/auth/')) || err.code === 'PASSWORD_CHANGE_REQUIRED')
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
