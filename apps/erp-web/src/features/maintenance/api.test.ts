import { describe, expect, it } from 'vitest';
import { duration, isoFromLocal, localInput, reasonLabel, statusText, statusTone } from './api';

describe('maintenance helpers', () => {
  it('formats downtime durations', () => {
    expect(duration(0)).toBe('0 min');
    expect(duration(45.4)).toBe('45 min');
    expect(duration(60)).toBe('1 h');
    expect(duration(95)).toBe('1 h 35 min');
    expect(duration(3 * 24 * 60 + 120)).toBe('3 d 2 h');
  });

  it('round-trips datetime-local values through ISO instants', () => {
    const local = localInput(new Date(2026, 10, 25, 8, 30));
    expect(local).toBe('2026-11-25T08:30');
    expect(new Date(isoFromLocal(local)!).getTime()).toBe(new Date(2026, 10, 25, 8, 30).getTime());
    expect(isoFromLocal('')).toBeUndefined();
    expect(localInput('not a date')).toBe('');
  });

  it('labels reasons and statuses as text, not only colour', () => {
    expect(reasonLabel('MATERIAL_SHORTAGE')).toBe('Material shortage');
    expect(statusText('IN_PROGRESS')).toBe('In progress');
    expect(statusText('SUPERSEDED')).toBe('Corrected');
    expect(statusTone('OPEN')).toBe('red');
    expect(statusTone('UNKNOWN')).toBe('gray');
  });
});
