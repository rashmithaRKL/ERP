import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { Plus } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Progress, Table } from '../../components';
import { date, fmt, labelState, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { DECIDERS, NCR_SOURCES, SEVERITIES, useDefectCodes } from './api';
import { RaiseNcrDialog } from './RaiseNcrDialog';
import { QualityBadge, QualitySearch, useDebounced } from './shared';

const PAGE = 25;
const STATUS_TABS = ['active', 'OPEN', 'DISPOSITIONED', 'CLOSED', 'CANCELLED', 'all'];

/** Nonconformance reports (`GET /api/quality/ncrs`): status tabs, severity/source/defect filters and search. */
export function NcrTab({ onOpen }: { onOpen: (id: string) => void }) {
  const { can } = useWorkspace();
  const [status, setStatus] = useState('active');
  const [severity, setSeverity] = useState('');
  const [source, setSource] = useState('');
  const [defect, setDefect] = useState('');
  const [input, setInput] = useState('');
  const [raising, setRaising] = useState(false);
  const q = useDebounced(input);
  const defects = useDefectCodes();
  const paged = useCursorPage(
    ['quality', 'ncrs'],
    '/quality/ncrs',
    { status, severity, source, defect, q },
    PAGE,
  );
  const counts = paged.query.data?.counts || {};
  const loaded = !!paged.query.data;
  return (
    <>
      <Tabs
        compact
        label="Nonconformance status"
        items={STATUS_TABS.map((t) => ({
          value: t,
          label: t === 'active' ? 'Open & dispositioned' : t === 'all' ? 'All' : labelState(t),
          count: !loaded
            ? undefined
            : t === 'active'
              ? num(counts.OPEN) + num(counts.DISPOSITIONED)
              : t === 'all'
                ? undefined
                : num(counts[t]),
        }))}
        value={status}
        onChange={setStatus}
      />
      <div className="server-toolbar">
        <QualitySearch value={input} onChange={setInput} placeholder="Search NCR, title, lot or item…" />
        <select aria-label="Severity" value={severity} onChange={(e) => setSeverity(e.target.value)}>
          <option value="">All severities</option>
          {SEVERITIES.map((s) => (
            <option key={s} value={s}>
              {labelState(s)}
            </option>
          ))}
        </select>
        <select aria-label="Source" value={source} onChange={(e) => setSource(e.target.value)}>
          <option value="">All sources</option>
          {NCR_SOURCES.map((s) => (
            <option key={s} value={s}>
              {labelState(s)}
            </option>
          ))}
        </select>
        <select aria-label="Defect code" value={defect} onChange={(e) => setDefect(e.target.value)}>
          <option value="">All defects</option>
          {(defects.data?.items ?? []).map((d) => (
            <option key={text(d.code)} value={text(d.code)}>
              {text(d.code)} · {text(d.name)}
            </option>
          ))}
        </select>
        {can(...DECIDERS) && (
          <button className="button primary small qa-toolbar-action" onClick={() => setRaising(true)}>
            <Plus size={14} />
            Raise NCR
          </button>
        )}
      </div>
      <PageState paged={paged}>
        {paged.rows.length ? (
          <Table
            rows={paged.rows}
            compact
            pageSize={PAGE}
            label="Nonconformance reports"
            onRow={(r) => onOpen(r.id)}
            columns={[
              {
                label: 'NCR',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.number)}</strong>
                    <span className="notes-cell">{text(r.title)}</span>
                  </div>
                ),
              },
              {
                label: 'Item / lot',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.item) || '—'}</strong>
                    <span>{text(r.lot) || labelState(r.source)}</span>
                  </div>
                ),
              },
              { label: 'Severity', render: (r) => <QualityBadge value={r.severity} /> },
              {
                label: 'Defect',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.defect_code)}</strong>
                    <span>{text(r.defect_name)}</span>
                  </div>
                ),
              },
              {
                label: 'Dispositioned',
                render: (r) =>
                  num(r.quantity) > 0 ? (
                    <div className="table-progress">
                      <Progress value={(num(r.dispositioned) / num(r.quantity)) * 100} />
                      <span>
                        {fmt(r.dispositioned, 3)} / {fmt(r.quantity, 3)} {text(r.uom)}
                        {num(r.pending) > 0 ? ` · ${fmt(r.pending, 3)} pending` : ''}
                      </span>
                    </div>
                  ) : (
                    '—'
                  ),
              },
              { label: 'Status', render: (r) => <QualityBadge value={r.status} /> },
              {
                label: 'Raised',
                render: (r) => (
                  <div className="cell-stack">
                    <span>{date(r.created_at)}</span>
                    <span>{text(r.raised_by)}</span>
                  </div>
                ),
              },
            ]}
          />
        ) : (
          <Empty
            title="No nonconformances here"
            body="Failed inspections raise an NCR automatically; quality can also raise one against a lot."
          />
        )}
      </PageState>
      <PageFooter paged={paged} label="nonconformances" />
      <AnimatePresence>
        {raising && (
          <RaiseNcrDialog
            key="raise"
            onClose={() => setRaising(false)}
            onRaised={(id) => {
              setRaising(false);
              onOpen(id);
            }}
          />
        )}
      </AnimatePresence>
    </>
  );
}
