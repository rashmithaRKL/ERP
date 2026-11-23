import type { Workspace } from '../../app/workspace';
import { qtyField, selectField } from '../../components';
import type { Field } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { num, text } from '../../lib/format';
import {
  compact,
  formatAttributes,
  itemTypeLabels,
  itemTypes,
  parseAttributes,
  unitKinds,
} from './itemMaster';
import type { FamilyDetail, ItemDetail } from './itemMaster';

type Lists = { units: Row[]; categories: Row[] };

async function lists(): Promise<Lists> {
  const [units, categories] = await Promise.all([
    request<{ items: Row[] }>('/units'),
    request<{ items: Row[] }>('/item-categories'),
  ]);
  return { units: units.items, categories: categories.items };
}

const unitOptions = (units: Row[], keep?: unknown) =>
  units
    .filter((u) => u.active || u.code === keep)
    .map((u) => ({ value: text(u.code), label: `${text(u.name)} (${text(u.code)})` }));

const categoryOptions = (categories: Row[], keep?: unknown) =>
  categories.filter((c) => c.active || c.id === keep).map((c) => ({ value: c.id, label: text(c.path) }));

const typeOptions = itemTypes.map((t) => ({ value: t, label: itemTypeLabels[t] }));

const description = (value?: unknown): Field => ({
  name: 'description',
  label: 'Description',
  type: 'textarea',
  optional: true,
  defaultValue: text(value),
});

/** "New item" (page heading action and Products page). */
export async function createItem(workspace: Workspace) {
  const { openForm, notify } = workspace;
  let data: Lists;
  try {
    data = await lists();
  } catch (e) {
    notify((e as Error).message);
    return;
  }
  openForm({
    title: 'Create product or material',
    description:
      'Stock is kept in the base unit. Purchase and sales units (bags, boxes, rolls) are added as conversions after creation.',
    submit: 'Create item',
    fields: [
      { name: 'sku', label: 'Unique SKU' },
      { name: 'name', label: 'Item name' },
      selectField('type', 'Item type', typeOptions),
      selectField('uom', 'Base unit', unitOptions(data.units), 'pcs'),
      { ...selectField('categoryId', 'Category', categoryOptions(data.categories)), optional: true },
      { ...qtyField('standardCost', 'Standard unit cost (LKR)'), min: '0' },
      { ...qtyField('reorderLevel', 'Safety stock', '0'), min: '0' },
      { ...qtyField('reorderQuantity', 'Reorder quantity', '0'), min: '0', optional: true },
      {
        name: 'leadTimeDays',
        label: 'Lead time (days)',
        type: 'number',
        min: '0',
        step: '1',
        defaultValue: '0',
        optional: true,
      },
      description(),
    ],
    run: (values, key) => request('/items', compact(values), key),
  });
}

/** Edit an item. Locked fields (SKU, base unit) are kept and explained instead of offered. */
export async function editItem(workspace: Workspace, detail: ItemDetail) {
  const { openForm, notify } = workspace;
  let data: Lists;
  try {
    data = await lists();
  } catch (e) {
    notify((e as Error).message);
    return;
  }
  const item = detail.item;
  const locked = detail.usage.skuLocked;
  const fields: Field[] = [
    ...(locked ? [] : [{ name: 'sku', label: 'SKU', defaultValue: text(item.sku) }]),
    { name: 'name', label: 'Item name', defaultValue: text(item.name) },
    ...(locked ? [] : [selectField('uom', 'Base unit', unitOptions(data.units, item.uom), text(item.uom))]),
    {
      ...selectField(
        'categoryId',
        'Category',
        categoryOptions(data.categories, item.category_id),
        text(item.category_id),
      ),
      optional: true,
    },
    { ...qtyField('standardCost', 'Standard unit cost (LKR)', text(num(item.standard_cost))), min: '0' },
    {
      ...qtyField('reorderLevel', `Safety stock (${text(item.uom)})`, text(num(item.reorder_level))),
      min: '0',
    },
    {
      ...qtyField('reorderQuantity', `Reorder quantity (${text(item.uom)})`, text(num(item.reorder_qty))),
      min: '0',
    },
    {
      name: 'leadTimeDays',
      label: 'Lead time (days)',
      type: 'number',
      min: '0',
      step: '1',
      defaultValue: text(item.lead_time_days),
    },
    description(item.description),
  ];
  openForm({
    title: `Edit ${text(item.sku)}`,
    description: locked
      ? `SKU ${text(item.sku)} and base unit ${text(item.uom)} are locked because this item already has stock movements or document references. A new standard cost only affects estimates and new order snapshots.`
      : 'A new standard cost only affects estimates and new order snapshots; posted stock and order values never change.',
    submit: 'Save changes',
    fields,
    run: (values, key) =>
      request(
        `/items/${item.id}`,
        {
          sku: text(item.sku),
          uom: text(item.uom),
          description: '',
          categoryId: null,
          ...compact(values),
        },
        key,
      ),
  });
}

/** Add or change an item conversion (1 unit = factor × base unit). */
export async function editConversion(workspace: Workspace, detail: ItemDetail, existing?: Row) {
  const { openForm, notify } = workspace;
  const item = detail.item;
  const base = text(item.uom);
  let units: Row[];
  try {
    units = (await request<{ items: Row[] }>('/units')).items;
  } catch (e) {
    notify((e as Error).message);
    return;
  }
  const baseUnit = units.find((u) => u.code === base);
  const taken = new Set(detail.conversions.map((c) => text(c.unit_code)));
  const choices = units.filter(
    (u) =>
      u.active &&
      u.code !== base &&
      !taken.has(text(u.code)) &&
      !(u.ratio != null && baseUnit?.ratio != null && u.kind === baseUnit.kind),
  );
  const usage = existing
    ? existing.purchase && existing.sales
      ? 'both'
      : existing.purchase
        ? 'purchase'
        : existing.sales
          ? 'sales'
          : 'none'
    : 'both';
  openForm({
    title: existing ? `Edit ${text(existing.unit_code)} conversion` : 'Add unit conversion',
    description: `How many ${base} one unit holds for ${text(item.sku)}. Units of the same kind with a fixed ratio (g, t) convert automatically. Documents keep the base quantity they posted.`,
    submit: existing ? 'Save conversion' : 'Add conversion',
    fields: [
      ...(existing
        ? []
        : [
            selectField(
              'unit',
              'Alternate unit',
              choices.map((u) => ({ value: text(u.code), label: `${text(u.name)} (${text(u.code)})` })),
            ),
          ]),
      {
        ...qtyField('factor', `${base} in one unit`, existing ? text(num(existing.factor)) : undefined),
        min: '0.000000000001',
        step: '0.000000000001',
        hint: 'e.g. 25 for a 25 kg bag, 50 for a box of 50 pcs',
      },
      selectField(
        'usage',
        'Use on documents',
        [
          { value: 'both', label: 'Purchasing and sales' },
          { value: 'purchase', label: 'Purchasing only' },
          { value: 'sales', label: 'Sales only' },
          { value: 'none', label: 'Internal only (counting, scanning)' },
        ],
        usage,
      ),
    ],
    run: (values, key) =>
      request(
        `/items/${item.id}/conversions`,
        {
          unit: existing ? text(existing.unit_code) : values.unit,
          factor: values.factor,
          purchase: values.usage === 'both' || values.usage === 'purchase',
          sales: values.usage === 'both' || values.usage === 'sales',
        },
        key,
      ),
  });
}

export function addBarcode({ openForm }: Workspace, detail: ItemDetail) {
  const item = detail.item;
  openForm({
    title: 'Add barcode',
    description: `Barcodes are unique across your organization. A barcode can scan as one ${text(item.uom)} or as one of the item's alternate units (a carton barcode = one box).`,
    submit: 'Add barcode',
    fields: [
      { name: 'barcode', label: 'Barcode', hint: 'EAN/UPC/GTIN or an internal code, no spaces' },
      selectField(
        'unit',
        'One scan equals',
        [
          { value: 'BASE', label: `1 ${text(item.uom)} (base unit)` },
          ...detail.units
            .filter((u) => u.source !== 'BASE')
            .map((u) => ({ value: u.code, label: `1 ${u.code} = ${num(u.factor)} ${text(item.uom)}` })),
        ],
        'BASE',
      ),
    ],
    run: (values, key) =>
      request(
        `/items/${item.id}/barcodes`,
        { barcode: values.barcode.trim(), unit: values.unit === 'BASE' ? null : values.unit },
        key,
      ),
  });
}

export function createCategory({ openForm }: Workspace, categories: Row[], parent?: Row) {
  openForm({
    title: parent ? `New category under ${text(parent.name)}` : 'New item category',
    submit: 'Create category',
    fields: [
      { name: 'name', label: 'Category name' },
      {
        ...selectField('parentId', 'Parent category', categoryOptions(categories), parent?.id),
        optional: true,
      },
      { name: 'description', label: 'Description', type: 'textarea', optional: true },
    ],
    run: (values, key) => request('/item-categories', compact(values), key),
  });
}

export function editCategory({ openForm }: Workspace, categories: Row[], category: Row) {
  openForm({
    title: `Edit ${text(category.name)}`,
    description: 'Items keep an inactive category, but it can no longer be chosen for new items.',
    submit: 'Save category',
    fields: [
      { name: 'name', label: 'Category name', defaultValue: text(category.name) },
      {
        ...selectField(
          'parentId',
          'Parent category',
          categoryOptions(
            categories.filter((c) => c.id !== category.id),
            category.parent_id,
          ),
          text(category.parent_id),
        ),
        optional: true,
      },
      selectField(
        'active',
        'Status',
        [
          { value: 'true', label: 'Active' },
          { value: 'false', label: 'Inactive' },
        ],
        String(!!category.active),
      ),
      {
        name: 'description',
        label: 'Description',
        type: 'textarea',
        optional: true,
        defaultValue: text(category.description),
      },
    ],
    run: (values, key) =>
      request(`/item-categories/${category.id}`, { parentId: null, ...compact(values) }, key),
  });
}

export function createUnit({ openForm }: Workspace) {
  openForm({
    title: 'New unit of measure',
    description:
      'Package units (box, bag, roll) get their size per item as a conversion. Other kinds may declare a fixed size in their reference unit (kg, l, m, m², pcs) to convert automatically.',
    submit: 'Create unit',
    fields: [
      { name: 'code', label: 'Code', hint: 'Letters and digits, e.g. lb, ctn' },
      { name: 'name', label: 'Name' },
      selectField(
        'kind',
        'Kind',
        unitKinds.map((k) => ({ value: k, label: k.charAt(0) + k.slice(1).toLowerCase() })),
        'PACKAGE',
      ),
      {
        name: 'decimals',
        label: 'Decimal places',
        type: 'number',
        min: '0',
        step: '1',
        defaultValue: '0',
      },
      {
        ...qtyField('ratio', 'Size in reference unit'),
        min: '0.000000000001',
        step: '0.000000000001',
        optional: true,
        hint: 'e.g. 0.45359237 for lb (kg). Leave empty for package units',
      },
    ],
    run: (values, key) => request('/units', compact(values), key),
  });
}

export function editUnit({ openForm }: Workspace, unit: Row) {
  const used = num(unit.base_items) + num(unit.conversions) > 0;
  openForm({
    title: `Edit ${text(unit.code)}`,
    description: used
      ? 'Precision is fixed because items already use this unit. Inactive units stay on existing items but cannot be chosen again.'
      : undefined,
    submit: 'Save unit',
    fields: [
      { name: 'name', label: 'Name', defaultValue: text(unit.name) },
      {
        name: 'decimals',
        label: 'Decimal places',
        type: 'number',
        min: '0',
        step: '1',
        defaultValue: text(unit.decimals),
      },
      selectField(
        'active',
        'Status',
        [
          { value: 'true', label: 'Active' },
          { value: 'false', label: 'Inactive' },
        ],
        String(!!unit.active),
      ),
    ],
    run: (values, key) => request(`/units/${unit.code}`, values, key),
  });
}

const attributesField = (value = ''): Field => ({
  name: 'attributes',
  label: 'Attributes (one per line)',
  type: 'textarea',
  defaultValue: value || 'Size: 500ml, 1L\nColour: Clear, Blue',
  hint: 'Name: value, value… — up to 5 attributes',
});

/** New variant family: the template is not stocked; its variants are generated as ordinary items. */
export async function createFamily(workspace: Workspace) {
  const { openForm, notify } = workspace;
  let data: Lists;
  try {
    data = await lists();
  } catch (e) {
    notify((e as Error).message);
    return;
  }
  openForm({
    title: 'New variant family',
    description:
      'A family defines the shared type, base unit and attributes. Each variant becomes a normal item with its own SKU, cost and stock.',
    submit: 'Create family',
    fields: [
      { name: 'code', label: 'Family code', hint: 'e.g. BTL-PET' },
      { name: 'name', label: 'Family name' },
      selectField('type', 'Item type', typeOptions, 'FINISHED_GOOD'),
      selectField('uom', 'Base unit', unitOptions(data.units), 'pcs'),
      { ...selectField('categoryId', 'Category', categoryOptions(data.categories)), optional: true },
      attributesField(),
      description(),
    ],
    run: (values, key) =>
      request('/item-templates', { ...compact(values), attributes: parseAttributes(values.attributes) }, key),
  });
}

export async function editFamily(workspace: Workspace, family: FamilyDetail) {
  const { openForm, notify } = workspace;
  let data: Lists;
  try {
    data = await lists();
  } catch (e) {
    notify((e as Error).message);
    return;
  }
  const t = family.template;
  openForm({
    title: `Edit ${text(t.code)}`,
    description: family.variants.length
      ? 'Variants exist, so attribute names are fixed and used values must stay. You can add new values at any time.'
      : undefined,
    submit: 'Save family',
    fields: [
      { name: 'name', label: 'Family name', defaultValue: text(t.name) },
      {
        ...selectField(
          'categoryId',
          'Category',
          categoryOptions(data.categories, t.category_id),
          text(t.category_id),
        ),
        optional: true,
      },
      attributesField(formatAttributes(t.attributes)),
      description(t.description),
    ],
    run: (values, key) =>
      request(
        `/item-templates/${t.id}`,
        { categoryId: null, ...compact(values), attributes: parseAttributes(values.attributes) },
        key,
      ),
  });
}
