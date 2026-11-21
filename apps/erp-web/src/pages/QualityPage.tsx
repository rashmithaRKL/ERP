import { useState } from 'react';
import { ArrowRight, ShieldCheck } from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Badge, Panel, selectField, Table } from '../components';
import type { Row } from '../lib/api';
import { fmt, labelState, text } from '../lib/format';
import { filterRows } from '../lib/rows';
import { Tabs } from '../motion/motion';

/** Route `/quality`: inspection queue and decisions. */
export default function QualityPage() {
  const { can, postForm, search, summary } = useWorkspace();
  const { pendingQc } = summary;
  const data = useStateData();
  const [tab, setTab] = useState('all');
  function inspect(r: Row) {
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
      `${r.item} · Lot ${r.lot}. Finalized inspection decisions are auditable.`,
      'Finalize inspection',
    );
  }
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Awaiting inspection</span>
          <strong>{pendingQc.length}</strong>
        </div>
        <div>
          <span>Passed inspections</span>
          <strong>{data.inspections.filter((i) => i.status === 'PASSED').length}</strong>
        </div>
        <div>
          <span>Failed inspections</span>
          <strong>{data.inspections.filter((i) => i.status === 'FAILED').length}</strong>
        </div>
        <div>
          <span>Quality gate</span>
          <strong className="positive">
            Enforced
            <ShieldCheck size={19} />
          </strong>
        </div>
      </div>
      <Panel>
        <Tabs
          label="Quality inspection filters"
          items={['all', 'PENDING', 'PASSED', 'FAILED'].map((t) => ({
            value: t,
            label: t === 'all' ? 'All inspections' : labelState(t),
          }))}
          value={tab}
          onChange={setTab}
        />
        <Table
          rows={filterRows(data.inspections, search, tab)}
          search
          columns={[
            {
              label: 'Item / lot',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.item)}</strong>
                  <span>{text(r.lot)}</span>
                </div>
              ),
            },
            {
              label: 'Inspection',
              render: (r) => <span className="capitalize">{labelState(r.type)}</span>,
            },
            { label: 'Quantity in stock', render: (r) => fmt(r.quantity) },
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
                    {text(r.notes) || 'Awaiting authorized inspector'}
                  </span>
                ),
            },
          ]}
        />
      </Panel>
    </>
  );
}
