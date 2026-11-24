import { useEffect, useState } from 'react';
import { ArrowRight, Search, ShieldCheck } from 'lucide-react';
import { useWorkspace } from '../app/workspace';
import { Badge, Panel, selectField, Table } from '../components';
import { PageFooter, PageState, useCursorPage } from '../features/lists/cursorPage';
import type { Row } from '../lib/api';
import { fmt, labelState, num, text } from '../lib/format';
import { Tabs } from '../motion/motion';

const PAGE = 25;
const TYPES = ['INCOMING', 'FINAL', 'RETURN', 'OPENING'];

/** Route `/quality`: server-paged inspection queue (`GET /api/quality/inspections`) and decisions. */
export default function QualityPage() {
  const { can, postForm } = useWorkspace();
  const [tab, setTab] = useState('all');
  const [type, setType] = useState('');
  const [input, setInput] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => {
    const timeout = setTimeout(() => setQ(input.trim()), 300);
    return () => clearTimeout(timeout);
  }, [input]);
  const paged = useCursorPage(
    ['quality', 'inspections'],
    '/quality/inspections',
    { status: tab, type, q },
    PAGE,
  );
  // Tenant-wide counts per decision (independent of the filters).
  const counts = paged.query.data?.counts || {};
  const loaded = !!paged.query.data;
  function inspect(r: Row) {
    // The shell's refresh() after the form also reloads this paged list (`['quality']` queries).
    postForm(
      'Record quality inspection',
      `/quality/${r.id}/inspect`,
      [
        selectField('result', 'Inspection decision', [
          { value: 'PASSED', label: 'Pass — release lot' },
          { value: 'FAILED', label: 'Fail — enforce quality hold / incoming return' },
        ]),
        { name: 'notes', label: 'Measurements, findings & reason', type: 'textarea' },
      ],
      `${text(r.item)} · Lot ${text(r.lot)}. Finalized inspection decisions are auditable.`,
      'Finalize inspection',
    );
  }
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Awaiting inspection</span>
          <strong>{loaded ? fmt(counts.PENDING) : '—'}</strong>
        </div>
        <div>
          <span>Passed inspections</span>
          <strong>{loaded ? fmt(counts.PASSED) : '—'}</strong>
        </div>
        <div>
          <span>Failed inspections</span>
          <strong>{loaded ? fmt(counts.FAILED) : '—'}</strong>
        </div>
        <div>
          <span>Quality gate</span>
          <strong className="positive">
            Enforced
            <ShieldCheck size={19} />
          </strong>
        </div>
      </div>
      <Panel className="server-table">
        <Tabs
          label="Quality inspection filters"
          items={['all', 'PENDING', 'PASSED', 'FAILED'].map((t) => ({
            value: t,
            label: t === 'all' ? 'All inspections' : labelState(t),
            count: !loaded
              ? undefined
              : t === 'all'
                ? num(counts.PENDING) + num(counts.PASSED) + num(counts.FAILED)
                : num(counts[t]),
          }))}
          value={tab}
          onChange={setTab}
        />
        <div className="server-toolbar">
          <div className="search-input">
            <Search size={16} />
            <input
              aria-label="Search inspections"
              placeholder="Search lot, item, SKU or notes…"
              value={input}
              onChange={(e) => setInput(e.target.value)}
            />
          </div>
          <select aria-label="Inspection type" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">All inspection types</option>
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {labelState(t)}
              </option>
            ))}
          </select>
        </div>
        <PageState paged={paged}>
          <Table
            rows={paged.rows}
            compact
            pageSize={PAGE}
            columns={[
              {
                label: 'Item / lot',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.item)}</strong>
                    <span>
                      {text(r.lot)}
                      {r.source_number ? ` · ${text(r.source_number)}` : ''}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Inspection',
                render: (r) => <span className="capitalize">{labelState(r.type)}</span>,
              },
              { label: 'Quantity in stock', render: (r) => `${fmt(r.quantity)} ${text(r.uom)}` },
              { label: 'Status', render: (r) => <Badge value={r.status} /> },
              {
                label: 'Decision / notes',
                render: (r) =>
                  r.status === 'PENDING' && can('QUALITY') ? (
                    <button className="button small secondary" onClick={() => inspect(r)}>
                      Inspect lot
                      <ArrowRight size={13} />
                    </button>
                  ) : (
                    <span className="notes-cell" title={text(r.notes)}>
                      {text(r.notes) ||
                        (r.status === 'PENDING'
                          ? 'Awaiting authorized inspector'
                          : `By ${text(r.inspected_by)}`)}
                    </span>
                  ),
              },
            ]}
          />
        </PageState>
        <PageFooter paged={paged} label="inspections" />
      </Panel>
    </>
  );
}
