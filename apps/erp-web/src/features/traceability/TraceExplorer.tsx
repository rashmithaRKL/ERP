import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { GitBranch, LoaderCircle, Search, X } from 'lucide-react';
import { Empty } from '../../components';
import { fmt, text, today } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { LotDrawer } from '../inventory/LotDrawer';
import { QueryState } from '../inventory/shared';
import { useDebounced } from '../quality/shared';
import { KIND_LABELS, TRACE_TYPES, useTrace, useTraceSearch } from './api';
import type { Direction, SearchHit, TraceRoot } from './api';
import { GenealogyGraph } from './GenealogyGraph';
import { GenealogyTree } from './GenealogyTree';
import { NodeDetails } from './NodeDetails';
import { KindIcon } from './shared';

const DEPTHS = [3, 6, 12, 20, 30];
/** Above this many nodes the explorer opens in the tree view (the graph stays one click away). */
const LARGE = 160;

const daysAgo = (days: number) => {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().substring(0, 10);
};

/**
 * Genealogy explorer: search an entry point (lot, MO, goods receipt, PO, SO, delivery, supplier + receipt dates), then
 * read where it came from (left) and where it went (right) as a graph or an indented tree, with node details.
 */
export function TraceExplorer({
  root,
  onRoot,
  onRecall,
}: {
  root: TraceRoot | null;
  onRoot: (root: TraceRoot | null) => void;
  onRecall: (lotId: string, lotNumber: string) => void;
}) {
  const [input, setInput] = useState('');
  const q = useDebounced(input);
  const search = useTraceSearch(q);
  const [direction, setDirection] = useState<Direction>('BOTH');
  const [depth, setDepth] = useState(12);
  const [view, setView] = useState<'graph' | 'tree'>('graph');
  const [selected, setSelected] = useState<string>();
  const [lot, setLot] = useState<string>();
  const [supplierHit, setSupplierHit] = useState<SearchHit>();
  const [from, setFrom] = useState(daysAgo(90));
  const [to, setTo] = useState(today());
  const trace = useTrace(root, direction, depth);
  const data = trace.data;
  const large = (data?.nodes.length ?? 0) > LARGE;

  useEffect(() => {
    setSelected(undefined);
  }, [root?.type, root?.id, root?.from, root?.to]);
  useEffect(() => {
    if (large) setView('tree');
  }, [large]);

  const node = useMemo(() => data?.nodes.find((n) => n.id === selected), [data, selected]);

  function pick(hit: SearchHit) {
    setInput('');
    if (hit.type === 'SUPPLIER') {
      setSupplierHit(hit);
      onRoot({ type: 'SUPPLIER', id: hit.id, label: hit.label, from, to });
    } else {
      setSupplierHit(undefined);
      onRoot({ type: hit.type, id: hit.id, label: hit.label });
    }
  }
  function applyDates(nextFrom: string, nextTo: string) {
    setFrom(nextFrom);
    setTo(nextTo);
    if (root?.type === 'SUPPLIER' && nextFrom && nextTo && nextFrom <= nextTo)
      onRoot({ ...root, from: nextFrom, to: nextTo });
  }

  const hits = search.data?.items ?? [];
  const summary = data?.summary;
  return (
    <div className="trace-explorer">
      <div className="server-toolbar trace-toolbar">
        <div className="trace-search">
          <div className="search-input">
            <Search size={16} />
            <input
              aria-label="Find a lot, order, receipt, delivery or supplier"
              placeholder="Lot, MO, GRN, PO, SO, delivery or supplier…"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && hits.length) pick(hits[0]);
                if (e.key === 'Escape') setInput('');
              }}
            />
            {search.isFetching && <LoaderCircle size={14} className="spin" aria-hidden="true" />}
          </div>
          {q.length >= 2 && input.trim().length >= 2 && (
            <div className="trace-hits" role="listbox" aria-label="Matching entry points">
              {search.isError ? (
                <p className="form-error">{(search.error as Error).message}</p>
              ) : hits.length ? (
                hits.map((h) => (
                  <button
                    key={`${h.type}-${h.id}`}
                    type="button"
                    role="option"
                    aria-selected={false}
                    onClick={() => pick(h)}
                  >
                    <KindIcon
                      kind={
                        h.type === 'MO'
                          ? 'MANUFACTURING_ORDER'
                          : h.type === 'PO'
                            ? 'PURCHASE_ORDER'
                            : h.type === 'SO'
                              ? 'SALES_ORDER'
                              : h.type === 'RECEIPT'
                                ? 'GOODS_RECEIPT'
                                : h.type
                      }
                    />
                    <span>
                      <strong>{h.label}</strong>
                      <small>
                        {TRACE_TYPES.find((t) => t.value === h.type)?.label} · {h.detail}
                      </small>
                    </span>
                  </button>
                ))
              ) : search.isPending ? (
                <p className="muted inv-small">Searching…</p>
              ) : (
                <p className="muted inv-small">No lot, order, receipt, delivery or supplier matches “{q}”.</p>
              )}
            </div>
          )}
        </div>
        <select
          aria-label="Direction"
          value={direction}
          onChange={(e) => setDirection(e.target.value as Direction)}
        >
          <option value="BOTH">Backward and forward</option>
          <option value="BACKWARD">Backward: where it came from</option>
          <option value="FORWARD">Forward: where it went</option>
        </select>
        <select aria-label="Depth" value={depth} onChange={(e) => setDepth(Number(e.target.value))}>
          {DEPTHS.map((d) => (
            <option key={d} value={d}>
              {d} lot levels
            </option>
          ))}
        </select>
      </div>
      {root?.type === 'SUPPLIER' && (
        <div className="trace-dates">
          <span>
            Lots received from <strong>{root.label ?? supplierHit?.label}</strong> between
          </span>
          <input
            type="date"
            aria-label="First receipt date"
            value={from}
            max={to}
            onChange={(e) => applyDates(e.target.value, to)}
          />
          <span>and</span>
          <input
            type="date"
            aria-label="Last receipt date"
            value={to}
            min={from}
            onChange={(e) => applyDates(from, e.target.value)}
          />
        </div>
      )}
      {!root ? (
        <Empty
          title="Trace any lot, order or shipment"
          body="Search a lot number, manufacturing order, goods receipt, purchase or sales order, delivery reference or supplier to see where its material came from and where it went."
        />
      ) : (
        <QueryState pending={trace.isPending} error={trace.error} retry={() => trace.refetch()}>
          {data && (
            <>
              <div className="trace-heading">
                <div>
                  <span>{KIND_LABELS[data.root.kind] ?? data.root.kind}</span>
                  <h2>
                    <GitBranch size={16} aria-hidden="true" /> {text(data.root.label)}
                  </h2>
                  <p>{text(data.root.detail)}</p>
                </div>
                <button className="icon-button" aria-label="Clear the trace" onClick={() => onRoot(null)}>
                  <X size={16} />
                </button>
              </div>
              <div className="trace-summary" aria-label="Trace summary">
                <span className="inv-chip">
                  <strong>{fmt(summary?.lots)}</strong> lots
                </span>
                <span className="inv-chip">
                  <strong>{fmt(summary?.inStockLots)}</strong> in stock
                </span>
                <span className="inv-chip">
                  <strong>{fmt(summary?.orders)}</strong> manufacturing orders
                </span>
                <span className="inv-chip">
                  <strong>{fmt(summary?.deliveries)}</strong> deliveries
                </span>
                <span className="inv-chip">
                  <strong>{fmt(summary?.customers)}</strong> customers
                </span>
                <span className="inv-chip">
                  <strong>{fmt(summary?.suppliers)}</strong> suppliers
                </span>
                {trace.isFetching && <LoaderCircle size={14} className="spin" aria-label="Refreshing" />}
              </div>
              {data.truncated && (
                <div className="inv-warning">
                  This trace is larger than one screen can show; it was cut at {fmt(2000)} lots per direction.
                  Trace from a node further along to see the rest.
                </div>
              )}
              <Tabs
                compact
                label="Genealogy view"
                items={[
                  { value: 'graph', label: 'Graph' },
                  { value: 'tree', label: 'Indented tree' },
                ]}
                value={view}
                onChange={(v) => setView(v as 'graph' | 'tree')}
              />
              {large && view === 'graph' && (
                <p className="inv-small muted trace-hint">
                  {fmt(data.nodes.length)} nodes: scroll the graph, or use the indented tree to read it level
                  by level.
                </p>
              )}
              <div className={`trace-body ${node ? 'with-details' : ''}`}>
                <div className="trace-main">
                  {data.nodes.length <= 1 && !data.edges.length ? (
                    <Empty
                      title="No genealogy links"
                      body="Nothing was received into, made from, moved or shipped from it yet."
                    />
                  ) : view === 'graph' ? (
                    <GenealogyGraph trace={data} selected={selected} onSelect={setSelected} />
                  ) : (
                    <GenealogyTree trace={data} selected={selected} onSelect={setSelected} />
                  )}
                </div>
                {node && (
                  <NodeDetails
                    trace={data}
                    node={node}
                    onSelect={setSelected}
                    onClose={() => setSelected(undefined)}
                    onTrace={(r) => onRoot(r.type === 'SUPPLIER' ? { ...r, from, to } : r)}
                    onOpenLot={setLot}
                    onRecall={onRecall}
                  />
                )}
              </div>
            </>
          )}
        </QueryState>
      )}
      <AnimatePresence>
        {lot && <LotDrawer key={lot} id={lot} onClose={() => setLot(undefined)} onOpen={setLot} />}
      </AnimatePresence>
    </div>
  );
}
