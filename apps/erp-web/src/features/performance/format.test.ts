import { describe, expect, it } from 'vitest';
import { businessDay, cellText, hours, oeeBand, pct, waterfallBars } from './format';
import type { WaterfallStep } from './format';

describe('OEE formatting', () => {
  it('shows ratios as percentages and never turns "not measurable" into 0 %', () => {
    expect(pct(0.654762)).toBe('65.5%');
    expect(pct(1, 0)).toBe('100%');
    expect(pct(0)).toBe('0%');
    expect(pct(null)).toBe('—');
    expect(pct(undefined)).toBe('—');
  });

  it('formats minutes as hours, short durations as minutes', () => {
    expect(hours(840)).toBe('14 h');
    expect(hours(90)).toBe('1.5 h');
    expect(hours(45.4)).toBe('45 min');
    expect(hours(null)).toBe('—');
  });

  it('bands OEE values for the heatmap', () => {
    expect(oeeBand(0.86)).toBe('excellent');
    expect(oeeBand(0.65)).toBe('good');
    expect(oeeBand(0.5)).toBe('fair');
    expect(oeeBand(0)).toBe('poor');
    expect(oeeBand(null)).toBe('none');
  });

  it('labels cells without an OEE by their reason', () => {
    expect(cellText({ status: 'MEASURED', oee: 0.7234 })).toBe('72%');
    expect(cellText({ status: 'MEASURED', oee: 0 })).toBe('0%');
    expect(cellText({ status: 'NOT_SCHEDULED', oee: null })).toBe('·');
    expect(cellText({ status: 'UNTIMED', oee: null })).toBe('untimed');
    expect(cellText({ status: 'NO_DATA', oee: null })).toBe('n/m');
  });

  it('stacks the loss waterfall: totals from zero, each loss floating on the next total', () => {
    const steps: WaterfallStep[] = [
      { key: 'calendar', label: 'Calendar time', kind: 'TOTAL', minutes: 1440 },
      { key: 'notScheduled', label: 'Outside shifts', kind: 'LOSS', minutes: 540 },
      { key: 'scheduled', label: 'Shift time', kind: 'TOTAL', minutes: 900 },
      { key: 'plannedStops', label: 'Planned stops', kind: 'LOSS', minutes: 60 },
      { key: 'planned', label: 'Planned production time', kind: 'TOTAL', minutes: 840 },
    ];
    const bars = waterfallBars(steps);
    expect(bars.map((b) => [b.base, b.value])).toEqual([
      [0, 24],
      [15, 9],
      [0, 15],
      [14, 1],
      [0, 14],
    ]);
  });

  it('computes the business day in Asia/Colombo (UTC+05:30)', () => {
    // 2026-11-25T19:00Z is 00:30 on 26 November in Colombo.
    expect(businessDay(0, Date.UTC(2026, 10, 25, 19, 0))).toBe('2026-11-26');
    expect(businessDay(-1, Date.UTC(2026, 10, 25, 19, 0))).toBe('2026-11-25');
  });
});
