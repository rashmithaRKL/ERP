import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { request } from '../../lib/api';
import type { Row, Value } from '../../lib/api';
import { download } from '../admin/api';

/**
 * V14 genealogy and recall endpoints. Query keys start with `traceability`; `useRecallCommand` invalidates them (and
 * the quality queries, since containment raises nonconformances) after every command.
 */
export const TRACEABILITY = 'traceability';

/** Roles that act on recalls (ADMIN is implied by `can`); every role may trace and read recalls. */
export const RECALL_ROLES = ['QUALITY'];

export type TraceType = 'LOT' | 'MO' | 'RECEIPT' | 'PO' | 'SO' | 'DELIVERY' | 'SUPPLIER';
export type Direction = 'BOTH' | 'BACKWARD' | 'FORWARD';
export type TraceRoot = { type: TraceType; id: string; label?: string; from?: string; to?: string };

export const TRACE_TYPES: { value: TraceType; label: string }[] = [
  { value: 'LOT', label: 'Lot' },
  { value: 'MO', label: 'Manufacturing order' },
  { value: 'RECEIPT', label: 'Goods receipt' },
  { value: 'PO', label: 'Purchase order' },
  { value: 'SO', label: 'Sales order' },
  { value: 'DELIVERY', label: 'Delivery' },
  { value: 'SUPPLIER', label: 'Supplier' },
];

/** One node of a trace graph: a lot or a document (receipt, order, delivery, terminal outflow). */
export type TraceNode = Row & {
  id: string;
  kind: string;
  label: string;
  detail: string;
  level: number;
  side: 'ROOT' | 'BACKWARD' | 'FORWARD';
  ref?: string;
  more?: boolean;
};
/** Material flow from `from` to `to` (always in the direction the material moved). */
export type TraceEdge = {
  from: string;
  to: string;
  kind: string;
  quantity: Value;
  uom: Value;
  at: Value;
  reference: Value;
};
export type TreeRow = {
  node: string;
  indent: number;
  edge: string;
  quantity: Value;
  uom: Value;
  at: Value;
  reference: Value;
  repeated: boolean;
};
export type Trace = {
  root: TraceNode;
  direction: Direction;
  depth: number;
  truncated: boolean;
  nodes: TraceNode[];
  edges: TraceEdge[];
  tree: { backward: TreeRow[]; forward: TreeRow[] };
  summary: Record<string, number>;
};
export type SearchHit = { type: TraceType; id: string; label: string; detail: string };
export type RecallDetail = Row & {
  suspects: Row[];
  lots: Row[];
  shipments: Row[];
  customers: Row[];
  closeCheck: { pendingCustomers: number; unsettledLots: number; releasedSinceContainment: number };
};

/** `?a=1&b=x` from the defined, non-empty values. */
export function params(values: Record<string, string | number | undefined | null>) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  const out = search.toString();
  return out ? `?${out}` : '';
}

export function useTrace(root: TraceRoot | null, direction: Direction, depth: number) {
  const path = root
    ? `/traceability/trace${params({ type: root.type, id: root.id, direction, depth, from: root.from, to: root.to })}`
    : '';
  return useQuery({
    queryKey: [TRACEABILITY, 'trace', path],
    queryFn: () => request<Trace>(path),
    enabled: !!root,
  });
}

export function useTraceSearch(q: string) {
  return useQuery({
    queryKey: [TRACEABILITY, 'search', q],
    queryFn: () => request<{ items: SearchHit[] }>(`/traceability/search${params({ q })}`),
    enabled: q.length >= 2,
    staleTime: 30_000,
  });
}

export function useRecall(id: string | undefined) {
  return useQuery({
    queryKey: [TRACEABILITY, 'recall', id],
    queryFn: () => request<RecallDetail>(`/recalls/${id}`),
    enabled: !!id,
  });
}

/** Invalidates the trace/recall queries and the quality lists (containment raises nonconformances). */
export function useInvalidateRecalls() {
  const qc = useQueryClient();
  const { refresh } = useWorkspace();
  return () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: [TRACEABILITY] }),
      qc.invalidateQueries({ queryKey: ['inventory'] }),
      refresh(),
    ]);
}

/** Posts a recall command, then refreshes. Errors are thrown for the caller to show next to the control. */
export function useRecallCommand() {
  const invalidate = useInvalidateRecalls();
  const { notify } = useWorkspace();
  const [busy, setBusy] = useState(false);
  async function run<T = Row>(url: string, body: unknown = {}, key?: string, success?: string): Promise<T> {
    setBusy(true);
    try {
      const result = await request<T>(url, body, key);
      if (success) notify(success);
      return result;
    } finally {
      await invalidate();
      setBusy(false);
    }
  }
  return { run, busy };
}

/** Downloads the notification list CSV of a recall (the server audits the download). */
export const downloadNotifications = (id: string, number: string) =>
  download(
    `/recalls/${encodeURIComponent(id)}/notifications/export`,
    `${number || 'recall'}-notifications.csv`,
  );

/** Human label of a node kind. */
export const KIND_LABELS: Record<string, string> = {
  LOT: 'Lot',
  MANUFACTURING_ORDER: 'Manufacturing order',
  REWORK_ORDER: 'Rework order',
  RECEIPT: 'Goods receipt',
  GOODS_RECEIPT: 'Goods receipt',
  PURCHASE_ORDER: 'Purchase order',
  SALES_ORDER: 'Sales order',
  DELIVERY: 'Delivery',
  DELIVERY_NOTE: 'Delivery note',
  SUPPLIER: 'Supplier',
  SUPPLIER_RETURN: 'Supplier return',
  SCRAP: 'Scrap',
  MAINTENANCE: 'Maintenance issue',
  ADJUSTMENT: 'Stock adjustment',
  OPENING: 'Opening stock',
  FOUND: 'Found stock',
  ORIGIN: 'Origin',
};

/** Verb of an edge kind for the tree and node details ("issued to", "produced"). */
export const EDGE_LABELS: Record<string, string> = {
  RECEIVED: 'Received',
  ISSUED: 'Issued to production',
  PRODUCED: 'Produced',
  TRANSFERRED: 'Transferred',
  REWORK_INPUT: 'Reworked',
  REWORK_MATERIAL: 'Rework material',
  REWORKED: 'Rework output',
  DELIVERED: 'Delivered',
  RETURNED: 'Returned by customer',
  SUPPLIER_RETURN: 'Returned to supplier',
  SCRAP: 'Scrapped',
  MAINTENANCE: 'Issued to maintenance',
  ADJUSTMENT: 'Adjusted out',
  OPENING: 'Opening stock',
  FOUND: 'Found stock',
  ORIGIN: 'Origin',
};

export const RELATIONS: Record<string, string> = {
  SUSPECT: 'Suspect',
  DOWNSTREAM: 'Made from suspect',
  SOURCE: 'Source of suspect',
  SIBLING: 'Sibling',
};

export const CONTAINMENT: Record<string, string> = {
  HELD: 'Held by recall',
  ALREADY_HELD: 'Already on hold',
  UNDER_NCR: 'Under open NCR',
  AWAITING_INSPECTION: 'Awaiting inspection',
};

/** Where the node can be traced from in turn (re-root the explorer). */
export function traceTargets(node: TraceNode): TraceRoot[] {
  const out: TraceRoot[] = [];
  const s = (v: unknown) => (v === null || v === undefined ? '' : String(v));
  switch (node.kind) {
    case 'LOT':
      out.push({ type: 'LOT', id: s(node.ref), label: node.label });
      break;
    case 'MANUFACTURING_ORDER':
      out.push({ type: 'MO', id: s(node.ref), label: node.label });
      break;
    case 'RECEIPT':
    case 'GOODS_RECEIPT':
      out.push({ type: 'RECEIPT', id: s(node.ref), label: node.label });
      if (node.purchaseOrderId)
        out.push({ type: 'PO', id: s(node.purchaseOrderId), label: s(node.purchaseOrder) });
      if (node.supplierId) out.push({ type: 'SUPPLIER', id: s(node.supplierId), label: s(node.supplier) });
      break;
    case 'PURCHASE_ORDER':
      if (node.supplierId) out.push({ type: 'SUPPLIER', id: s(node.supplierId), label: s(node.supplier) });
      break;
    case 'DELIVERY':
      out.push({ type: 'DELIVERY', id: s(node.reference), label: s(node.reference) });
      if (node.salesOrderId) out.push({ type: 'SO', id: s(node.salesOrderId), label: s(node.salesOrder) });
      break;
    case 'DELIVERY_NOTE':
      if (node.salesOrderId) out.push({ type: 'SO', id: s(node.salesOrderId), label: s(node.salesOrder) });
      break;
  }
  return out.filter((t) => t.id);
}
