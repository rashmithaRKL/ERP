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
  purchases: Row[];
  lots: Row[];
  inspections: Row[];
  receipts: Row[];
  ledger: Row[];
  mrp: Row[];
  trend: { day: string; completed: number; scrap: number }[];
  metrics: Row;
  audit: Row[];
  sync: Row;
  financial?: Row;
  journal?: Row[];
  users?: Row[];
};
export type User = { id: string; name: string; email: string; role: string; tenant: Row };
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
