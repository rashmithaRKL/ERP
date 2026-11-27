import { useQuery } from '@tanstack/react-query';
import { Info, LoaderCircle, ReceiptText, TriangleAlert } from 'lucide-react';
import { request } from '../../lib/api';
import { money, text } from '../../lib/format';
import { longDate } from '../admin/api';
import { KIND_LABELS, pct, SOURCE_LABELS, TAX } from './api';
import type { TaxEstimateResponse } from './api';
import './tax.css';

/**
 * Read-only "Estimated tax" block of the sales and purchase order drawers (V25). The server calculates it from the
 * order lines with today's rates and the tenant's settings; nothing is posted and the order total is unchanged.
 * `version` (the order's `updated_at`) refetches the estimate after the draft is edited.
 */
export function TaxEstimate({
  kind,
  orderId,
  version,
}: {
  kind: 'sales' | 'purchase';
  orderId: string;
  version: unknown;
}) {
  const path = kind === 'sales' ? 'sales-orders' : 'purchase-orders';
  const query = useQuery({
    queryKey: [TAX, 'estimate', kind, orderId, text(version)],
    queryFn: () => request<TaxEstimateResponse>(`/tax/estimates/${path}/${orderId}`),
  });
  return (
    <section className="tax-estimate" aria-labelledby={`tax-estimate-${orderId}`}>
      <h3 id={`tax-estimate-${orderId}`}>
        <span>Estimated tax</span>
        <small>Not posted · for information</small>
      </h3>
      {query.isPending ? (
        <div className="tax-estimate-state" role="status">
          <LoaderCircle className="spin" size={16} />
          <span>Calculating…</span>
        </div>
      ) : query.isError ? (
        <div className="tax-estimate-state">
          <span className="form-error" role="alert">
            {(query.error as Error).message}
          </span>
          <button className="button secondary small" onClick={() => query.refetch()}>
            Retry
          </button>
        </div>
      ) : !query.data.configured ? (
        <p className="tax-estimate-note">
          <Info size={14} />
          No tax codes are set up yet. An administrator can apply a country template under Administration →
          Taxes.
        </p>
      ) : !query.data.available || !query.data.estimate ? (
        <p className="tax-estimate-note warn">
          <TriangleAlert size={14} />
          {text(query.data.message) || 'The tax could not be estimated.'}
        </p>
      ) : (
        <Estimate data={query.data} />
      )}
    </section>
  );
}

function Estimate({ data }: { data: TaxEstimateResponse }) {
  const e = data.estimate!;
  const sources = [...new Set(e.lines.map((l) => SOURCE_LABELS[l.source] || l.source))];
  return (
    <>
      <div className="tax-estimate-grid">
        <table className="tax-estimate-table">
          <caption className="sr-only">Estimated tax per code</caption>
          <thead>
            <tr>
              <th scope="col">Tax code</th>
              <th scope="col">Taxable base</th>
              <th scope="col">Tax</th>
            </tr>
          </thead>
          <tbody>
            {e.codes.map((c) => (
              <tr key={c.taxCodeId}>
                <th scope="row">
                  <strong>{c.code}</strong>
                  <span>
                    {c.kind === 'STANDARD'
                      ? c.components
                          .map((k) => `${k.name} ${pct(k.rate)}${k.compound ? ' compound' : ''}`)
                          .join(' + ')
                      : KIND_LABELS[c.kind]}
                  </span>
                </th>
                <td>{money(c.base)}</td>
                <td>{money(c.tax)}</td>
              </tr>
            ))}
            {e.lines.some((l) => !l.taxCodeId) && (
              <tr>
                <th scope="row">
                  <strong>Untaxed</strong>
                  <span>Lines without a tax code</span>
                </th>
                <td>{money(e.lines.filter((l) => !l.taxCodeId).reduce((s, l) => s + Number(l.base), 0))}</td>
                <td>{money(0)}</td>
              </tr>
            )}
          </tbody>
        </table>
        <dl className="tax-estimate-totals">
          <div>
            <dt>Total excluding tax</dt>
            <dd>{money(e.net)}</dd>
          </div>
          <div>
            <dt>{e.direction === 'OUTPUT' ? 'Output tax' : 'Input tax'}</dt>
            <dd>{money(e.tax)}</dd>
          </div>
          <div className="strong">
            <dt>Total including tax</dt>
            <dd>{money(e.gross)}</dd>
          </div>
        </dl>
      </div>
      <p className="tax-estimate-note">
        <ReceiptText size={14} />
        Rates effective {longDate(e.date)} · prices {e.pricesIncludeTax ? 'include' : 'exclude'} tax · rounded{' '}
        {e.rounding === 'PER_DOCUMENT' ? 'per document' : 'per line'} · codes from {sources.join(', ')}. The
        order total stays {money(data.order_total)}.
      </p>
      {e.warnings.map((w) => (
        <p className="tax-estimate-note warn" key={w}>
          <TriangleAlert size={14} />
          {w}
        </p>
      ))}
    </>
  );
}
