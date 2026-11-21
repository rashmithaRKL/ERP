import type { Row } from './api';

export const num = (x: unknown) => Number(x || 0);
export const fmt = (x: unknown, digits = 0) =>
  num(x).toLocaleString('en-LK', { maximumFractionDigits: digits });
export const text = (x: unknown) => String(x ?? '');
export const date = (x: unknown) =>
  x
    ? new Date(text(x).substring(0, 10) + 'T12:00:00').toLocaleDateString('en-GB', {
        day: 'numeric',
        month: 'short',
      })
    : '—';
export const money = (x: unknown) => `Rs. ${fmt(x, 2)}`;
export const today = () =>
  new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Colombo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
/** Status/enum code as lower-case words, e.g. `QUALITY_REVIEW` -> `quality review`. */
export const labelState = (x: unknown) => text(x).toLowerCase().replaceAll('_', ' ');
export function csv(name: string, rows: Row[]) {
  if (!rows.length) return;
  const keys = Object.keys(rows[0]).filter((k) => !k.endsWith('_id') && k !== 'id' && k !== 'tenant_id');
  const esc = (x: unknown) => {
    const raw = text(x);
    return `"${(/^[=+@\-\t\r]/.test(raw) ? "'" : '') + raw.replaceAll('"', '""')}"`;
  };
  const blob = new Blob(
    ['﻿' + [keys.map(esc).join(','), ...rows.map((r) => keys.map((k) => esc(r[k])).join(','))].join('\r\n')],
    { type: 'text/csv;charset=utf-8' },
  );
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${name}-${today()}.csv`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
