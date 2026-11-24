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

/** Abbreviations kept upper-case wherever a code is turned into words. */
export const ACRONYMS = new Set([
  'BOM',
  'COGS',
  'FIFO',
  'GRN',
  'GRNI',
  'LKR',
  'MO',
  'MRP',
  'PO',
  'QC',
  'SKU',
  'SO',
  'WIP',
]);

/**
 * Field, account or status code as a sentence-case label with acronyms kept, e.g. `wip_cost` -> "WIP cost",
 * `estimated_cost` -> "Estimated cost", `MATERIALS_RESERVED` -> "Materials reserved". Pass `capitalize = false` for a
 * code inside a sentence ("Was approval required").
 */
export function codeLabel(code: unknown, capitalize = true): string {
  return text(code)
    .trim()
    .split(/[_\s]+/)
    .filter(Boolean)
    .map((word, i) => {
      const upper = word.toUpperCase();
      if (ACRONYMS.has(upper)) return upper;
      const lower = word.toLowerCase();
      return i === 0 && capitalize ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
    })
    .join(' ');
}

/** Single-word status, role and partner codes the API writes into audit details (`DRAFT → CANCELLED`). */
const STATUS_WORDS = new Set([
  'ACTIVE',
  'ADMIN',
  'APPROVED',
  'CANCELLED',
  'CLOSED',
  'COMPLETED',
  'CONFIRMED',
  'COUNTING',
  'CUSTOMER',
  'DELIVERED',
  'DISPATCHED',
  'DRAFT',
  'FAILED',
  'IDLE',
  'INACTIVE',
  'ISSUED',
  'MAINTENANCE',
  'MATCHED',
  'OPEN',
  'OPERATOR',
  'PARTIAL',
  'PASSED',
  'PAUSED',
  'PENDING',
  'PLANNER',
  'POSTED',
  'QUALITY',
  'QUARANTINE',
  'RECEIVED',
  'REJECTED',
  'RELEASED',
  'REMOVED',
  'RESERVED',
  'RETURNED',
  'REVIEW',
  'RUNNING',
  'SALES',
  'STARTED',
  'SUBMITTED',
  'SUPPLIER',
  'VARIANCE',
  'VIEWER',
  'WAREHOUSE',
]);

/**
 * A number in prose: `Rs.` + amount (group 1-2), or a free-standing number (group 3). Digits that touch letters,
 * hyphens, slashes, colons or other digits after a dot (document numbers, lots, dates, times, versions) never match.
 */
const NUMBER =
  /(Rs\.\s?)(-?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)(?![\w/:-]|[.,]\d)|(?<![\w.,/:#-])(-?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)(?![\w/:-]|[.,]\d)/g;
/** An UPPER_SNAKE code, or a single upper-case word (checked against {@link STATUS_WORDS}). */
const CODE = /(?<![\w-])([A-Z]+(?:_[A-Z]+)+|[A-Z]{3,})(?![\w-])/g;
/** Text before a code that starts a new phrase, so the code is capitalized: `MATERIALS_RESERVED → RELEASED`. */
const PHRASE_START = /(?:^|[→←·:;([,/|>—–])$/;

function readableNumber(raw: string, money: boolean) {
  const plain = raw.replaceAll(',', '');
  const [whole, fraction = ''] = plain.replace('-', '').split('.');
  if (!money && !fraction) {
    // Integers that are probably identifiers or years stay as written: `007`, barcodes, `2026`.
    if (whole.length < 4 || whole.length > 9 || (whole.length > 1 && whole.startsWith('0'))) return raw;
    if (whole.length === 4 && !raw.includes(',') && Number(whole) >= 1900 && Number(whole) <= 2099)
      return raw;
  }
  if (whole.replace(/^0+/, '').length > 15) return raw;
  return fmt(plain, money ? 2 : Math.min(fraction.replace(/0+$/, '').length, 20));
}

/**
 * Display form of server-written prose such as audit details. Render-time only: the stored text is never changed.
 *
 * - `Rs. 72750.000000` -> `Rs. 72,750` (money: at most 2 decimals, no trailing zeros, like {@link money})
 * - `WIP 221301.740000` -> `WIP 221,301.74`, `4000 good` -> `4,000 good` (other numbers: grouped, zeros trimmed,
 *   never rounded; years, leading-zero and 10+ digit numbers such as barcodes are left alone)
 * - `MATERIALS_RESERVED → RELEASED` -> `Materials reserved → Released`, `Was APPROVAL_REQUIRED` ->
 *   `Was approval required` (codes in sentence case, acronyms kept, arrows untouched)
 */
export function humanizeText(x: unknown): string {
  return text(x)
    .replace(
      NUMBER,
      (match, prefix: string | undefined, amount: string | undefined, value: string | undefined) =>
        prefix ? prefix + readableNumber(amount!, true) : readableNumber(value ?? match, false),
    )
    .replace(CODE, (code: string, _code: string, offset: number, all: string) =>
      code.includes('_') || STATUS_WORDS.has(code)
        ? codeLabel(code, PHRASE_START.test(all.slice(0, offset).trimEnd()))
        : code,
    );
}
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
