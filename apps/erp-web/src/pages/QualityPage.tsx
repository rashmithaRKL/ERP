import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { AnimatePresence } from 'motion/react';
import { useStateData } from '../app/workspace';
import { Panel } from '../components';
import { useCursorPage } from '../features/lists/cursorPage';
import { useQualityQuery, usePeople } from '../features/quality/api';
import type { QualityAlerts, QualitySummary } from '../features/quality/api';
import { CapaDrawer } from '../features/quality/CapaDrawer';
import { CapaTab } from '../features/quality/CapaTab';
import { InspectionsTab } from '../features/quality/InspectionsTab';
import { NcrDrawer } from '../features/quality/NcrDrawer';
import { NcrTab } from '../features/quality/NcrTab';
import { PlansTab } from '../features/quality/PlansTab';
import { ReworkDrawer } from '../features/quality/ReworkDrawer';
import { ReworkTab } from '../features/quality/ReworkTab';
import { useDebounced } from '../features/quality/shared';
import '../features/quality/quality.css';
import { fmt, money, num, text } from '../lib/format';
import { Tabs } from '../motion/motion';

const PAGE = 25;
const VIEWS = ['inspections', 'ncrs', 'rework', 'capa', 'plans'];

/**
 * Route `/quality`: inspection queue with plan-driven measurements (V9 paged list), nonconformances with
 * dispositions, rework orders, CAPA and inspection plans (V12). `?tab=` and `?ncr=` / `?capa=` deep-link.
 */
export default function QualityPage() {
  const data = useStateData();
  const [params] = useSearchParams();
  const [view, setView] = useState(
    VIEWS.includes(params.get('tab') ?? '') ? params.get('tab')! : 'inspections',
  );
  const [ncr, setNcr] = useState<string | undefined>(params.get('ncr') ?? undefined);
  const [rework, setRework] = useState<string>();
  const [capa, setCapa] = useState<string | undefined>(params.get('capa') ?? undefined);
  // Deep links from the attention center and the lot drawer also work while this page is open.
  useEffect(() => {
    const tab = params.get('tab');
    if (tab && VIEWS.includes(tab)) setView(tab);
    if (params.get('ncr')) setNcr(params.get('ncr')!);
    if (params.get('capa')) setCapa(params.get('capa')!);
  }, [params]);
  // The inspection queue's filters live here: the summary strip reads its tenant-wide counts.
  const [tab, setTab] = useState('all');
  const [type, setType] = useState('');
  const [input, setInput] = useState('');
  const q = useDebounced(input);
  const inspections = useCursorPage(
    ['quality', 'inspections'],
    '/quality/inspections',
    { status: tab, type, q },
    PAGE,
  );
  const summary = useQualityQuery<QualitySummary>('summary', '/quality/summary');
  const people = usePeople().data?.items ?? [];
  const alerts = data.qualityAlerts as QualityAlerts | undefined;
  const counts = inspections.query.data?.counts || {};
  const s = summary.data;
  const open = (kind: 'ncr' | 'rework' | 'capa', id: string) => {
    setNcr(kind === 'ncr' ? id : undefined);
    setRework(kind === 'rework' ? id : undefined);
    setCapa(kind === 'capa' ? id : undefined);
  };
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Awaiting inspection</span>
          <strong>{inspections.query.data ? fmt(counts.PENDING) : '—'}</strong>
        </div>
        <div>
          <span>Open nonconformances</span>
          <strong className={num(alerts?.open_critical_ncrs) ? 'negative' : undefined}>
            {alerts ? fmt(alerts.open_ncrs) : '—'}
            {num(alerts?.open_critical_ncrs) > 0 && (
              <small> · {fmt(alerts?.open_critical_ncrs)} critical</small>
            )}
          </strong>
        </div>
        <div>
          <span>Quality cost, 30 days</span>
          <strong title={s ? `Scrap ${money(s.scrapValue)} · rework ${money(s.reworkCost)}` : undefined}>
            {s ? money(num(s.scrapValue) + num(s.reworkCost)) : '—'}
          </strong>
        </div>
        <div>
          <span>Overdue CAPA actions</span>
          <strong className={num(alerts?.overdue_capa_actions) ? 'inv-attention' : undefined}>
            {alerts ? fmt(alerts.overdue_capa_actions) : '—'}
          </strong>
        </div>
      </div>
      <Panel className="server-table quality-panel">
        <Tabs
          label="Quality views"
          items={[
            { value: 'inspections', label: 'Inspections', count: num(counts.PENDING) || undefined },
            { value: 'ncrs', label: 'Nonconformances', count: num(alerts?.open_ncrs) || undefined },
            { value: 'rework', label: 'Rework', count: num(alerts?.open_rework) || undefined },
            { value: 'capa', label: 'CAPA', count: num(alerts?.overdue_capa_actions) || undefined },
            { value: 'plans', label: 'Inspection plans' },
          ]}
          value={view}
          onChange={setView}
        />
        {view === 'inspections' && (
          <InspectionsTab
            paged={inspections}
            tab={tab}
            setTab={setTab}
            type={type}
            setType={setType}
            input={input}
            setInput={setInput}
            onOpenNcr={(id) => open('ncr', id)}
          />
        )}
        {view === 'ncrs' && (
          <>
            {s && s.topDefects.length > 0 && (
              <div className="qa-defect-strip" aria-label="Top defect codes, last 30 days">
                <span>Top defects, 30 days</span>
                {s.topDefects.map((d) => (
                  <span key={text(d.code)} className="inv-chip">
                    <strong>{text(d.code)}</strong> {text(d.name)} · {fmt(d.ncrs)}
                  </span>
                ))}
                {num(alerts?.pending_concessions) + num(alerts?.awaiting_returns) > 0 && (
                  <span className="qa-pending">
                    {fmt(alerts?.pending_concessions)} concession approvals · {fmt(alerts?.awaiting_returns)}{' '}
                    supplier returns to post
                  </span>
                )}
              </div>
            )}
            <NcrTab onOpen={(id) => open('ncr', id)} />
          </>
        )}
        {view === 'rework' && <ReworkTab onOpen={(id) => open('rework', id)} />}
        {view === 'capa' && <CapaTab people={people} onOpen={(id) => open('capa', id)} />}
        {view === 'plans' && <PlansTab />}
      </Panel>
      <AnimatePresence>
        {ncr && (
          <NcrDrawer
            key={`ncr-${ncr}`}
            id={ncr}
            people={people}
            onClose={() => setNcr(undefined)}
            onOpenRework={(id) => open('rework', id)}
            onOpenCapa={(id) => open('capa', id)}
          />
        )}
        {rework && (
          <ReworkDrawer
            key={`rework-${rework}`}
            id={rework}
            onClose={() => setRework(undefined)}
            onOpenNcr={(id) => open('ncr', id)}
          />
        )}
        {capa && (
          <CapaDrawer
            key={`capa-${capa}`}
            id={capa}
            people={people}
            onClose={() => setCapa(undefined)}
            onOpenNcr={(id) => open('ncr', id)}
          />
        )}
      </AnimatePresence>
    </>
  );
}
