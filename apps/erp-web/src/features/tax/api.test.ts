import { describe, expect, it } from 'vitest';
import { codeOptions, pct, previousMonth } from './api';
import type { TaxCode } from './api';

const code = (id: string, applies: string, active = true, rate: number | null = 18) =>
  ({
    id,
    code: id,
    name: `${id} tax`,
    applies_to: applies,
    active,
    current_rate: rate,
    components: [],
  }) as unknown as TaxCode;

describe('tax helpers', () => {
  it('formats rates with up to four decimals', () => {
    expect(pct(18)).toBe('18 %');
    expect(pct('18.1234')).toBe('18.1234 %');
    expect(pct(null)).toBe('—');
  });

  it('offers active codes of the right side and keeps the current choice', () => {
    const codes = [code('STD', 'BOTH'), code('BUY', 'PURCHASES'), code('OLD', 'SALES', false)];
    expect(codeOptions(codes, 'SALES').map((o) => o.value)).toEqual(['STD']);
    expect(codeOptions(codes, 'PURCHASES').map((o) => o.value)).toEqual(['STD', 'BUY']);
    expect(codeOptions(codes, 'SALES', 'OLD').map((o) => o.value)).toEqual(['STD', 'OLD']);
    expect(codeOptions(codes, 'SALES')[0].label).toBe('STD · STD tax (18 %)');
  });

  it('returns the previous calendar month', () => {
    expect(previousMonth('2026-11-27')).toEqual({ from: '2026-10-01', to: '2026-10-31' });
    expect(previousMonth('2026-01-05')).toEqual({ from: '2025-12-01', to: '2025-12-31' });
    expect(previousMonth('2024-03-31')).toEqual({ from: '2024-02-01', to: '2024-02-29' });
  });
});
