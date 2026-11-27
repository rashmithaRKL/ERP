import { describe, expect, it } from 'vitest';
import { centsToDecimal, drCr, formatCents, normalAmount, sumCents, toCents } from './cents';

describe('journal amounts in cents', () => {
  it('parses amounts to the cent and rejects anything finer or malformed', () => {
    expect(toCents('1234.5')).toBe(123450n);
    expect(toCents(' 1,234.56 ')).toBe(123456n);
    expect(toCents('7')).toBe(700n);
    expect(toCents('0.1')).toBe(10n);
    expect(toCents('')).toBeNull();
    expect(toCents('1.005')).toBeNull();
    expect(toCents('-5')).toBeNull();
    expect(toCents('1e3')).toBeNull();
  });

  it('totals exactly where floats drift', () => {
    // 0.1 + 0.2 !== 0.3 in floating point; in cents it is exact.
    expect(sumCents(['0.1', '0.2'])).toBe(toCents('0.3'));
    const debits = sumCents(['182450.10', '0.20', 'abc', '']);
    expect(debits).toBe(18245030n);
    expect(centsToDecimal(debits)).toBe('182450.30');
    expect(centsToDecimal(-5n)).toBe('-0.05');
  });

  it('formats cents and balances for display', () => {
    expect(formatCents(123456789n)).toBe('Rs. 1,234,567.89');
    expect(formatCents(-1n)).toBe('−Rs. 0.01');
    expect(formatCents(99999999999999n)).toBe('Rs. 999,999,999,999.99');
    expect(drCr(1250)).toBe('Rs. 1,250 Dr');
    expect(drCr('-80.5')).toBe('Rs. 80.5 Cr');
    expect(drCr(0)).toBe('Rs. 0');
    expect(normalAmount(-500, 'CREDIT')).toBe(500);
    expect(normalAmount(-500, 'DEBIT')).toBe(-500);
  });
});
