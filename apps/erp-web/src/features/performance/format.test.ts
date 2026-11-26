import { describe, expect, it } from 'vitest';
import { businessDay, cellText, hours, oeeBand, pct, trendRows, waterfallBars } from './format';
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

describe('OEE trend rows', () => {
  it('draws an open last period only as a dashed segment from the previous period', () => {
    const rows = trendRows([
      { label: '24 Nov', partial: false, oee: 0.61, availability: 0.8, performance: 0.8, quality: 0.95 },
      { label: '25 Nov', partial: false, oee: 0.5534, availability: 0.7, performance: 0.81, quality: 0.97 },
      { label: '26 Nov', partial: true, oee: 0.02, availability: 0.05, performance: 0.4, quality: 1 },
    ]);
    expect(rows.map((r) => r.oee)).toEqual([61, 55.3, null]);
    expect(rows.map((r) => r.oeeOpen)).toEqual([null, 55.3, 2]);
    expect(rows.map((r) => r.oeeValue)).toEqual([61, 55.3, 2]);
    expect(rows.map((r) => r.quality)).toEqual([95, 97, null]);
    expect(rows[2].partial).toBe(true);
  });

  it('keeps every period solid when the range ends with a complete day; unmeasured days stay gaps', () => {
    const rows = trendRows([
      { label: '20 Nov', partial: false, oee: null },
      { label: '21 Nov', partial: false, oee: 0.7 },
    ]);
    expect(rows.map((r) => r.oee)).toEqual([null, 70]);
    expect(rows.map((r) => r.oeeOpen)).toEqual([null, null]);
  });
});
