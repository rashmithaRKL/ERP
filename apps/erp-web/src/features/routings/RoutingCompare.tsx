import { useState } from 'react';
import { LoaderCircle } from 'lucide-react';
import { Overlay, Table } from '../../components';
import type { Row } from '../../lib/api';
import { codeLabel, fmt, text } from '../../lib/format';
import { listOf, minutesText, useRoutingComparison, useRoutingDetail } from './api';
import type { MachineRef } from './api';
import { StatusChip } from './shared';

const CHANGE_TONE: Record<string, string> = {
  ADDED: 'green',
  REMOVED: 'red',
  CHANGED: 'amber',
  SAME: 'gray',
};

function summary(o: Row | null) {
  if (!o) return '—';
  const alternates = listOf<MachineRef>(o, 'alternates').map((m) => m.code);
  return `${text(o.name)} · ${text(o.machine_code)}${alternates.length ? ` (or ${alternates.join(', ')})` : ''} · setup ${minutesText(o.setup_minutes)} · run ${fmt(o.run_minutes_per_unit, 6)} min · crew ${fmt(o.crew_size, 2)}${o.skill_code ? ` · ${text(o.skill_code)} L${text(o.skill_level)}` : ''}`;
}

/** Operation-by-operation comparison of two revisions of one product (`GET /api/routings/compare`). */
export function RoutingCompare({ from, to, onClose }: { from: string; to: string; onClose: () => void }) {
  const [base, setBase] = useState(from);
  const target = useRoutingDetail(to);
  const comparison = useRoutingComparison(base, to);
  const c = comparison.data;
  const revisions = (target.data?.revisions || []).filter((r) => r.id !== to);
  return (
    <Overlay
      title={`Compare routing ${text(target.data?.routing.revision)}`}
      subtitle="Added, removed and changed operations, matched by sequence number"
      onClose={onClose}
      wide
    >
      <div className="drawer-body">
        <label className="field compare-select">
          <span>Compare with</span>
          <select value={base} onChange={(e) => setBase(e.target.value)}>
            {revisions.map((r) => (
              <option key={r.id} value={r.id}>
                {text(r.revision)} · {codeLabel(r.status)}
              </option>
            ))}
          </select>
        </label>
        {comparison.isPending ? (
          <LoaderCircle className="spin" />
        ) : comparison.isError || !c ? (
          <div className="form-error" role="alert">
            {(comparison.error as Error)?.message || 'Choose another revision to compare.'}
          </div>
        ) : (
          <>
            <div className="routing-facts">
              <div>
                <span>Added</span>
                <strong>{c.summary.added}</strong>
              </div>
              <div>
                <span>Removed</span>
                <strong>{c.summary.removed}</strong>
              </div>
              <div>
                <span>Changed</span>
                <strong>{c.summary.changed}</strong>
              </div>
              <div>
                <span>
                  {text(c.from.revision)} → {text(c.to.revision)}
                </span>
                <strong>{text(c.to.sku)}</strong>
              </div>
            </div>
            <Table
              label="Operation differences"
              compact
              rows={c.operations.map((o) => ({
                id: String(o.seq),
                seq: o.seq,
                status: o.status,
                before: summary(o.from),
                after: summary(o.to),
                changes: o.changes.map((x) => codeLabel(x, false)).join(', '),
              }))}
              columns={[
                { label: 'Seq', key: 'seq' },
                {
                  label: 'Change',
                  render: (r) => (
                    <span className={`badge ${CHANGE_TONE[text(r.status)]}`}>
                      <i />
                      {codeLabel(r.status)}
                    </span>
                  ),
                },
                {
                  label: `${text(c.from.revision)}`,
                  render: (r) => <span className="notes-cell">{text(r.before)}</span>,
                },
                {
                  label: `${text(c.to.revision)}`,
                  render: (r) => <span className="notes-cell">{text(r.after)}</span>,
                },
                { label: 'Changed fields', render: (r) => text(r.changes) || '—' },
              ]}
            />
            <p className="settings-note">
              <StatusChip value={c.from.status} /> {text(c.from.revision)} compared with{' '}
              <StatusChip value={c.to.status} /> {text(c.to.revision)}.
            </p>
          </>
        )}
      </div>
    </Overlay>
  );
}
