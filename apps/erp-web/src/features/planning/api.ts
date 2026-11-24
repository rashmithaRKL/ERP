import { useQuery } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { request } from '../../lib/api';

/** Multi-level MRP read model (V7): `GET /api/planning/mrp`. */

export type Pegging = {
  source: 'ORDER' | 'PLANNED';
  order_id: string | null;
  order_number: string | null;
  order_status: string | null;
  product_id: string;
  product_sku: string;
  product: string;
  quantity: number;
  uncovered: number;
  need_date: string;
  drivers: string[];
};

export type MrpRow = {
  id: string;
  sku: string;
  name: string;
  uom: string;
  type: string;
  active: boolean;
  reorder_level: number;
  reorder_qty: number;
  lead_time_days: number;
  level: number;
  gross: number;
  independent_demand: number;
  dependent_demand: number;
  available: number;
  inspection: number;
  incoming: number;
  on_order: number;
  safety: number;
  shortage: number;
  action: 'NONE' | 'PRODUCE' | 'PURCHASE' | 'NO_BOM' | 'REVIEW';
  bom_id: string | null;
  bom_revision: string | null;
  need_date: string | null;
  start_date: string | null;
  late: boolean;
  drivers: string;
  safety_shortfall: boolean;
  supply_orders: string;
  pegging: Pegging[];
};

export type ParentOrder = { order_id: string; order_number: string; status: string; due_date: string };

type Recommendation = {
  id: string;
  item_id: string;
  sku: string;
  name: string;
  uom: string;
  type: string;
  level: number;
  lead_time_days: number;
  quantity: number;
  need_date: string;
  late: boolean;
  drivers: string[];
  safety_shortfall: boolean;
};

export type PlannedProduction = Recommendation & {
  start_date: string;
  bom_id: string;
  bom_revision: string;
  parent_orders: ParentOrder[];
};

export type PurchaseRecommendation = Recommendation & {
  order_date: string;
  /** Same as `quantity`; lets the purchasing form prefill the purchase quantity. */
  shortage: number;
  reorder_qty: number;
};

export type MrpPlan = {
  today: string;
  summary: { levels: number; shortages: number; plannedProduction: number; purchases: number; late: number };
  rows: MrpRow[];
  plannedProduction: PlannedProduction[];
  purchases: PurchaseRecommendation[];
};

/** The plan, refetched whenever the workspace snapshot is (after every posted transaction). */
export function useMrpPlan() {
  const { stateQuery } = useWorkspace();
  return useQuery({
    queryKey: ['planning', 'mrp', stateQuery.dataUpdatedAt],
    queryFn: () => request<MrpPlan>('/planning/mrp'),
    placeholderData: (previous) => previous,
  });
}
