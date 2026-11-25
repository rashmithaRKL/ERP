import { useState } from 'react';
import { Empty, Table } from '../../components';
import { date, fmt, labelState, money, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { QualityBadge, QualitySearch, useDebounced } from './shared';

const PAGE = 25;

/** Rework orders (`GET /api/quality/rework`): open and completed, with input/output lots and costs. */
export function ReworkTab({ onOpen }: { onOpen: (id: string) => void }) {
  const [status, setStatus] = useState('all');
  const [input, setInput] = useState('');
  const q = useDebounced(input);
  const paged = useCursorPage(['quality', 'rework'], '/quality/rework', { status, q }, PAGE);
  const counts = paged.query.data?.counts || {};
  const loaded = !!paged.query.data;
  return (
    <>
      <Tabs
        compact
        label="Rework order status"
        items={['all', 'OPEN', 'COMPLETED'].map((t) => ({
          value: t,
          label: t === 'all' ? 'All rework' : labelState(t),
          count: !loaded
            ? undefined
            : t === 'all'
              ? num(counts.OPEN) + num(counts.COMPLETED)
              : num(counts[t]),
        }))}
        value={status}
        onChange={setStatus}
      />
      <div className="server-toolbar">
        <QualitySearch
          value={input}
          onChange={setInput}
          placeholder="Search rework order, NCR, lot or item…"
        />
      </div>
      <PageState paged={paged}>
        {paged.rows.length ? (
          <Table
            rows={paged.rows}
            compact
            pageSize={PAGE}
            label="Rework orders"
            onRow={(r) => onOpen(r.id)}
            columns={[
              {
                label: 'Rework order',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.number)}</strong>
                    <span>{text(r.ncr)}</span>
                  </div>
                ),
              },
              {
                label: 'Item',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.item)}</strong>
                    <span>{text(r.sku)}</span>
                  </div>
                ),
              },
              {
                label: 'Input → output',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>
                      {fmt(r.input_quantity, 3)} →{' '}
                      {r.output_quantity === null ? '…' : fmt(r.output_quantity, 3)} {text(r.uom)}
                    </strong>
                    <span>
                      {text(r.input_lot)}
                      {r.output_lot ? ` → ${text(r.output_lot)}` : ''}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Rework cost',
                render: (r) =>
                  r.status === 'COMPLETED' ? money(num(r.labour_cost) + num(r.material_cost)) : '—',
              },
              {
                label: 'Status',
                render: (r) => (
                  <div className="cell-stack">
                    <QualityBadge value={r.status} />
                    {r.inspection_status && <span>Output {labelState(r.inspection_status)}</span>}
                  </div>
                ),
              },
              { label: 'Opened', render: (r) => date(r.created_at) },
            ]}
          />
        ) : (
          <Empty
            title="No rework orders"
            body="A REWORK disposition on a nonconformance opens a rework order."
          />
        )}
      </PageState>
      <PageFooter paged={paged} label="rework orders" />
    </>
  );
}
