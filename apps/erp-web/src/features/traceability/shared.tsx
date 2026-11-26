import {
  Archive,
  Building2,
  CircleDot,
  Factory,
  FileText,
  Package,
  PackagePlus,
  RefreshCcw,
  ShoppingCart,
  SlidersHorizontal,
  Trash2,
  Truck,
  Undo2,
  Wrench,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { fmt, labelState, num, text } from '../../lib/format';
import type { TraceNode } from './api';

const ICONS: Record<string, LucideIcon> = {
  LOT: Package,
  MANUFACTURING_ORDER: Factory,
  REWORK_ORDER: RefreshCcw,
  RECEIPT: PackagePlus,
  GOODS_RECEIPT: PackagePlus,
  PURCHASE_ORDER: ShoppingCart,
  SALES_ORDER: FileText,
  DELIVERY: Truck,
  DELIVERY_NOTE: Truck,
  SUPPLIER: Building2,
  SUPPLIER_RETURN: Undo2,
  SCRAP: Trash2,
  MAINTENANCE: Wrench,
  ADJUSTMENT: SlidersHorizontal,
  OPENING: Archive,
  FOUND: Archive,
};

/** Small icon for a node kind (decorative: the kind is always also given as text). */
export function KindIcon({ kind, size = 14 }: { kind: string; size?: number }) {
  const Icon = ICONS[kind] ?? CircleDot;
  return <Icon size={size} aria-hidden="true" className="trace-kind-icon" />;
}

const TONES: Record<string, string> = {
  // recalls
  OPEN: 'amber',
  CONTAINED: 'blue',
  CLOSED: 'green',
  CANCELLED: 'gray',
  PENDING: 'amber',
  NOTIFIED: 'green',
  HELD: 'blue',
  ALREADY_HELD: 'gray',
  UNDER_NCR: 'violet',
  AWAITING_INSPECTION: 'amber',
  // severities
  CRITICAL: 'red',
  MAJOR: 'amber',
  MINOR: 'gray',
  // lots, orders and NCRs
  RELEASED: 'green',
  QUARANTINE: 'amber',
  REJECTED: 'red',
  DISPOSITIONED: 'blue',
  COMPLETED: 'green',
  IN_PROGRESS: 'blue',
  QUALITY_REVIEW: 'violet',
  // relations
  SUSPECT: 'red',
  DOWNSTREAM: 'amber',
  SOURCE: 'violet',
  SIBLING: 'teal',
};

/** Status / severity / relation chip on the shared `.badge` tones; always readable as text. */
export function TraceBadge({ value, label }: { value: unknown; label?: string }) {
  const code = text(value);
  return (
    <span className={`badge ${TONES[code] || 'gray'}`}>
      <i />
      {label ?? labelState(code)}
    </span>
  );
}

/** Third line of a graph node: stock and status of a lot, quantity of an order or delivery. */
export function NodeStatus({ node }: { node: TraceNode }) {
  if (node.kind === 'LOT')
    return (
      <span className="trace-node-meta">
        <span className={`trace-dot ${TONES[text(node.status)] || 'gray'}`} aria-hidden="true" />
        {labelState(node.status)}
        {num(node.onHand) > 0 ? ` · ${fmt(node.onHand, 3)} ${text(node.uom)} on hand` : ' · none on hand'}
      </span>
    );
  if (node.kind === 'MANUFACTURING_ORDER')
    return (
      <span className="trace-node-meta">
        {labelState(node.status)} · {fmt(node.goodQty, 3)} of {fmt(node.quantity, 3)} {text(node.uom)}
      </span>
    );
  if (node.kind === 'DELIVERY')
    return (
      <span className="trace-node-meta">
        {fmt(node.quantity, 3)} {text(node.uom)}
        {num(node.returned) > 0 ? ` · ${fmt(node.returned, 3)} returned` : ''}
      </span>
    );
  if (node.status) return <span className="trace-node-meta">{labelState(node.status)}</span>;
  return null;
}
