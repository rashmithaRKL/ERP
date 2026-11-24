import { useQuery } from '@tanstack/react-query';
import { request } from '../../lib/api';

/** Multi-level BOM read model (V7): `GET /api/boms/...`. Query keys start with `['boms']`. */

export type BomHeader = {
  bomId: string;
  revision: string;
  status: string;
  productId: string;
  sku: string;
  name: string;
  type: string;
  uom: string;
  outputQty: number;
  yieldFactor: number;
  sourceBomId: string | null;
};

/** One node of an indented explosion (level 0 is the product itself). */
export type ExplosionNode = {
  level: number;
  itemId: string;
  sku: string;
  name: string;
  type: string;
  uom: string;
  active: boolean;
  /** Required quantity for the parent quantity, rounded like a manufacturing snapshot (whole pcs, 6 decimals). */
  quantity: number;
  exactQuantity: number;
  /** Quantity per one unit of the parent (output and yield applied). */
  perUnit: number;
  standardCost: number;
  leadTimeDays: number;
  lineQty?: number;
  bomId?: string;
  revision?: string;
  status?: string;
  outputQty?: number;
  yieldFactor?: number;
  extendedCost: number;
  missingBom?: boolean;
  cycle?: boolean;
  children: ExplosionNode[];
};

export type Explosion = {
  bom: BomHeader;
  quantity: number;
  depth: number;
  materialCost: number;
  unitCost: number;
  root: ExplosionNode;
  materials: {
    itemId: string;
    sku: string;
    name: string;
    type: string;
    uom: string;
    quantity: number;
    extendedCost: number;
  }[];
  warnings: string[];
};

export type WhereUsedNode = {
  level: number;
  bomId: string;
  revision: string;
  status: string;
  active: boolean;
  productId: string;
  sku: string;
  name: string;
  type: string;
  uom: string;
  productActive: boolean;
  lineQty: number;
  outputQty: number;
  yieldFactor: number;
  perUnit: number;
  cycle?: boolean;
  parents: WhereUsedNode[];
};

export type WhereUsed = {
  item: { id: string; sku: string; name: string; type: string; uom: string };
  depth: number;
  parents: WhereUsedNode[];
  endProducts: { productId: string; sku: string; name: string; type: string }[];
};

export type Rollup = {
  unitCost: number;
  singleLevelCost: number;
  levels: { level: number; cost: number }[];
  depth: number;
  missingBoms: string[];
  cycle: boolean;
  truncated: boolean;
};

export type CostRollup = BomHeader &
  Rollup & {
    standardCost: number;
    components: {
      itemId: string;
      sku: string;
      name: string;
      type: string;
      uom: string;
      perUnit: number;
      standardCost: number;
      unitCost: number;
      rolledUp: boolean;
      bomId: string | null;
      revision: string | null;
      cost: number;
    }[];
  };

export type CompareLine = {
  itemId: string;
  sku: string;
  name: string;
  type: string;
  uom: string;
  change: 'ADDED' | 'REMOVED' | 'CHANGED' | 'UNCHANGED';
  fromQty: number | null;
  toQty: number | null;
  fromPerUnit: number;
  toPerUnit: number;
  perUnitDelta: number;
  unitCost: number;
  fromCost: number;
  toCost: number;
  costDelta: number;
};

export type Comparison = {
  from: BomHeader;
  to: BomHeader;
  lines: CompareLine[];
  summary: {
    ADDED: number;
    REMOVED: number;
    CHANGED: number;
    UNCHANGED: number;
    fromUnitCost: number;
    toUnitCost: number;
    unitCostDelta: number;
    outputChanged: boolean;
    yieldChanged: boolean;
  };
};

export function useExplosion(bomId: string, quantity: string) {
  const valid = Number(quantity) > 0;
  return useQuery({
    queryKey: ['boms', 'explosion', bomId, quantity],
    queryFn: () =>
      request<Explosion>(`/boms/${bomId}/explosion?quantity=${encodeURIComponent(String(Number(quantity)))}`),
    enabled: valid,
    placeholderData: (previous) => previous,
  });
}

export function useWhereUsed(itemId: string | undefined) {
  return useQuery({
    queryKey: ['boms', 'where-used', itemId],
    queryFn: () => request<WhereUsed>(`/boms/where-used?itemId=${encodeURIComponent(itemId!)}`),
    enabled: !!itemId,
  });
}

export function useCostRollup(bomId: string) {
  return useQuery({
    queryKey: ['boms', 'cost', bomId],
    queryFn: () => request<CostRollup>(`/boms/${bomId}/cost`),
  });
}

export function useComparison(from: string, to: string) {
  return useQuery({
    queryKey: ['boms', 'compare', from, to],
    queryFn: () =>
      request<Comparison>(`/boms/compare?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    enabled: !!from && !!to && from !== to,
  });
}

/** Next free revision reference for a product: R01 -> R02, A -> A2 (case-insensitive). */
export function nextRevision(existing: string[]) {
  const taken = new Set(existing.map((r) => r.toLowerCase()));
  const last = existing[0] || 'R00';
  const match = /^(.*?)(\d+)$/.exec(last);
  const prefix = match ? match[1] : `${last}-`;
  let n = match ? Number(match[2]) + 1 : 2;
  const width = match ? match[2].length : 1;
  let candidate = `${prefix}${String(n).padStart(width, '0')}`;
  while (taken.has(candidate.toLowerCase())) {
    n += 1;
    candidate = `${prefix}${String(n).padStart(width, '0')}`;
  }
  return candidate;
}
