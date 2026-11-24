import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Layers, LoaderCircle } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { Empty, Overlay } from '../../components';
import { request } from '../../lib/api';
import { fmt, money, num, text } from '../../lib/format';
import { invalidatePurchasing, usePurchaseSources } from './api';
import '../sales/sales.css';
import './purchasing.css';

type Entry = { include: boolean; quantity: string; supplierId: string };

/**
 * Draft purchase orders from several material-planning shortages at once (`POST /api/purchases/recommendations`):
 * one draft per supplier. Each material defaults to the supplier it was last bought from; materials without one go to
 * a single draft without a supplier that the planner completes.
 */
export function ReplenishDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const data = useStateData();
  const qc = useQueryClient();
  const { refresh, notify } = useWorkspace();
  const shortages = data.mrp.filter(
    (m) => num(m.shortage) > 0 && m.type !== 'FINISHED_GOOD' && m.active !== false,
  );
  const sources = usePurchaseSources(shortages.map((m) => text(m.id)));
  const byItem = new Map((sources.data?.items || []).map((s) => [text(s.item_id), s]));
  const suppliers = data.partners.filter((p) => p.type === 'SUPPLIER' && p.active !== false);
  const [entries, setEntries] = useState<Record<string, Entry>>(() =>
    Object.fromEntries(
      shortages.map((m) => [
        text(m.id),
        {
          include: true,
          quantity: String(
            m.uom === 'pcs' ? Math.ceil(num(m.shortage)) : Math.ceil(num(m.shortage) * 1000) / 1000,
          ),
          supplierId: '',
        },
      ]),
    ),
  );
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  const set = (id: string, patch: Partial<Entry>) => {
    setEntries({ ...entries, [id]: { ...entries[id], ...patch } });
    key.current = crypto.randomUUID();
  };
  /** The chosen supplier, or the preferred one while the planner has not picked any. */
  const supplierOf = (id: string) => entries[id].supplierId || text(byItem.get(id)?.supplier_id);
  const chosen = shortages.filter(
    (m) => entries[text(m.id)].include && num(entries[text(m.id)].quantity) > 0,
  );
  const drafts = new Set(chosen.map((m) => supplierOf(text(m.id)) || '—')).size;

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!chosen.length) return setError('Choose at least one material.');
    setPending(true);
    setError('');
    try {
      const result = await request<{ count: number }>(
        '/purchases/recommendations',
        {
          lines: chosen.map((m) => ({
            itemId: m.id,
            quantity: entries[text(m.id)].quantity,
            supplierId: entries[text(m.id)].supplierId || null,
          })),
          dueDate: null,
        },
        key.current,
      );
      await Promise.all([invalidatePurchasing(qc), refresh()]);
      notify(
        `Created ${result.count} draft purchase ${result.count === 1 ? 'order' : 'orders'} successfully. Review and submit them for approval.`,
      );
      onCreated();
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }
  return (
    <Overlay
      title="Draft orders from material shortages"
      subtitle="One draft per supplier, priced at the last purchase price and due after each material's lead time."
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form onSubmit={submit}>
        <div className="dispatch-lots purchase-replenish">
          <div className="dispatch-lots-head">
            <h3>
              <Layers size={15} />
              Net shortages from material planning
            </h3>
            {sources.isFetching && <LoaderCircle size={15} className="spin muted" />}
          </div>
          {shortages.length ? (
            <div className="purchase-match-list">
              {shortages.map((m) => {
                const id = text(m.id);
                const e = entries[id];
                const source = byItem.get(id);
                return (
                  <div key={id} className={`purchase-match ${e.include ? '' : 'excluded'}`}>
                    <label className="purchase-match-pick">
                      <input
                        type="checkbox"
                        checked={e.include}
                        disabled={pending}
                        onChange={(ev) => set(id, { include: ev.target.checked })}
                      />
                      <span className="cell-stack">
                        <strong>{text(m.name)}</strong>
                        <span>
                          {text(m.sku)} · short {fmt(m.shortage, 3)} {text(m.uom)}
                          {num(m.lead_time_days) > 0 ? ` · ${text(m.lead_time_days)} day lead time` : ''}
                        </span>
                      </span>
                    </label>
                    <label className="field">
                      <span>Buy ({text(m.uom)})</span>
                      <input
                        type="number"
                        inputMode="decimal"
                        min="0"
                        step={m.uom === 'pcs' ? '1' : '0.000001'}
                        value={e.quantity}
                        disabled={pending || !e.include}
                        onChange={(ev) => set(id, { quantity: ev.target.value })}
                      />
                    </label>
                    <label className="field purchase-supplier-field">
                      <span>Supplier</span>
                      <select
                        value={e.supplierId}
                        disabled={pending || !e.include}
                        onChange={(ev) => set(id, { supplierId: ev.target.value })}
                      >
                        <option value="">
                          {source?.supplier ? `Last supplier: ${text(source.supplier)}` : 'Choose later'}
                        </option>
                        {suppliers.map((p) => (
                          <option key={p.id} value={p.id}>
                            {text(p.name)}
                          </option>
                        ))}
                      </select>
                    </label>
                    <div className="purchase-match-result">
                      <strong>
                        {source?.base_unit_cost && !e.supplierId
                          ? money(source.base_unit_cost)
                          : money(source?.standard_cost)}
                      </strong>
                      <span className="muted">
                        per {text(m.uom)} ·{' '}
                        {source?.base_unit_cost && !e.supplierId
                          ? 'last price'
                          : e.supplierId
                            ? 'estimate; that supplier’s last price applies'
                            : 'standard cost'}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <Empty
              title="No shortages"
              body="Material planning has no net shortage of purchasable materials."
            />
          )}
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <span className="muted sales-editor-total">
            {chosen.length} {chosen.length === 1 ? 'material' : 'materials'} · {drafts}{' '}
            {drafts === 1 ? 'draft' : 'drafts'}
          </span>
          <span className="grow" />
          <button type="button" className="button secondary" onClick={onClose} disabled={pending}>
            Cancel
          </button>
          <button className="button primary" disabled={pending || !chosen.length}>
            {pending ? <LoaderCircle className="spin" size={15} /> : <Layers size={15} />}
            Create draft orders
          </button>
        </div>
      </form>
    </Overlay>
  );
}
