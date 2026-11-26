import { useEffect, useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { useTabParam, useUrlParams } from '../app/urlState';
import { useStateData } from '../app/workspace';
import { Panel } from '../components';
import type { TraceRoot, TraceType } from '../features/traceability/api';
import { TRACE_TYPES } from '../features/traceability/api';
import { OpenRecallDialog } from '../features/traceability/OpenRecallDialog';
import type { SuspectLot } from '../features/traceability/OpenRecallDialog';
import { RecallDrawer } from '../features/traceability/RecallDrawer';
import { RecallsTab } from '../features/traceability/RecallsTab';
import { TraceExplorer } from '../features/traceability/TraceExplorer';
import '../features/quality/quality.css';
import '../features/traceability/traceability.css';
import { fmt, num } from '../lib/format';
import { Tabs } from '../motion/motion';

const VIEWS = ['genealogy', 'recalls'];

/** The explorer's entry point in the URL: `?lot=` or `?type=&id=` (+ `from`/`to` for a supplier). */
function rootFrom(params: URLSearchParams): TraceRoot | null {
  if (params.get('lot')) return { type: 'LOT', id: params.get('lot')! };
  const type = (params.get('type') ?? '').toUpperCase() as TraceType;
  const id = params.get('id');
  if (id && TRACE_TYPES.some((t) => t.value === type))
    return { type, id, from: params.get('from') ?? undefined, to: params.get('to') ?? undefined };
  return null;
}

/** The URL parameters of an entry point (all of them cleared for none). */
function rootParams(root: TraceRoot | null): Record<string, string | null> {
  if (!root) return { lot: null, type: null, id: null, from: null, to: null };
  if (root.type === 'LOT') return { lot: root.id, type: null, id: null, from: null, to: null };
  return { lot: null, type: root.type, id: root.id, from: root.from ?? null, to: root.to ?? null };
}

const sameRoot = (a: TraceRoot | null, b: TraceRoot | null) =>
  a?.type === b?.type && a?.id === b?.id && a?.from === b?.from && a?.to === b?.to;

/**
 * Route `/traceability` (V14): the genealogy explorer (backward ← entry point → forward, as a graph or an indented
 * tree) and recalls (list, drawer, open/contain/notify/export/close). Everyone can trace and read recalls;
 * QUALITY/ADMIN act on them. Deep links: `?lot=`, `?type=&id=`, `?tab=recalls&recall=`; the URL follows the tab, the open
 * recall and the traced entry point (V16), so links work while the page is open and every view can be shared.
 */
export default function TraceabilityPage() {
  const data = useStateData();
  const [view, setView] = useTabParam(VIEWS, 'genealogy');
  const [params, update] = useUrlParams();
  const recall = params.get('recall') || undefined;
  // The root keeps its display label locally; a different root in the URL (a link followed) replaces it.
  const [root, setRoot] = useState<TraceRoot | null>(() => rootFrom(params));
  const [opening, setOpening] = useState<SuspectLot[] | null>(null);
  const linked = rootFrom(params);
  useEffect(() => {
    setRoot((current) => (sameRoot(current, linked) ? current : linked));
    // Compared by value: `rootFrom` returns a new object on every render.
  }, [linked?.type, linked?.id, linked?.from, linked?.to]);
  const alerts = data.recalls;
  const changeRoot = (next: TraceRoot | null) => {
    setRoot(next);
    update(rootParams(next));
  };
  const trace = (next: TraceRoot) => {
    setRoot(next);
    update({ ...rootParams(next), tab: null, recall: null });
  };
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Recalls not yet contained</span>
          <strong className={num(alerts?.open) ? 'negative' : undefined}>
            {alerts ? fmt(alerts.open) : '—'}
          </strong>
        </div>
        <div>
          <span>Contained, still open</span>
          <strong>{alerts ? fmt(alerts.contained) : '—'}</strong>
        </div>
        <div>
          <span>Customers to notify</span>
          <strong className={num(alerts?.pending_customers) ? 'inv-attention' : undefined}>
            {alerts ? fmt(alerts.pending_customers) : '—'}
          </strong>
        </div>
      </div>
      <Panel className="server-table quality-panel trace-panel">
        <Tabs
          label="Traceability views"
          items={[
            { value: 'genealogy', label: 'Genealogy' },
            {
              value: 'recalls',
              label: 'Recalls',
              count: num(alerts?.open) + num(alerts?.contained) || undefined,
            },
          ]}
          value={view}
          onChange={setView}
        />
        {view === 'genealogy' && (
          <TraceExplorer
            root={root}
            onRoot={changeRoot}
            onRecall={(id, number) => setOpening([{ id, number }])}
          />
        )}
        {view === 'recalls' && (
          <RecallsTab onOpen={(id) => update({ recall: id })} onNew={() => setOpening([])} />
        )}
      </Panel>
      <AnimatePresence>
        {recall && (
          <RecallDrawer key={recall} id={recall} onClose={() => update({ recall: null })} onTrace={trace} />
        )}
        {opening && (
          <OpenRecallDialog
            key="open-recall"
            lots={opening}
            onClose={() => setOpening(null)}
            onOpened={(id) => {
              setOpening(null);
              update({ tab: 'recalls', recall: id });
            }}
          />
        )}
      </AnimatePresence>
    </>
  );
}
