import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { ArrowRight, ClipboardList } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Badge, Table } from '../../components';
import type { Row } from '../../lib/api';
import { fmt, labelState, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { PageFooter, PageState } from '../lists/cursorPage';
import type { useCursorPage } from '../lists/cursorPage';
import { InspectDialog } from './InspectDialog';
import { MeasurementsDialog } from './MeasurementsDialog';
import { QualitySearch } from './shared';

const PAGE = 25;
export const INSPECTION_TYPES = ['INCOMING', 'FINAL', 'RETURN', 'REWORK', 'OPENING'];

/** Server-paged inspection queue (`GET /api/quality/inspections`) with decisions and recorded measurements. */
export function InspectionsTab({
  paged,
  tab,
  setTab,
  type,
  setType,
  input,
  setInput,
  onOpenNcr,
}: {
  paged: ReturnType<typeof useCursorPage>;
  tab: string;
  setTab: (value: string) => void;
  type: string;
  setType: (value: string) => void;
  input: string;
  setInput: (value: string) => void;
  onOpenNcr: (id: string) => void;
}) {
  const { can } = useWorkspace();
  const [inspecting, setInspecting] = useState<Row>();
  const [measured, setMeasured] = useState<Row>();
  const counts = paged.query.data?.counts || {};
  const loaded = !!paged.query.data;
  return (
    <>
      <Tabs
        compact
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
        <QualitySearch value={input} onChange={setInput} placeholder="Search lot, item, SKU or notes…" />
        <select aria-label="Inspection type" value={type} onChange={(e) => setType(e.target.value)}>
          <option value="">All inspection types</option>
          {INSPECTION_TYPES.map((t) => (
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
          label="Quality inspections"
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
              render: (r) => (
                <div className="cell-stack">
                  <span className="capitalize">{labelState(r.type)}</span>
                  {r.plan_id && (
                    <span className="qa-plan-chip">
                      <ClipboardList size={12} /> Plan applies
                    </span>
                  )}
                </div>
              ),
            },
            { label: 'Quantity in stock', render: (r) => `${fmt(r.quantity)} ${text(r.uom)}` },
            { label: 'Status', render: (r) => <Badge value={r.status} /> },
            {
              label: 'Decision / notes',
              render: (r) =>
                r.status === 'PENDING' && can('QUALITY') ? (
                  <button className="button small secondary" onClick={() => setInspecting(r)}>
                    Inspect lot
                    <ArrowRight size={13} />
                  </button>
                ) : (
                  <div className="cell-stack">
                    <span className="notes-cell" title={text(r.notes)}>
                      {text(r.notes) ||
                        (r.status === 'PENDING'
                          ? 'Awaiting authorized inspector'
                          : `By ${text(r.inspected_by)}`)}
                    </span>
                    {(r.ncr_number || r.status !== 'PENDING') && (
                      <span className="qa-row-links">
                        {r.ncr_number && (
                          <button className="text-button" onClick={() => onOpenNcr(text(r.ncr_id))}>
                            {text(r.ncr_number)}
                          </button>
                        )}
                        {r.status !== 'PENDING' && (
                          <button className="text-button" onClick={() => setMeasured(r)}>
                            Measurements
                          </button>
                        )}
                      </span>
                    )}
                  </div>
                ),
            },
          ]}
        />
      </PageState>
      <PageFooter paged={paged} label="inspections" />
      <AnimatePresence>
        {inspecting && (
          <InspectDialog key="inspect" inspection={inspecting} onClose={() => setInspecting(undefined)} />
        )}
        {measured && (
          <MeasurementsDialog key="measured" inspection={measured} onClose={() => setMeasured(undefined)} />
        )}
      </AnimatePresence>
    </>
  );
}
