import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowRight, GitBranch, Package, ShieldAlert, X } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { fmt, labelState, money, num, text } from '../../lib/format';
import { dateTime } from '../inventory/shared';
import { Facts } from '../quality/shared';
import { EDGE_LABELS, KIND_LABELS, RECALL_ROLES, traceTargets, TRACE_TYPES } from './api';
import type { Trace, TraceEdge, TraceNode, TraceRoot } from './api';
import { KindIcon, TraceBadge } from './shared';

/**
 * Details of the selected graph/tree node: item, quantities, dates, status, the links in and out of it with their
 * quantities, and actions — trace from here, open the lot or manufacturing order, open a recall from a lot.
 */
export function NodeDetails({
  trace,
  node,
  onSelect,
  onClose,
  onTrace,
  onOpenLot,
  onRecall,
}: {
  trace: Trace;
  node: TraceNode;
  onSelect: (id: string) => void;
  onClose: () => void;
  onTrace: (root: TraceRoot) => void;
  onOpenLot: (id: string) => void;
  onRecall: (lotId: string, lotNumber: string) => void;
}) {
  const { can, openOrder } = useWorkspace();
  const navigate = useNavigate();
  const nodes = new Map(trace.nodes.map((n) => [n.id, n]));
  const incoming = trace.edges.filter((e) => e.to === node.id);
  const outgoing = trace.edges.filter((e) => e.from === node.id);
  const ref = text(node.ref);
  const isRoot = node.id === trace.root.id;
  return (
    <aside className="trace-details" aria-label={`Details of ${text(node.label)}`}>
      <div className="trace-details-head">
        <KindIcon kind={node.kind} size={18} />
        <div>
          <span>{KIND_LABELS[node.kind] ?? node.kind}</span>
          <h2>{text(node.label)}</h2>
          <p>{text(node.detail)}</p>
        </div>
        <button className="icon-button" aria-label="Close details" onClick={onClose}>
          <X size={16} />
        </button>
      </div>
      {node.status && (
        <div className="qa-badges trace-details-badges">
          <TraceBadge value={node.status} />
          {node.openNcr && <TraceBadge value="UNDER_NCR" label={`Open ${text(node.openNcr)}`} />}
          {node.more && <TraceBadge value="PENDING" label="More beyond depth" />}
        </div>
      )}
      <Facts items={facts(node)} />
      <Links
        title="In"
        icon={<ArrowLeft size={13} />}
        edges={incoming}
        other={(e) => nodes.get(e.from)}
        onSelect={onSelect}
      />
      <Links
        title="Out"
        icon={<ArrowRight size={13} />}
        edges={outgoing}
        other={(e) => nodes.get(e.to)}
        onSelect={onSelect}
      />
      <div className="trace-details-actions">
        {node.kind === 'LOT' && (
          <button className="button secondary small" onClick={() => onOpenLot(ref)}>
            <Package size={13} /> Open lot
          </button>
        )}
        {node.kind === 'MANUFACTURING_ORDER' && (
          <button className="button secondary small" onClick={() => openOrder(ref)}>
            Open order
          </button>
        )}
        {node.kind === 'REWORK_ORDER' && (
          <button className="button secondary small" onClick={() => navigate('/quality?tab=rework')}>
            Rework orders
          </button>
        )}
        {node.kind === 'REWORK_ORDER' && node.ncrId && (
          <button
            className="button secondary small"
            onClick={() => navigate(`/quality?tab=ncrs&ncr=${encodeURIComponent(text(node.ncrId))}`)}
          >
            Open {text(node.ncr)}
          </button>
        )}
        {traceTargets(node)
          .filter((t) => !(isRoot && t.type === typeOfRoot(trace)))
          .map((t) => (
            <button key={`${t.type}-${t.id}`} className="button secondary small" onClick={() => onTrace(t)}>
              <GitBranch size={13} /> Trace {TRACE_TYPES.find((x) => x.value === t.type)?.label.toLowerCase()}{' '}
              {t.label}
            </button>
          ))}
        {node.kind === 'LOT' && can(...RECALL_ROLES) && (
          <button className="button primary small" onClick={() => onRecall(ref, text(node.label))}>
            <ShieldAlert size={13} /> Open recall
          </button>
        )}
      </div>
    </aside>
  );
}

function typeOfRoot(trace: Trace) {
  const kind = trace.root.kind;
  return kind === 'LOT' ? 'LOT' : kind === 'MANUFACTURING_ORDER' ? 'MO' : '';
}

function facts(n: TraceNode): [string, ReactNode][] {
  switch (n.kind) {
    case 'LOT':
      return [
        ['Item', `${text(n.sku)} · ${text(n.item)}`],
        ['Type', labelState(n.itemType)],
        [
          'On hand',
          `${fmt(n.onHand, 3)} ${text(n.uom)}${num(n.reserved) > 0 ? ` (${fmt(n.reserved, 3)} reserved)` : ''}`,
        ],
        ['Warehouse', `${text(n.warehouse)} (${text(n.warehouseCode)})`],
        ['Source', labelState(n.sourceType)],
        ['Created', dateTime(n.at)],
        ['Unit cost', n.unitCost !== undefined ? money(n.unitCost) : ''],
      ];
    case 'MANUFACTURING_ORDER':
      return [
        ['Product', text(n.detail)],
        ['Quantity', `${fmt(n.goodQty, 3)} good of ${fmt(n.quantity, 3)} ${text(n.uom)}`],
        ['Scrap', num(n.scrapQty) ? `${fmt(n.scrapQty, 3)} ${text(n.uom)}` : ''],
        ['Work center', text(n.machine)],
        ['Sales order', text(n.salesOrder)],
        ['Started', dateTime(n.at)],
      ];
    case 'RECEIPT':
    case 'GOODS_RECEIPT':
      return [
        ['Supplier', text(n.supplier)],
        ['Purchase order', text(n.purchaseOrder)],
        ['Delivery note', text(n.deliveryNote)],
        ['Received', dateTime(n.at)],
      ];
    case 'DELIVERY':
    case 'DELIVERY_NOTE':
      return [
        ['Customer', text(n.customer)],
        ['Sales order', text(n.salesOrder)],
        ['Delivered', n.quantity !== undefined ? `${fmt(n.quantity, 3)} ${text(n.uom)}` : ''],
        ['Returned', num(n.returned) ? `${fmt(n.returned, 3)} ${text(n.uom)}` : ''],
        ['Date', dateTime(n.at)],
      ];
    case 'SALES_ORDER':
      return [['Customer', text(n.customer)]];
    case 'PURCHASE_ORDER':
      return [['Supplier', text(n.supplier)]];
    case 'SUPPLIER':
      return [['Receipts', `${text(n.from)} – ${text(n.to)}`]];
    case 'REWORK_ORDER':
      return [
        ['Item', text(n.detail)],
        ['Nonconformance', text(n.ncr)],
        ['Opened', dateTime(n.at)],
      ];
    default:
      return [
        ['Reference', text(n.ref)],
        ['Date', n.at ? dateTime(n.at) : ''],
      ];
  }
}

function Links({
  title,
  icon,
  edges,
  other,
  onSelect,
}: {
  title: string;
  icon: ReactNode;
  edges: TraceEdge[];
  other: (e: TraceEdge) => TraceNode | undefined;
  onSelect: (id: string) => void;
}) {
  if (!edges.length) return null;
  return (
    <div className="trace-links">
      <h3>
        {icon}
        {title === 'In' ? 'Came from' : 'Went to'}
      </h3>
      <ul>
        {edges.map((e, i) => {
          const n = other(e);
          if (!n) return null;
          return (
            <li key={`${n.id}-${e.kind}-${i}`}>
              <button className="text-button" onClick={() => onSelect(n.id)}>
                {text(n.label)}
              </button>
              <span>
                {EDGE_LABELS[e.kind] ?? e.kind}
                {e.quantity !== null && e.quantity !== undefined
                  ? ` · ${fmt(e.quantity, 3)} ${text(e.uom)}`
                  : ''}
                {e.reference ? ` · ${text(e.reference)}` : ''}
                {e.at ? ` · ${dateTime(e.at)}` : ''}
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
