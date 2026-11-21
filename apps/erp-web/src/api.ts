export type Value = string | number | boolean | null;
export type Row = { id: string; [key: string]: Value };
export type State = {
  company: Row; factories: Row[]; warehouses: Row[]; partners: Row[]; machines: Row[];
  items: Row[]; orders: Row[]; boms: Row[]; bomLines: Row[]; sales: Row[]; purchases: Row[];
  lots: Row[]; inspections: Row[]; receipts: Row[]; ledger: Row[]; mrp: Row[];
  trend: { day: string; completed: number; scrap: number }[]; metrics: Row;
  audit: Row[]; sync: Row; financial?: Row; journal?: Row[]; users?: Row[];
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
    method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json', [csrf!.headerName]: csrf!.token, 'Idempotency-Key': key || crypto.randomUUID() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) { const err = await res.json().catch(() => ({})); throw new Error(err.message || err.detail || `Request failed (${res.status})`); }
  return res.status === 204 ? undefined as T : res.json();
}
export async function session(): Promise<User | null> {
  const res = await fetch('/api/auth/me');
  if (res.ok) return res.json();
  if (res.status !== 401) throw new Error('Unable to load your session.');
  const config = await request<{ demo: boolean }>('/auth/config');
  if (config.demo) { await request('/auth/demo', {}); await token(); return request<User>('/auth/me'); }
  return null;
}
export const num = (x: unknown) => Number(x || 0);
export const fmt = (x: unknown, digits = 0) => num(x).toLocaleString('en-LK', { maximumFractionDigits: digits });
export const text = (x: unknown) => String(x ?? '');
export const date = (x: unknown) => x ? new Date(text(x).substring(0, 10) + 'T12:00:00').toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }) : '—';
export const money = (x: unknown) => `Rs. ${fmt(x, 2)}`;
export const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Colombo', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
export function csv(name: string, rows: Row[]) {
  if (!rows.length) return;
  const keys = Object.keys(rows[0]).filter(k => !k.endsWith('_id') && k !== 'id' && k !== 'tenant_id');
  const esc = (x: unknown) => { const raw = text(x); return `"${(/^[=+@\-\t\r]/.test(raw) ? "'" : '') + raw.replaceAll('"', '""')}"`; };
  const blob = new Blob(['\uFEFF' + [keys.map(esc).join(','), ...rows.map(r => keys.map(k => esc(r[k])).join(','))].join('\r\n')], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = `${name}-${today()}.csv`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
