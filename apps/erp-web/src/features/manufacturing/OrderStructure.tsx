import type { CSSProperties } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CornerDownRight, GitFork, LoaderCircle } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Badge, Progress, Table } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, fmt, num, text } from '../../lib/format';
import { createChildOrders } from './forms';
import '../boms/boms.css';

type TreeNode = Row & { children?: TreeNode[] };

/** Response of `GET /api/manufacturing/{id}/tree` (V7). */
export type OrderTree = {
  order: Row;
  parent: Row | null;
  ancestors: Row[];
  children: TreeNode[];
  descendants: number;
  shortages: Row[];
  canPlanChildren: boolean;
};

function Node({ node, depth, kind }: { node: Row; depth: number; kind?: 'parent' }) {
  const { openOrder } = useWorkspace();
  return (
    <div className={`structure-node ${kind || ''}`} style={{ '--depth': depth } as CSSProperties}>
      <div className="product-cell">
        {depth > 0 && <CornerDownRight size={14} className="muted" />}
        <div className="cell-stack">
          <button className="text-button order-number" onClick={() => openOrder(node.id)}>
            {text(node.number)}
          </button>
          <span>
            {text(node.sku)} · {fmt(node.quantity, 6)} {text(node.uom)} · due {date(node.due_date)} ·{' '}
            {text(node.machine)}
          </span>
        </div>
      </div>
      <div className="table-progress hide-sm">
        <span>
          {fmt(node.good_qty)} <em>/ {fmt(node.quantity)}</em>
        </span>
        <Progress value={num(node.progress)} />
      </div>
      <Badge value={node.status} />
    </div>
  );
}

function Branch({ nodes, depth }: { nodes: TreeNode[]; depth: number }) {
  return (
    <>
      {nodes.map((n) => (
        <div key={n.id} className="order-structure">
          <Node node={n} depth={depth} />
          {n.children && n.children.length > 0 && <Branch nodes={n.children} depth={depth + 1} />}
        </div>
      ))}
    </>
  );
}

/**
 * Parent/child structure of a manufacturing order (V7): the parent it supplies, the child orders that supply its
 * semi-finished materials (nested, with status and progress) and the semi-finished shortages that still need one.
 */
export function OrderStructure({ order }: { order: Row }) {
  const workspace = useWorkspace();
  const { can } = workspace;
  const tree = useQuery({
    queryKey: ['order', order.id, 'tree'],
    queryFn: () => request<OrderTree>(`/manufacturing/${order.id}/tree`),
  });
  if (tree.isPending)
    return (
      <div className="mrp-state">
        <LoaderCircle className="spin" />
      </div>
    );
  if (tree.isError)
    return (
      <div className="form-error" role="alert">
        {(tree.error as Error).message}
      </div>
    );
  const t = tree.data;
  const shortages = t.shortages;
  if (!t.parent && !t.children.length && !shortages.length) return null;
  return (
    <>
      <h3>
        <span className="product-cell">
          <GitFork size={15} />
          Order structure
        </span>
      </h3>
      {t.parent && (
        <>
          <div className="structure-label">Supplies parent order</div>
          <div className="order-structure">
            {t.ancestors.length > 1 && (
              <span className="muted">
                {t.ancestors
                  .slice(0, -1)
                  .map((a) => text(a.number))
                  .join(' › ')}{' '}
                ›
              </span>
            )}
            <Node node={t.parent} depth={0} kind="parent" />
          </div>
        </>
      )}
      {t.children.length > 0 && (
        <>
          <div className="structure-label">
            Child orders · {t.descendants} {t.descendants === 1 ? 'order' : 'orders'}
          </div>
          <Branch nodes={t.children} depth={0} />
        </>
      )}
      {shortages.length > 0 && (
        <>
          <div className="structure-label">Semi-finished materials</div>
          <div className="panel-inline">
            <Table
              compact
              pageSize={50}
              rows={shortages.map((s) => ({ ...s, id: text(s.item_id) }))}
              columns={[
                {
                  label: 'Material',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{text(r.name)}</strong>
                      <span>
                        {text(r.sku)}
                        {r.bom_revision ? ` · BOM ${text(r.bom_revision)}` : ' · no approved BOM'} ·{' '}
                        {text(r.lead_time_days)} day lead time
                      </span>
                    </div>
                  ),
                },
                { label: 'Required', render: (r) => `${fmt(r.required_qty, 6)} ${text(r.uom)}` },
                {
                  label: 'Released stock',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{fmt(r.available_for_order, 6)}</strong>
                      {num(r.earlier_demand) > 0 && (
                        <span>of {fmt(r.available, 6)}; earlier orders first</span>
                      )}
                    </div>
                  ),
                },
                { label: 'Child supply', render: (r) => fmt(r.child_supply, 6) },
                {
                  label: 'Shortage',
                  render: (r) =>
                    num(r.shortage) > 0 ? (
                      <div className="cell-stack">
                        <strong className="negative">
                          {fmt(r.shortage, 6)} {text(r.uom)}
                        </strong>
                        <span>child due {date(r.suggested_due_date)}</span>
                      </div>
                    ) : (
                      <span className="positive">Covered</span>
                    ),
                },
              ]}
            />
          </div>
          {t.canPlanChildren && can('PLANNER') && (
            <div className="structure-actions">
              <button
                className="button secondary small"
                onClick={() => createChildOrders(workspace, t.order, shortages)}
              >
                <GitFork size={13} />
                Create child orders for shortages
              </button>
            </div>
          )}
        </>
      )}
    </>
  );
}
