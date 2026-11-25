import { describe, expect, it } from 'vitest';
import { addDays, dayStart, duration, localDate, localInput, pinnable } from './api';

describe('schedule time helpers (Asia/Colombo)', () => {
  it('maps instants to local business dates and inputs', () => {
    // 20:00 UTC is 01:30 the next day in Sri Lanka.
    expect(localDate('2026-11-25T20:00:00Z')).toBe('2026-11-26');
    expect(localInput('2026-11-25T20:00:00Z')).toBe('2026-11-26T01:30');
    expect(dayStart('2026-11-26')).toBe(Date.parse('2026-11-25T18:30:00Z'));
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01');
    expect(addDays('2026-12-01', -7)).toBe('2026-11-24');
  });

  it('formats durations for planners', () => {
    expect(duration(45)).toBe('45 min');
    expect(duration(320)).toBe('5 h 20 min');
    expect(duration(120)).toBe('2 h');
    expect(duration(3 * 1440 + 240)).toBe('3 d 4 h');
  });

  it('only offers pins for operations that have not started', () => {
    expect(pinnable({ id: '1', operation_id: 'op', current_status: 'READY', running: false })).toBe(true);
    expect(pinnable({ id: '2', operation_id: 'op', current_status: 'IN_PROGRESS', running: true })).toBe(
      false,
    );
    expect(pinnable({ id: '3', operation_id: 'op', current_status: 'DONE', running: false })).toBe(false);
    expect(
      pinnable({
        id: '4',
        operation_id: null,
        operation_status: 'READY',
        order_status: 'RELEASED',
        running: false,
      }),
    ).toBe(true);
    expect(
      pinnable({
        id: '5',
        operation_id: null,
        operation_status: 'READY',
        order_status: 'IN_PROGRESS',
        running: false,
      }),
    ).toBe(false);
  });
});
