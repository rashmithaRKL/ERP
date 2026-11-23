import type { Workspace } from '../../app/workspace';
import { dateField, option, qtyField, selectField } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { fmt, labelState, num, text } from '../../lib/format';

/** Quantity of a sales order line not yet covered by (non-cancelled) manufacturing orders. */
const unplanned = (line: Row) => Math.max(0, num(line.quantity) - num(line.planned));

/**
 * "New manufacturing order" form (Overview, MOs, Shop floor, Sales). It can be planned for a customer order line:
 * pick the line and the product's latest approved BOM and the unplanned quantity are used unless chosen explicitly.
 * `line` pre-selects a sales order line (from the sales order drawer); `onDone` refreshes caller-owned queries.
 */
export function createOrder({ data, openForm }: Workspace, line?: Row, onDone?: () => unknown) {
  if (!data) return;
  const lines = [...(data.salesLines || [])];
  if (line && !lines.some((l) => l.id === line.id)) lines.unshift(line);
  const boms = data.boms.filter((b) => b.status === 'APPROVED');
  const bomFor = (product: unknown) => boms.find((b) => b.product_id === product);
  openForm({
    title: 'New manufacturing order',
    description: line
      ? `${text(line.number)} line ${text(line.line_no)} · ${text(line.product)} for ${text(line.customer)}. Material requirements are snapshotted from the BOM revision; an independent approval is required.`
      : 'Material requirements are snapshotted from this BOM revision. An independent approval is required.',
    fields: [
      {
        ...selectField(
          'salesOrderLineId',
          'Customer order line',
          lines.map((l) => ({
            value: l.id,
            label: `${text(l.number)} · L${text(l.line_no)} · ${text(l.product)} · ${text(l.customer)} · ${fmt(unplanned(l))} ${text(l.uom) || 'pcs'} unplanned`,
          })),
          line?.id,
        ),
        optional: true,
        hint: 'Make to order: the product and the unplanned quantity come from the line. Leave empty to make to stock.',
      },
      {
        ...selectField(
          'bomId',
          'Product & approved BOM',
          boms.map((b) => ({ value: b.id, label: `${b.product} · ${b.revision}` })),
          line ? bomFor(line.product_id)?.id : undefined,
        ),
        optional: true,
        hint: 'Optional with a customer order line: its product’s latest approved revision is used.',
      },
      {
        ...qtyField('quantity', 'Planned quantity (pieces)', line ? text(unplanned(line)) : undefined),
        optional: true,
        hint: 'Defaults to the line’s unplanned quantity.',
      },
      selectField(
        'machineId',
        'Work center',
        option(
          data.machines.filter((m) => m.status !== 'MAINTENANCE'),
          'name',
        ),
      ),
      selectField(
        'priority',
        'Priority',
        ['NORMAL', 'HIGH', 'URGENT'].map((x) => ({ value: x, label: labelState(x) })),
        'NORMAL',
      ),
      line?.due_date ? { ...dateField, defaultValue: text(line.due_date).substring(0, 10) } : dateField,
    ],
    submit: 'Create order',
    run: async (values, key) => {
      const picked = lines.find((l) => l.id === values.salesOrderLineId);
      let bomId = values.bomId;
      if (picked) {
        const chosen = boms.find((b) => b.id === bomId);
        if (!bomId) bomId = text(bomFor(picked.product_id)?.id);
        else if (chosen && chosen.product_id !== picked.product_id)
          throw new Error('The selected BOM is for a different product than the customer order line.');
        if (!bomId) throw new Error(`No approved BOM exists for ${text(picked.product)}.`);
      }
      if (!bomId) throw new Error('Choose a product & approved BOM, or a customer order line.');
      const quantity = values.quantity || (picked ? String(unplanned(picked)) : '');
      if (!(num(quantity) > 0)) throw new Error('Enter a planned quantity greater than zero.');
      const result = await request(
        '/manufacturing',
        {
          bomId,
          machineId: values.machineId,
          salesOrderId: null,
          quantity,
          priority: values.priority,
          dueDate: values.dueDate,
          salesOrderLineId: picked ? picked.id : null,
        },
        key,
      );
      await onDone?.();
      return result;
    },
  });
}

/** "Record production output" form (Shop floor, order drawer). */
export function recordOutput({ postForm }: Workspace, order: Row) {
  postForm(
    'Record production output',
    `/manufacturing/${order.id}/complete`,
    [
      { ...qtyField('goodQty', 'Good quantity (pieces)'), min: '0' },
      { ...qtyField('scrapQty', 'Scrap quantity (pieces)', '0'), min: '0' },
      { ...qtyField('labourCost', 'Additional labour cost (LKR)', '0'), min: '0' },
      { ...qtyField('machineCost', 'Additional machine cost (LKR)', '0'), min: '0' },
      {
        name: 'notes',
        label: 'Production notes / scrap reason',
        type: 'textarea',
        defaultValue: '',
        optional: true,
      },
    ],
    `${order.number} · ${fmt(num(order.quantity) - num(order.good_qty) - num(order.scrap_qty))} pieces remaining. Output is held for quality review.`,
    'Post output',
  );
}
