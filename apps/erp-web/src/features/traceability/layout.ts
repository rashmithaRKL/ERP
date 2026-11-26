/** Node and edge shapes the layout needs (a subset of the trace payload). */
export type LayoutNode = { id: string; level: number; label?: string };
export type LayoutEdge = { from: string; to: string };
export type Placed = { col: number; row: number; x: number; y: number };
export type Layout = {
  positions: Record<string, Placed>;
  width: number;
  height: number;
  columns: number;
};

export const NODE_WIDTH = 204;
export const NODE_HEIGHT = 74;
export const COLUMN_GAP = 64;
export const ROW_GAP = 14;

/**
 * Left-to-right layered layout of a trace: one column per level (backward levels are negative, the root is 0). Inside
 * a column, nodes are ordered by the average row of their neighbours in the column nearer the root (barycenter), so
 * most edges stay short and uncrossed; columns are centred vertically.
 */
export function layoutTrace(nodes: LayoutNode[], edges: LayoutEdge[]): Layout {
  if (!nodes.length) return { positions: {}, width: 0, height: 0, columns: 0 };
  const byLevel = new Map<number, LayoutNode[]>();
  for (const n of nodes) byLevel.set(n.level, [...(byLevel.get(n.level) ?? []), n]);
  const levels = [...byLevel.keys()].sort((a, b) => a - b);
  const min = levels[0];
  const neighbours = new Map<string, string[]>();
  for (const e of edges) {
    neighbours.set(e.from, [...(neighbours.get(e.from) ?? []), e.to]);
    neighbours.set(e.to, [...(neighbours.get(e.to) ?? []), e.from]);
  }
  const row = new Map<string, number>();
  const levelOf = new Map(nodes.map((n) => [n.id, n.level]));
  const order = (level: number, towards: number) => {
    const column = byLevel.get(level) ?? [];
    const score = (n: LayoutNode) => {
      const rows = (neighbours.get(n.id) ?? [])
        .filter((m) => levelOf.get(m) !== undefined && Math.abs(levelOf.get(m)!) < Math.abs(level))
        .filter((m) => Math.sign(levelOf.get(m)!) === towards || levelOf.get(m) === 0)
        .map((m) => row.get(m))
        .filter((r): r is number => r !== undefined);
      return rows.length ? rows.reduce((a, b) => a + b, 0) / rows.length : Number.MAX_SAFE_INTEGER;
    };
    const sorted = [...column].sort(
      (a, b) => score(a) - score(b) || (a.label ?? a.id).localeCompare(b.label ?? b.id),
    );
    sorted.forEach((n, i) => row.set(n.id, i));
  };
  order(0, 0);
  for (const level of levels.filter((l) => l > 0)) order(level, 1);
  for (const level of levels.filter((l) => l < 0).reverse()) order(level, -1);
  const tallest = Math.max(...[...byLevel.values()].map((c) => c.length));
  const height = tallest * NODE_HEIGHT + (tallest - 1) * ROW_GAP;
  const positions: Record<string, Placed> = {};
  for (const [level, column] of byLevel) {
    const columnHeight = column.length * NODE_HEIGHT + (column.length - 1) * ROW_GAP;
    const offset = (height - columnHeight) / 2;
    const col = level - min;
    for (const n of column) {
      const r = row.get(n.id) ?? 0;
      positions[n.id] = {
        col,
        row: r,
        x: col * (NODE_WIDTH + COLUMN_GAP),
        y: offset + r * (NODE_HEIGHT + ROW_GAP),
      };
    }
  }
  const columns = levels[levels.length - 1] - min + 1;
  return { positions, width: columns * NODE_WIDTH + (columns - 1) * COLUMN_GAP, height, columns };
}

/** SVG path of an edge from the right side of `a` to the left side of `b` (a loop when `b` is not to the right). */
export function edgePath(a: Placed, b: Placed) {
  const x1 = a.x + NODE_WIDTH;
  const y1 = a.y + NODE_HEIGHT / 2;
  const x2 = b.x;
  const y2 = b.y + NODE_HEIGHT / 2;
  if (x2 > x1) {
    const dx = (x2 - x1) / 2;
    return `M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}`;
  }
  // Leftward or same-column link (only possible in cyclic data): bow out below both nodes.
  const bottom = Math.max(a.y, b.y) + NODE_HEIGHT + 24;
  return `M${x1},${y1} C${x1 + 40},${bottom} ${x2 - 40},${bottom} ${x2},${y2}`;
}

/** Midpoint of an edge for its quantity label. */
export function edgeMid(a: Placed, b: Placed) {
  return { x: (a.x + NODE_WIDTH + b.x) / 2, y: (a.y + b.y) / 2 + NODE_HEIGHT / 2 };
}
