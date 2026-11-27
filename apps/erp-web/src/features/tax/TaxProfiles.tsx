import { useQuery, useQueryClient } from '@tanstack/react-query';
import { LoaderCircle, Pencil } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import type { Workspace } from '../../app/workspace';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, text, today } from '../../lib/format';
import { codeOptions, invalidateTax, TAX } from './api';
import type { TaxCodes } from './api';
import './tax.css';

/** Registration and exemption chips for a partner row (`GET /api/partners` rows carry the V25 columns). */
export function PartnerTaxChips({ partner }: { partner: Row }) {
  const exemptUntil = text(partner.tax_exemption_valid_until).substring(0, 10);
  const exempt = exemptUntil && exemptUntil >= today();
  return (
    <span className="tax-profile">
      {partner.tax_registered ? (
        <span className="badge green">
          <i />
          tax-registered
        </span>
      ) : (
        <span className="badge gray">
          <i />
          not registered
        </span>
      )}
      {exemptUntil && (
        <span className={`badge ${exempt ? 'blue' : 'amber'}`} title={text(partner.tax_exemption_reference)}>
          <i />
          {exempt ? `exempt to ${date(exemptUntil)}` : 'exemption expired'}
        </span>
      )}
    </span>
  );
}

/**
 * Administrator dialog for a partner's tax profile: registration, exemption certificate (reference + valid until)
 * and default sales/purchase tax codes. Loads the codes first; `onDone` refreshes the caller's lists.
 */
export async function editPartnerTax(workspace: Workspace, partner: Row, onDone?: () => unknown) {
  let codes: TaxCodes;
  try {
    codes = await request<TaxCodes>('/tax/codes');
  } catch (err) {
    workspace.notify((err as Error).message);
    return;
  }
  const customer = partner.type !== 'SUPPLIER';
  workspace.openForm({
    title: `Tax profile · ${text(partner.name)}`,
    description:
      'Used when tax is calculated on this partner’s documents: an exemption valid on the document date makes every line exempt unless the line names a code.',
    fields: [
      {
        name: 'taxRegistered',
        label: 'Tax registration',
        options: [
          { value: 'true', label: 'Registered for VAT / tax' },
          { value: 'false', label: 'Not registered' },
        ],
        defaultValue: partner.tax_registered ? 'true' : 'false',
        hint: text(partner.tax_id) ? `Tax ID ${text(partner.tax_id)} (edit it with the partner).` : undefined,
      },
      {
        name: 'exemptionReference',
        label: 'Exemption certificate reference',
        optional: true,
        defaultValue: text(partner.tax_exemption_reference) || undefined,
        hint: 'Leave both exemption fields empty when the partner has no exemption.',
      },
      {
        name: 'exemptionValidUntil',
        label: 'Exemption valid until',
        type: 'date',
        optional: true,
        defaultValue: text(partner.tax_exemption_valid_until).substring(0, 10) || undefined,
      },
      {
        name: customer ? 'salesTaxCodeId' : 'purchaseTaxCodeId',
        label: customer ? 'Default sales tax code' : 'Default purchase tax code',
        optional: true,
        options: codeOptions(
          codes.items,
          customer ? 'SALES' : 'PURCHASES',
          customer ? partner.sales_tax_code_id : partner.purchase_tax_code_id,
        ),
        defaultValue: text(customer ? partner.sales_tax_code_id : partner.purchase_tax_code_id) || undefined,
        hint: 'Used when neither the line nor the item names a code.',
      },
    ],
    submit: 'Save tax profile',
    run: async (v, key) => {
      const result = await request(
        `/tax/partners/${partner.id}`,
        {
          taxRegistered: v.taxRegistered === 'true',
          exemptionReference: v.exemptionReference?.trim() || null,
          exemptionValidUntil: v.exemptionValidUntil || null,
          salesTaxCodeId: customer ? v.salesTaxCodeId || null : text(partner.sales_tax_code_id) || null,
          purchaseTaxCodeId: customer
            ? text(partner.purchase_tax_code_id) || null
            : v.purchaseTaxCodeId || null,
        },
        key,
      );
      await onDone?.();
      return result;
    },
  });
}

/** "Tax" section of the item drawer's overview: default sales and purchase codes; administrators edit them. */
export function ItemTaxSection({ itemId }: { itemId: string }) {
  const workspace = useWorkspace();
  const qc = useQueryClient();
  const query = useQuery({
    queryKey: [TAX, 'item', itemId],
    queryFn: () => request<{ item: Row }>(`/tax/items/${itemId}`),
  });
  const item = query.data?.item;
  async function edit() {
    if (!item) return;
    let codes: TaxCodes;
    try {
      codes = await request<TaxCodes>('/tax/codes');
    } catch (err) {
      workspace.notify((err as Error).message);
      return;
    }
    workspace.openForm({
      title: `Tax defaults · ${text(item.name)}`,
      description: 'Used when a document line names no code and the partner has no valid exemption.',
      fields: [
        {
          name: 'salesTaxCodeId',
          label: 'Sales tax code',
          optional: true,
          options: codeOptions(codes.items, 'SALES', item.sales_tax_code_id),
          defaultValue: text(item.sales_tax_code_id) || undefined,
        },
        {
          name: 'purchaseTaxCodeId',
          label: 'Purchase tax code',
          optional: true,
          options: codeOptions(codes.items, 'PURCHASES', item.purchase_tax_code_id),
          defaultValue: text(item.purchase_tax_code_id) || undefined,
        },
      ],
      submit: 'Save tax defaults',
      run: async (v, key) => {
        const result = await request(
          `/tax/items/${itemId}`,
          { salesTaxCodeId: v.salesTaxCodeId || null, purchaseTaxCodeId: v.purchaseTaxCodeId || null },
          key,
        );
        await invalidateTax(qc);
        return result;
      },
    });
  }
  return (
    <div className="drawer-section">
      <h3>
        <span>Tax defaults</span>
        {workspace.can() && item && (
          <button className="button secondary small" onClick={() => void edit()}>
            <Pencil size={13} />
            Edit
          </button>
        )}
      </h3>
      {query.isPending ? (
        <div className="tax-estimate-state" role="status">
          <LoaderCircle className="spin" size={16} />
          <span>Loading…</span>
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
      ) : (
        <dl className="detail-list">
          <dt>Sales tax code</dt>
          <dd>
            {text(item?.sales_tax_code) || <span className="muted">None · workspace or partner default</span>}
          </dd>
          <dt>Purchase tax code</dt>
          <dd>
            {text(item?.purchase_tax_code) || (
              <span className="muted">None · workspace or partner default</span>
            )}
          </dd>
        </dl>
      )}
    </div>
  );
}
