import { money, num } from '../../lib/format';

/**
 * Decimal-safe money for the journal editor (V24): amounts are entered to the cent and totalled as whole cents in
 * `bigint`, never as JavaScript floats, so the balancing indicator agrees exactly with the server's
 * "balanced to the cent" rule. Values sent to the API stay the entered decimal strings.
 */

/** `"1,234.5"` / `"1234.50"` → 123450n; null for blank, malformed or more than two decimals. */
export function toCents(value: string): bigint | null {
  const text = value.trim().replaceAll(',', '');
  if (!text) return null;
  const match = /^(\d{1,12})(?:\.(\d{0,2}))?$/.exec(text);
  if (!match) return null;
  return BigInt(match[1]) * 100n + BigInt((match[2] ?? '').padEnd(2, '0'));
}

/** 123450n → `"1234.50"` (plain decimal string, as the API accepts it). */
export function centsToDecimal(cents: bigint): string {
  const negative = cents < 0n;
  const abs = negative ? -cents : cents;
  return `${negative ? '-' : ''}${abs / 100n}.${(abs % 100n).toString().padStart(2, '0')}`;
}

/**
 * 123450n → `Rs. 1,234.5`, like {@link money}. Display only: at most 14 significant digits, which a number shows exactly.
 */
export function formatCents(cents: bigint): string {
  return cents < 0n ? `−${money(centsToDecimal(-cents))}` : money(centsToDecimal(cents));
}

/** Sum of the valid amounts (blank or malformed values count as zero). */
export function sumCents(values: string[]): bigint {
  return values.reduce((sum, v) => sum + (toCents(v) ?? 0n), 0n);
}

/**
 * A server balance (debits minus credits, debit positive) on its side: `Rs. 1,250 Dr`, `Rs. 80.5 Cr`, or `Rs. 0` for
 * zero. Display only: the server computes balances with exact decimals.
 */
export function drCr(balance: unknown): string {
  const n = num(balance);
  if (Math.abs(n) < 0.005) return money(0);
  return `${money(Math.abs(n))} ${n > 0 ? 'Dr' : 'Cr'}`;
}

/** A balance in the account's normal-balance sense: positive when on its normal side (e.g. a credit for income). */
export function normalAmount(balance: unknown, normal: unknown): number {
  const n = num(balance);
  return normal === 'CREDIT' ? -n : n;
}
