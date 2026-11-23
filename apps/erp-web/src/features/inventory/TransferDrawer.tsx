import { useState } from 'react';
import { ArrowRight, LoaderCircle } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import { money, num, text } from '../../lib/format';
import { OPERATORS, useDetail, useInventoryCommand } from './api';
import type { TransferDetail } from './api';
import { ActivityFeed, dateTime, Delta, Lifecycle, QueryState, StatusBadge, qty } from './shared';

/** Warehouse transfer details: source and destination lots, the value-neutral ledger pair and audit history. */
export function TransferDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const { can } = useWorkspace();
  const detail = useDetail<TransferDetail>('transfer', `/inventory/transfers/${id}`);
  const { run, busy } = useInventoryCommand();
  const [error, setError] = useState('');
  const t = detail.data?.transfer;
  const status = text(t?.status);
  async function act(action: string, success: string) {
    setError('');
    try {
      await run(`/inventory/transfers/${id}/${action}`, {}, undefined, success);
    } catch (e) {
      setError((e as Error).message);
    }
  }
  return (
    <Overlay
      title={text(t?.number) || 'Warehouse transfer'}
      subtitle="Lots moved, the destination lots they created and audit history"
      onClose={onClose}
      drawer
      wide
    >
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {detail.data && t && (
          <>
            <div className="drawer-body">
              <div className="detail-product">
                <div>
                  <h2 className="inv-route">
                    {text(t.from_warehouse)} <ArrowRight size={16} /> {text(t.to_warehouse)}
                  </h2>
                  <p>
                    Created by {text(t.created_by_name)} · {dateTime(t.created_at)}
                  </p>
                </div>
                <StatusBadge value={t.status} />
              </div>
              {error && (
                <div className="form-error" role="alert">
                  {error}
                </div>
              )}
              {status !== 'CANCELLED' && (
                <Lifecycle steps={['Draft', 'Posted']} index={status === 'POSTED' ? 1 : 0} />
              )}
              <div className="detail-stats">
                <div>
                  <span>Lots</span>
                  <strong>{text(t.line_count)}</strong>
                </div>
                <div>
                  <span>Value moved</span>
                  <strong>{status === 'POSTED' ? money(t.total_value) : '—'}</strong>
                </div>
                <div>
                  <span>Journal</span>
                  <strong className="inv-stat-text">None needed</strong>
                </div>
                <div>
                  <span>Posted</span>
                  <strong className="inv-stat-text">
                    {status === 'POSTED'
                      ? `${text(t.posted_by_name)}`
                      : status === 'CANCELLED'
                        ? 'Cancelled'
                        : 'Not yet'}
                  </strong>
                </div>
              </div>
              <p className="inv-small muted">
                Inventory accounts follow the item type, not the warehouse, so a transfer changes location
                only. Quarantined stock stays quarantined and follows its original inspection.
              </p>
              {text(t.notes) && <p className="inv-notes">{text(t.notes)}</p>}
              <h3>Lines</h3>
              <Table
                compact
                pageSize={Math.max(detail.data.lines.length, 1)}
                rows={detail.data.lines}
                columns={[
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
                    label: 'From lot',
                    render: (r) => (
                      <div className="cell-stack">
                        <strong>{text(r.source_lot)}</strong>
                        <span>
                          {status === 'DRAFT'
                            ? `${qty(num(r.on_hand) - num(r.reserved), r.uom)} available now`
                            : text(r.source_status).toLowerCase()}
                        </span>
                      </div>
                    ),
                  },
                  { label: 'Quantity', render: (r) => qty(r.quantity, r.uom) },
                  {
                    label: 'To lot',
                    render: (r) =>
                      r.destination_lot ? (
                        <div className="cell-stack">
                          <strong>{text(r.destination_lot)}</strong>
                          <span>{text(r.destination_status).toLowerCase()}</span>
                        </div>
                      ) : (
                        <span className="muted">Created on posting</span>
                      ),
                  },
                  { label: 'Value', render: (r) => (r.value_amount === null ? '—' : money(r.value_amount)) },
                ]}
              />
              {detail.data.ledger.length > 0 && (
                <>
                  <h3>Stock ledger</h3>
                  <Table
                    compact
                    pageSize={detail.data.ledger.length}
                    rows={detail.data.ledger}
                    columns={[
                      { label: 'Lot', key: 'lot' },
                      { label: 'Warehouse', key: 'warehouse' },
                      { label: 'Movement', render: (r) => <Delta value={r.delta} uom={r.uom} /> },
                      {
                        label: 'Value',
                        render: (r) => (
                          <span className={num(r.value_delta) < 0 ? 'negative' : 'positive'}>
                            {money(r.value_delta)}
                          </span>
                        ),
                      },
                    ]}
                  />
                </>
              )}
              <h3>Document activity</h3>
              <ActivityFeed events={detail.data.events} />
            </div>
            {status === 'DRAFT' && can(...OPERATORS) && (
              <div className="dialog-footer detail-footer">
                <>
                  <button
                    className="button danger-ghost"
                    disabled={busy}
                    onClick={() => act('cancel', `${text(t.number)} cancelled successfully.`)}
                  >
                    Cancel transfer
                  </button>
                  <span className="grow" />
                  <button
                    className="button primary"
                    disabled={busy}
                    onClick={() => act('post', `${text(t.number)} posted successfully.`)}
                  >
                    {busy && <LoaderCircle size={15} className="spin" />}
                    Post transfer
                    <ArrowRight size={15} />
                  </button>
                </>
              </div>
            )}
          </>
        )}
      </QueryState>
    </Overlay>
  );
}
