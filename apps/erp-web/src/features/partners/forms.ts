import type { Workspace } from '../../app/workspace';
import type { Field } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { num, text } from '../../lib/format';

type Kind = 'CUSTOMER' | 'SUPPLIER';

const value = (x: unknown) => (x === null || x === undefined ? undefined : text(x));
const orNull = (x: string | undefined) => (x && x.trim() ? x.trim() : null);

function fields(kind: Kind, partner?: Row): Field[] {
  const list: Field[] = [
    { name: 'name', label: 'Company name', defaultValue: value(partner?.name) },
    {
      name: 'email',
      label: 'Contact email',
      type: 'email',
      optional: true,
      defaultValue: value(partner?.email),
    },
    { name: 'phone', label: 'Phone', optional: true, defaultValue: value(partner?.phone) },
    { name: 'taxId', label: 'Tax ID / VAT number', optional: true, defaultValue: value(partner?.tax_id) },
    {
      name: 'paymentDays',
      label: 'Payment terms (days)',
      type: 'number',
      min: '0',
      step: '1',
      defaultValue: value(partner?.payment_days) ?? '30',
      hint: 'Net days from invoice, 0–365.',
    },
  ];
  if (kind === 'CUSTOMER')
    list.push({
      name: 'creditLimit',
      label: 'Credit limit (LKR)',
      type: 'number',
      min: '0',
      step: '0.01',
      optional: true,
      defaultValue: partner?.credit_limit == null ? undefined : String(num(partner.credit_limit)),
      hint: 'Confirming an order above the limit records a warning; it is not blocked.',
    });
  if (partner)
    list.push({
      name: 'active',
      label: 'Status',
      options: [
        { value: 'true', label: 'Active — available on new documents' },
        { value: 'false', label: 'Inactive — existing documents only' },
      ],
      defaultValue: partner.active === false ? 'false' : 'true',
    });
  list.push(
    {
      name: 'billingAddress',
      label: 'Billing address',
      type: 'textarea',
      optional: true,
      defaultValue: value(partner?.billing_address),
    },
    {
      name: 'shippingAddress',
      label: kind === 'CUSTOMER' ? 'Shipping address' : 'Pickup / dispatch address',
      type: 'textarea',
      optional: true,
      defaultValue: value(partner?.shipping_address),
    },
  );
  return list;
}

function body(v: Record<string, string>) {
  return {
    name: v.name.trim(),
    email: orNull(v.email),
    phone: orNull(v.phone),
    billingAddress: orNull(v.billingAddress),
    shippingAddress: orNull(v.shippingAddress),
    taxId: orNull(v.taxId),
    paymentDays: Number(v.paymentDays || 0),
    creditLimit: orNull(v.creditLimit),
  };
}

/** "Add customer" / "Add supplier" (Sales, Purchasing). `onDone` refreshes page-owned queries. */
export function createPartner({ openForm }: Workspace, kind: Kind, onDone?: () => unknown) {
  openForm({
    title: kind === 'CUSTOMER' ? 'Add customer' : 'Add supplier',
    description: 'Create a business partner in your organization.',
    fields: fields(kind),
    submit: kind === 'CUSTOMER' ? 'Add customer' : 'Add supplier',
    run: async (values, key) => {
      const b = body(values);
      const result = await request(
        '/partners',
        {
          name: b.name,
          type: kind,
          email: b.email,
          paymentDays: b.paymentDays,
          phone: b.phone,
          billingAddress: b.billingAddress,
          shippingAddress: b.shippingAddress,
          taxId: b.taxId,
          creditLimit: b.creditLimit,
        },
        key,
      );
      await onDone?.();
      return result;
    },
  });
}

/** Edit a customer or supplier, including deactivation (inactive partners cannot be used on new documents). */
export function editPartner({ openForm }: Workspace, partner: Row, onDone?: () => unknown) {
  const kind = partner.type === 'SUPPLIER' ? 'SUPPLIER' : 'CUSTOMER';
  openForm({
    title: `Edit ${kind === 'CUSTOMER' ? 'customer' : 'supplier'}`,
    description: `${text(partner.name)} · changes are audited.`,
    fields: fields(kind, partner),
    submit: 'Save changes',
    run: async (values, key) => {
      const result = await request(
        `/partners/${partner.id}`,
        { ...body(values), active: values.active !== 'false' },
        key,
      );
      await onDone?.();
      return result;
    },
  });
}
