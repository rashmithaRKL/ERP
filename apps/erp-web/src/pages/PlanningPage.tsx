import { useState } from 'react';
import { AnimatePresence, motion } from 'motion/react';
import {
  ArrowUpRight,
  CalendarClock,
  Factory,
  Layers,
  ListTree,
  LoaderCircle,
  ShoppingCart,
} from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Empty, Overlay, Panel, Stat, Table } from '../components';
import { createPlannedOrder } from '../features/manufacturing/forms';
import { useMrpPlan } from '../features/planning/api';
import type { MrpRow, Pegging, PlannedProduction, PurchaseRecommendation } from '../features/planning/api';
import { ItemTypeBadge } from '../features/products/ItemTypeBadge';
import { createPurchase } from '../features/purchasing/forms';
import type { Row } from '../lib/api';
import { date, fmt, num, text } from '../lib/format';
import { stagger, Tabs } from '../motion/motion';
import '../features/boms/boms.css';

type View = 'materials' | 'production' | 'purchases';

/** Order numbers as links to the order drawer (pegging drivers). */
function Drivers({ numbers, safety }: { numbers: string[]; safety?: boolean }) {
  const { openOrder } = useWorkspace();
  const data = useStateData();
  const ids = new Map(data.orders.map((o) => [text(o.number), o.id]));
  if (!numbers.length && !safety) return <span className="muted">—</span>;
  return (
    <div className="driver-list">
      {numbers.map((n) =>
        ids.has(n) ? (
          <button key={n} className="text-button order-number" onClick={() => openOrder(ids.get(n)!)}>
            {n}
          </button>
        ) : (
          <span key={n} className="muted">
            {n}
          </span>
        ),
      )}
      {safety && <span className="bom-chip">safety stock</span>}
    </div>
  );
}

function When({
  need,
  start,
  late,
  startLabel,
}: {
  need: unknown;
  start: unknown;
  late: boolean;
  startLabel: string;
}) {
  if (!need) return <span className="muted">—</span>;
  return (
    <div className="mrp-date">
      <strong className={late ? 'negative' : ''}>
        {startLabel} {date(start)}
        {late && <span className="late-flag"> · late</span>}
      </strong>
      <span>needed {date(need)}</span>
    </div>
  );
}

function PeggingDialog({ row, onClose }: { row: MrpRow; onClose: () => void }) {
  const { openOrder } = useWorkspace();
  return (
    <Overlay
      title={`Pegging · ${row.name}`}
      subtitle={`${row.sku} · level ${row.level}. Requirements in need-date order; open demand is covered before safety stock.`}
      onClose={onClose}
      wide
    >
      <div className="compare-body">
        {row.pegging.length === 0 ? (
          <Empty
            title="No open demand"
            body={
              num(row.shortage) > 0
                ? 'The net requirement comes from the safety stock only.'
                : 'No open order or planned production needs this item.'
            }
          />
        ) : (
          <div className="panel-inline">
            <Table
              compact
              pageSize={100}
              rows={row.pegging.map((p: Pegging, i) => ({
                id: `${i}`,
                source: p.source,
                order_id: p.order_id,
                order_number: p.order_number,
                product: p.product,
                product_sku: p.product_sku,
                quantity: p.quantity,
                uncovered: p.uncovered,
                need_date: p.need_date,
                drivers: p.drivers.join(', '),
              }))}
              columns={[
                {
                  label: 'Required by',
                  render: (r) =>
                    r.source === 'ORDER' ? (
                      <div className="cell-stack">
                        <button
                          className="text-button order-number"
                          onClick={() => openOrder(text(r.order_id))}
                        >
                          {text(r.order_number)}
                        </button>
                        <span>{text(r.product)}</span>
                      </div>
                    ) : (
                      <div className="cell-stack">
                        <strong>Planned {text(r.product_sku)}</strong>
                        <span>for {text(r.drivers) || 'safety stock'}</span>
                      </div>
                    ),
                },
                { label: 'Quantity', render: (r) => `${fmt(r.quantity, 6)} ${row.uom}` },
                {
                  label: 'Uncovered',
                  render: (r) =>
                    num(r.uncovered) > 0 ? (
                      <span className="negative weight-semibold">{fmt(r.uncovered, 6)}</span>
                    ) : (
                      <span className="positive">Covered</span>
                    ),
                },
                { label: 'Need date', render: (r) => date(r.need_date) },
              ]}
            />
          </div>
        )}
      </div>
      <div className="dialog-footer">
        <button type="button" className="button secondary" onClick={onClose}>
          Close
        </button>
      </div>
    </Overlay>
  );
}

/** Route `/planning`: multi-level material requirements planning (MRP, V7). */
export default function PlanningPage() {
  const workspace = useWorkspace();
  const { can } = workspace;
  const plan = useMrpPlan();
  const [view, setView] = useState<View>('materials');
  const [pegging, setPegging] = useState<MrpRow>();
  const p = plan.data;
  const rows = p?.rows || [];
  const byId = new Map(rows.map((r) => [r.id, r]));
  const planned = new Map((p?.plannedProduction || []).map((r) => [r.id, r]));
  const purchases = new Map((p?.purchases || []).map((r) => [r.id, r]));
  const sortedRows = [...rows].sort(
    (a, b) => a.level - b.level || num(b.shortage) - num(a.shortage) || a.sku.localeCompare(b.sku),
  );
  const flat = (r: MrpRow): Row => {
    const { pegging: list, ...rest } = r;
    return { ...rest, pegs: list.length };
  };
  const createMo = (r: PlannedProduction) => createPlannedOrder(workspace, r);
  const purchase = (r: PurchaseRecommendation) =>
    createPurchase(workspace, { id: r.id, shortage: r.quantity });

  function action(id: string) {
    const r = byId.get(id)!;
    if (r.action === 'PRODUCE' && planned.has(id))
      return can('PLANNER') ? (
        <button className="button secondary small" onClick={() => createMo(planned.get(id)!)}>
          Create MO
          <Factory size={13} />
        </button>
      ) : (
        <span className="muted">Plan production</span>
      );
    if ((r.action === 'PURCHASE' || r.action === 'NO_BOM') && purchases.has(id))
      return can('PLANNER', 'WAREHOUSE') ? (
        <div className="cell-stack">
          <button className="button secondary small" onClick={() => purchase(purchases.get(id)!)}>
            Create purchase
            <ArrowUpRight size={13} />
          </button>
          {r.action === 'NO_BOM' && <span>no approved BOM</span>}
        </div>
      ) : (
        '—'
      );
    if (r.action === 'REVIEW') return <span className="muted">Inactive item</span>;
    return '—';
  }

  const levelColumn = {
    label: 'Level',
    key: 'level',
    render: (r: Row) => <span className="level-pill">L{text(r.level)}</span>,
  };
  const materialColumn = {
    label: 'Material',
    key: 'name',
    render: (r: Row) => (
      <div className="cell-stack">
        <strong>{text(r.name)}</strong>
        <span>
          {text(r.sku)} · {text(r.uom)}
          {num(r.lead_time_days) > 0 ? ` · ${text(r.lead_time_days)} day lead time` : ''}
        </span>
      </div>
    ),
  };

  return (
    <>
      <div className="info-banner">
        <Layers size={20} />
        <div>
          <strong>Multi-level material requirements planning</strong>
          <p>
            Open order demand explodes level by level: semi-finished requirements are netted against released
            stock, stock in inspection, open (child) orders and safety stock; what remains becomes planned
            production, whose materials join the next level&apos;s demand and are netted against stock and
            incoming purchases. Infinite capacity, lot-for-lot.
          </p>
        </div>
      </div>
      {plan.isPending ? (
        <Panel>
          <div className="mrp-state">
            <LoaderCircle className="spin" />
          </div>
        </Panel>
      ) : plan.isError ? (
        <Panel>
          <div className="compare-body">
            <div className="form-error" role="alert">
              {(plan.error as Error).message}
            </div>
            <div className="structure-actions">
              <button className="button secondary small" onClick={() => plan.refetch()}>
                Try again
              </button>
            </div>
          </div>
        </Panel>
      ) : (
        p && (
          <>
            <motion.div
              className="stats-grid mrp-stats"
              variants={stagger}
              initial="hidden"
              animate="visible"
            >
              <Stat
                label="Planning levels"
                value={fmt(p.summary.levels)}
                icon={ListTree}
                note={`${fmt(rows.length)} materials planned`}
              />
              <Stat
                label="Planned production"
                value={fmt(p.summary.plannedProduction)}
                icon={Factory}
                note="Semi-finished items to make"
                tone={p.summary.plannedProduction ? 'amber' : 'green'}
              />
              <Stat
                label="Purchase recommendations"
                value={fmt(p.summary.purchases)}
                icon={ShoppingCart}
                note="Bought-in items to order"
                tone={p.summary.purchases ? 'amber' : 'green'}
              />
              <Stat
                label="Late starts"
                value={fmt(p.summary.late)}
                icon={CalendarClock}
                note="Start or order date already passed"
                tone={p.summary.late ? 'amber' : 'green'}
              />
            </motion.div>
            <Panel
              title="Material readiness"
              subtitle={`${p.summary.shortages} items need replenishment. Production needs an independent approval; purchases need a second person's approval.`}
              action={plan.isFetching ? <LoaderCircle className="spin" size={16} /> : undefined}
            >
              <div className="mrp-panel-body">
                <div className="table-toolbar">
                  <Tabs
                    compact
                    label="Planning view"
                    value={view}
                    onChange={(v) => setView(v as View)}
                    items={[
                      { value: 'materials', label: 'All materials', count: rows.length },
                      { value: 'production', label: 'Planned production', count: p.plannedProduction.length },
                      { value: 'purchases', label: 'Purchase recommendations', count: p.purchases.length },
                    ]}
                  />
                </div>
                {view === 'materials' &&
                  (rows.length === 0 ? (
                    <Empty
                      title="Nothing to plan yet"
                      body="Raw materials, packaging and semi-finished items appear here with their demand and supply."
                    />
                  ) : (
                    <Table
                      rows={sortedRows.map(flat)}
                      search
                      placeholder="Search materials…"
                      pageSize={10}
                      columns={[
                        levelColumn,
                        materialColumn,
                        {
                          label: 'Type',
                          render: (r) => (
                            <span className="cell-stack">
                              <ItemTypeBadge type={r.type} />
                              {r.active === false && <span>inactive</span>}
                            </span>
                          ),
                        },
                        {
                          label: 'Gross demand',
                          render: (r) => (
                            <div className="cell-stack">
                              <strong>{fmt(r.gross, 3)}</strong>
                              {num(r.dependent_demand) > 0 && (
                                <span>
                                  {fmt(r.independent_demand, 3)} orders · {fmt(r.dependent_demand, 3)} planned
                                </span>
                              )}
                            </div>
                          ),
                        },
                        {
                          label: 'Supply',
                          render: (r) => (
                            <div className="cell-stack">
                              <strong>
                                {fmt(
                                  num(r.available) + num(r.inspection) + num(r.on_order) + num(r.incoming),
                                  3,
                                )}
                              </strong>
                              <span>
                                {fmt(r.available, 3)} stock
                                {num(r.inspection) > 0 ? ` · ${fmt(r.inspection, 3)} in QC` : ''}
                                {num(r.on_order) > 0 ? ` · ${fmt(r.on_order, 3)} on MO` : ''}
                                {num(r.incoming) > 0 ? ` · ${fmt(r.incoming, 3)} on PO` : ''}
                              </span>
                            </div>
                          ),
                        },
                        { label: 'Safety stock', render: (r) => fmt(r.safety, 3) },
                        {
                          label: 'Net shortage',
                          key: 'shortage',
                          render: (r) => (
                            <span className={num(r.shortage) > 0 ? 'negative weight-semibold' : 'positive'}>
                              {num(r.shortage) > 0 ? `${fmt(r.shortage, 3)} ${r.uom}` : 'Covered'}
                            </span>
                          ),
                        },
                        {
                          label: 'Dates',
                          render: (r) => (
                            <When
                              need={r.need_date}
                              start={r.start_date}
                              late={r.late === true}
                              startLabel={r.action === 'PRODUCE' ? 'Start' : 'Order'}
                            />
                          ),
                        },
                        {
                          label: 'Pegging',
                          render: (r) =>
                            num(r.pegs) > 0 ? (
                              <button
                                className="text-button"
                                onClick={() => setPegging(byId.get(r.id))}
                                aria-label={`Pegging of ${text(r.sku)}`}
                              >
                                {fmt(r.pegs)} {num(r.pegs) === 1 ? 'requirement' : 'requirements'}
                              </button>
                            ) : (
                              <span className="muted">—</span>
                            ),
                        },
                        { label: 'Action', render: (r) => action(r.id) },
                      ]}
                    />
                  ))}
                {view === 'production' &&
                  (p.plannedProduction.length === 0 ? (
                    <Empty
                      title="No planned production"
                      body="Released stock, open child orders and safety stock cover every semi-finished requirement."
                    />
                  ) : (
                    <Table
                      rows={p.plannedProduction.map((r) => ({
                        ...r,
                        drivers: r.drivers.join(', '),
                        parent_orders: r.parent_orders.length,
                      }))}
                      pageSize={10}
                      columns={[
                        levelColumn,
                        materialColumn,
                        {
                          label: 'Planned quantity',
                          render: (r) => (
                            <strong>
                              {fmt(r.quantity, 6)} {text(r.uom)}
                            </strong>
                          ),
                        },
                        { label: 'BOM', render: (r) => text(r.bom_revision) },
                        {
                          label: 'Dates',
                          render: (r) => (
                            <When
                              need={r.need_date}
                              start={r.start_date}
                              late={r.late === true}
                              startLabel="Start"
                            />
                          ),
                        },
                        {
                          label: 'Driven by',
                          render: (r) => (
                            <Drivers
                              numbers={planned.get(r.id)!.drivers}
                              safety={planned.get(r.id)!.safety_shortfall}
                            />
                          ),
                        },
                        {
                          label: 'Action',
                          render: (r) =>
                            can('PLANNER') ? (
                              <button
                                className="button secondary small"
                                onClick={() => createMo(planned.get(r.id)!)}
                              >
                                Create MO
                                <Factory size={13} />
                              </button>
                            ) : (
                              '—'
                            ),
                        },
                      ]}
                    />
                  ))}
                {view === 'purchases' &&
                  (p.purchases.length === 0 ? (
                    <Empty
                      title="No purchase recommendations"
                      body="Stock and incoming purchases cover every bought-in requirement at every level."
                    />
                  ) : (
                    <Table
                      rows={p.purchases.map((r) => ({ ...r, drivers: r.drivers.join(', ') }))}
                      pageSize={10}
                      columns={[
                        levelColumn,
                        materialColumn,
                        {
                          label: 'Recommended quantity',
                          render: (r) => (
                            <div className="cell-stack">
                              <strong>
                                {fmt(r.quantity, 6)} {text(r.uom)}
                              </strong>
                              {num(r.reorder_qty) > num(r.quantity) && (
                                <span>reorder quantity {fmt(r.reorder_qty, 3)}</span>
                              )}
                            </div>
                          ),
                        },
                        {
                          label: 'Dates',
                          render: (r) => (
                            <When
                              need={r.need_date}
                              start={r.order_date}
                              late={r.late === true}
                              startLabel="Order"
                            />
                          ),
                        },
                        {
                          label: 'Driven by',
                          render: (r) => (
                            <Drivers
                              numbers={purchases.get(r.id)!.drivers}
                              safety={purchases.get(r.id)!.safety_shortfall}
                            />
                          ),
                        },
                        {
                          label: 'Action',
                          render: (r) =>
                            can('PLANNER', 'WAREHOUSE') ? (
                              <button
                                className="button secondary small"
                                onClick={() => purchase(purchases.get(r.id)!)}
                              >
                                Create purchase
                                <ArrowUpRight size={13} />
                              </button>
                            ) : (
                              '—'
                            ),
                        },
                      ]}
                    />
                  ))}
              </div>
            </Panel>
          </>
        )
      )}
      <AnimatePresence>
        {pegging && <PeggingDialog key="pegging" row={pegging} onClose={() => setPegging(undefined)} />}
      </AnimatePresence>
    </>
  );
}
