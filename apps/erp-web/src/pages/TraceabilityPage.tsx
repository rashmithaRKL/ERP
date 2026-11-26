import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { AnimatePresence } from 'motion/react';
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

/** `?lot=` or `?type=&id=` from a deep link (lot drawer "Trace", recall links). */
function rootFrom(params: URLSearchParams): TraceRoot | null {
  if (params.get('lot')) return { type: 'LOT', id: params.get('lot')! };
  const type = (params.get('type') ?? '').toUpperCase() as TraceType;
  const id = params.get('id');
  if (id && TRACE_TYPES.some((t) => t.value === type))
    return { type, id, from: params.get('from') ?? undefined, to: params.get('to') ?? undefined };
  return null;
}

/**
 * Route `/traceability` (V14): the genealogy explorer (backward ← entry point → forward, as a graph or an indented
 * tree) and recalls (list, drawer, open/contain/notify/export/close). Everyone can trace and read recalls;
 * QUALITY/ADMIN act on them. Deep links: `?lot=`, `?type=&id=`, `?tab=recalls&recall=`.
 */
export default function TraceabilityPage() {
  const data = useStateData();
  const [params] = useSearchParams();
  const [view, setView] = useState(
    VIEWS.includes(params.get('tab') ?? '') ? params.get('tab')! : 'genealogy',
  );
  const [root, setRoot] = useState<TraceRoot | null>(() => rootFrom(params));
  const [recall, setRecall] = useState<string | undefined>(params.get('recall') ?? undefined);
  const [opening, setOpening] = useState<SuspectLot[] | null>(null);
  useEffect(() => {
    const next = rootFrom(params);
    if (next) {
      setRoot(next);
      setView('genealogy');
    }
    const tab = params.get('tab');
    if (tab && VIEWS.includes(tab)) setView(tab);
    if (params.get('recall')) setRecall(params.get('recall')!);
  }, [params]);
  const alerts = data.recalls;
  const trace = (next: TraceRoot) => {
    setRoot(next);
    setRecall(undefined);
    setView('genealogy');
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
            onRoot={setRoot}
            onRecall={(id, number) => setOpening([{ id, number }])}
          />
        )}
        {view === 'recalls' && <RecallsTab onOpen={setRecall} onNew={() => setOpening([])} />}
      </Panel>
      <AnimatePresence>
        {recall && (
          <RecallDrawer key={recall} id={recall} onClose={() => setRecall(undefined)} onTrace={trace} />
        )}
        {opening && (
          <OpenRecallDialog
            key="open-recall"
            lots={opening}
            onClose={() => setOpening(null)}
            onOpened={(id) => {
              setOpening(null);
              setView('recalls');
              setRecall(id);
            }}
          />
        )}
      </AnimatePresence>
    </>
  );
}
