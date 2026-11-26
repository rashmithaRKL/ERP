import { describe, expect, it } from 'vitest';
import { COLUMN_GAP, edgePath, layoutTrace, NODE_WIDTH } from './layout';

describe('layoutTrace', () => {
  it('places backward levels left of the root and forward levels right of it', () => {
    const layout = layoutTrace(
      [
        { id: 'receipt', level: -2 },
        { id: 'resin', level: -1 },
        { id: 'root', level: 0 },
        { id: 'mo', level: 1 },
        { id: 'lot-a', level: 2, label: 'A' },
        { id: 'lot-b', level: 2, label: 'B' },
      ],
      [
        { from: 'receipt', to: 'resin' },
        { from: 'resin', to: 'root' },
        { from: 'root', to: 'mo' },
        { from: 'mo', to: 'lot-a' },
        { from: 'mo', to: 'lot-b' },
      ],
    );
    expect(layout.columns).toBe(5);
    expect(layout.positions.receipt.col).toBe(0);
    expect(layout.positions.root.col).toBe(2);
    expect(layout.positions['lot-b'].x).toBe(4 * (NODE_WIDTH + COLUMN_GAP));
    expect(layout.positions['lot-a'].row).toBe(0);
    expect(layout.positions['lot-b'].row).toBe(1);
    // A single node in a column is centred against the tallest column.
    expect(layout.positions.root.y).toBeGreaterThan(layout.positions['lot-a'].y);
    expect(layout.width).toBe(5 * NODE_WIDTH + 4 * COLUMN_GAP);
  });

  it('orders a column by the rows of its neighbours nearer the root', () => {
    const layout = layoutTrace(
      [
        { id: 'root', level: 0 },
        { id: 'z-first', level: 1, label: 'Z' },
        { id: 'a-second', level: 1, label: 'A' },
        { id: 'from-z', level: 2, label: 'A' },
        { id: 'from-a', level: 2, label: 'B' },
      ],
      [
        { from: 'root', to: 'z-first' },
        { from: 'root', to: 'a-second' },
        { from: 'a-second', to: 'from-a' },
        { from: 'z-first', to: 'from-z' },
      ],
    );
    expect(layout.positions['a-second'].row).toBe(0);
    expect(layout.positions['z-first'].row).toBe(1);
    expect(layout.positions['from-a'].row).toBe(0);
    expect(layout.positions['from-z'].row).toBe(1);
  });

  it('handles an empty trace and draws leftward links as loops', () => {
    expect(layoutTrace([], []).width).toBe(0);
    const a = { col: 1, row: 0, x: 300, y: 0 };
    const b = { col: 0, row: 0, x: 0, y: 0 };
    expect(edgePath(b, a).startsWith(`M${NODE_WIDTH},`)).toBe(true);
    expect(edgePath(a, b)).toContain('C');
  });
});
