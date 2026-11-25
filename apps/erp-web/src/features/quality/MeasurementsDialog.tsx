import { Overlay, Table } from '../../components';
import type { Row } from '../../lib/api';
import { labelState, text } from '../../lib/format';
import { dateTime, QueryState } from '../inventory/shared';
import { specText, trim, useQualityQuery } from './api';
import type { Measurement } from './api';
import { QualityBadge } from './shared';

/** Measurements recorded with a decided inspection (`GET /quality/inspections/{id}/measurements`). */
export function MeasurementsDialog({ inspection, onClose }: { inspection: Row; onClose: () => void }) {
  const query = useQualityQuery<{ inspection: Row; items: Measurement[] }>(
    'measurements',
    `/quality/inspections/${inspection.id}/measurements`,
  );
  const items = query.data?.items ?? [];
  return (
    <Overlay
      title="Inspection measurements"
      subtitle={`${text(inspection.item)} · Lot ${text(inspection.lot)} · ${labelState(inspection.type)} inspection`}
      onClose={onClose}
      wide
    >
      <div className="drawer-body">
        <QueryState pending={query.isPending} error={query.error} retry={() => query.refetch()}>
          {items.length ? (
            <>
              <p className="inv-small muted">
                Plan {text(items[0].plan_number)} (version {text(items[0].plan_version)}) · recorded by{' '}
                {text(inspection.inspected_by)} · {dateTime(inspection.inspected_at)}. Limits are shown as they were
                when measured.
              </p>
              <Table
                compact
                pageSize={items.length}
                rows={items}
                label="Measurements"
                columns={[
                  {
                    label: 'Characteristic',
                    render: (m) => (
                      <div className="cell-stack">
                        <strong>{text(m.name)}</strong>
                        <span>{specText(m)}</span>
                      </div>
                    ),
                  },
                  {
                    label: 'Result',
                    render: (m) =>
                      m.kind === 'ATTRIBUTE'
                        ? `${text(m.nonconforming)} of ${text(m.sample_size)} nonconforming`
                        : m.readings.map((v) => trim(v)).join(' · '),
                  },
                  {
                    label: 'Summary',
                    render: (m) =>
                      m.kind === 'ATTRIBUTE' ? '—' : `min ${trim(m.min_value)} · max ${trim(m.max_value)} · mean ${trim(m.mean_value)}`,
                  },
                  {
                    label: 'Status',
                    render: (m) => (
                      <div className="cell-stack">
                        <QualityBadge
                          value={m.conforming ? 'PASSED' : 'FAILED'}
                          label={m.conforming ? 'In spec' : 'Out of spec'}
                        />
                        {m.critical && <span>Critical</span>}
                      </div>
                    ),
                  },
                  { label: 'Note', render: (m) => <span className="notes-cell">{text(m.note) || '—'}</span> },
                ]}
              />
            </>
          ) : (
            <p className="inv-small muted">No measurements: no inspection plan governed this decision.</p>
          )}
        </QueryState>
      </div>
    </Overlay>
  );
}
