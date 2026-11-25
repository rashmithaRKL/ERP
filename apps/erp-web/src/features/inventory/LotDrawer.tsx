import { Overlay, Table } from '../../components';
import { labelState, money, num, text } from '../../lib/format';
import { ProductMark } from '../products/ProductMark';
import { LotQualitySection } from '../quality/LotQualitySection';
import { useDetail } from './api';
import type { LotDetail } from './api';
import { dateTime, Delta, QueryState, StatusBadge, qty } from './shared';

/** One lot: balance, quality, traceability (source / transferred-to lots) and its stock movements. */
export function LotDrawer({
  id,
  onClose,
  onOpen,
}: {
  id: string;
  onClose: () => void;
  onOpen: (id: string) => void;
}) {
  const detail = useDetail<LotDetail>('lot', `/inventory/lots/${id}`);
  const lot = detail.data?.lot;
  return (
    <Overlay
      title={text(lot?.number) || 'Lot'}
      subtitle="Balance, quality status, traceability and stock movements"
      onClose={onClose}
      drawer
      wide
    >
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {detail.data && lot && (
          <div className="drawer-body">
            <div className="detail-product">
              <ProductMark kind={lot.item} />
              <div>
                <h2>{text(lot.item)}</h2>
                <p>
                  {text(lot.sku)} · {text(lot.warehouse)} ({text(lot.warehouse_code)}) · received{' '}
                  {dateTime(lot.created_at)}
                </p>
              </div>
              <StatusBadge value={lot.status} />
            </div>
            <div className="detail-stats">
              <div>
                <span>On hand</span>
                <strong>
                  {num(lot.quantity).toLocaleString('en-LK', { maximumFractionDigits: 3 })}{' '}
                  <small>{text(lot.uom)}</small>
                </strong>
              </div>
              <div>
                <span>Reserved</span>
                <strong>{num(lot.reserved).toLocaleString('en-LK', { maximumFractionDigits: 3 })}</strong>
              </div>
              <div>
                <span>Value</span>
                <strong>{money(lot.value_amount)}</strong>
              </div>
              <div>
                <span>Unit cost</span>
                <strong>{money(lot.unit_cost)}</strong>
              </div>
            </div>
            {lot.awaiting_inspection === true && (
              <div className="inv-warning">
                Awaiting its incoming inspection: it cannot be adjusted, transferred or counted until Quality
                control records a decision.
              </div>
            )}
            {lot.source_type === 'TRANSFER' && (
              <p className="inv-small">
                Transferred from{' '}
                <button className="text-button" onClick={() => onOpen(text(lot.source_id))}>
                  {text(lot.source_lot)}
                </button>
                {lot.status === 'QUARANTINE' &&
                  ' · its quality decision follows that lot’s pending inspection'}
              </p>
            )}
            {lot.source_type !== 'TRANSFER' && (
              <p className="inv-small muted">Source: {labelState(lot.source_type)}</p>
            )}
            {lot.open_count && (
              <p className="inv-small muted">Included in open cycle count {text(lot.open_count)}.</p>
            )}
            {detail.data.transferredTo.length > 0 && (
              <>
                <h3>Transferred to</h3>
                <Table
                  compact
                  pageSize={detail.data.transferredTo.length}
                  rows={detail.data.transferredTo}
                  onRow={(r) => onOpen(r.id)}
                  columns={[
                    { label: 'Lot', key: 'number' },
                    { label: 'Warehouse', key: 'warehouse' },
                    { label: 'On hand', render: (r) => qty(r.quantity, lot.uom) },
                    { label: 'Status', render: (r) => <StatusBadge value={r.status} /> },
                  ]}
                />
              </>
            )}
            {detail.data.inspections.length > 0 && (
              <>
                <h3>Inspections</h3>
                <Table
                  compact
                  pageSize={detail.data.inspections.length}
                  rows={detail.data.inspections}
                  columns={[
                    { label: 'Type', render: (r) => labelState(r.type) },
                    { label: 'Status', render: (r) => <StatusBadge value={r.status} /> },
                    { label: 'Inspector', render: (r) => text(r.inspected_by) || '—' },
                    {
                      label: 'Notes',
                      render: (r) => <span className="notes-cell">{text(r.notes) || '—'}</span>,
                    },
                  ]}
                />
              </>
            )}
            <LotQualitySection lotId={id} onOpenLot={onOpen} />
            <h3>Movements</h3>
            <Table
              compact
              pageSize={Math.max(detail.data.movements.length, 1)}
              rows={detail.data.movements}
              columns={[
                { label: 'When', render: (r) => dateTime(r.created_at) },
                { label: 'Movement', render: (r) => <Delta value={r.delta} uom={r.uom} /> },
                { label: 'Value', render: (r) => money(r.value_delta) },
                { label: 'Reference', key: 'reference' },
                {
                  label: 'Reason',
                  render: (r) => <span className="capitalize">{labelState(r.reason)}</span>,
                },
                { label: 'By', key: 'actor' },
              ]}
            />
          </div>
        )}
      </QueryState>
    </Overlay>
  );
}
