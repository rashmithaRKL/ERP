import { useQuery } from '@tanstack/react-query';
import { Factory, Plus } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { selectField, Table } from '../../components';
import type { Field } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { fmt, labelState, money, text } from '../../lib/format';
import {
  INVENTORY,
  OPERATORS,
  useInventoryCommand,
  useWarehouses,
  WAREHOUSE_KIND_LABELS,
  WAREHOUSE_KINDS,
} from './api';
import type { Valuation } from './api';
import { QueryState, StatusBadge } from './shared';

const VALUATION_ROLES = ['ADMIN', 'PLANNER', 'WAREHOUSE'];

/** Warehouses of the plant with stock and value per warehouse; create and edit dialogs. */
export function WarehousesTab() {
  const { can, allowed, openForm } = useWorkspace();
  const { invalidate } = useInventoryCommand();
  const list = useWarehouses();
  const valuation = useQuery({
    queryKey: [INVENTORY, 'valuation'],
    queryFn: () => request<Valuation>('/inventory/valuation'),
    enabled: allowed(VALUATION_ROLES),
  });
  const values = new Map((valuation.data?.warehouses ?? []).map((w) => [w.id, w]));
  const kinds = WAREHOUSE_KINDS.map((k) => ({ value: k, label: WAREHOUSE_KIND_LABELS[k] }));
  const fields = (w?: Row): Field[] => [
    { name: 'name', label: 'Warehouse name', defaultValue: text(w?.name) },
    {
      name: 'code',
      label: 'Code',
      defaultValue: text(w?.code),
      hint: 'Up to 20 letters, digits or hyphens; unique in your organization.',
    },
    selectField('kind', 'Kind', kinds, text(w?.kind || 'RAW')),
    ...(w
      ? [
          selectField(
            'active',
            'Status',
            [
              { value: 'true', label: 'Active' },
              { value: 'false', label: 'Inactive (no stock may enter)' },
            ],
            String(w.active !== false),
          ),
        ]
      : []),
  ];
  function create() {
    openForm({
      title: 'New warehouse',
      description:
        'Warehouses belong to your plant. Raw-material warehouses can receive purchase deliveries.',
      submit: 'Create warehouse',
      fields: fields(),
      run: async (v, key) => {
        await request('/inventory/warehouses', { name: v.name, code: v.code, kind: v.kind }, key);
        await invalidate();
      },
    });
  }
  function edit(w: Row) {
    openForm({
      title: `Edit ${text(w.name)}`,
      description: w.production_output
        ? 'This warehouse receives production output, so it stays an active finished-goods warehouse.'
        : 'A warehouse can be deactivated or change kind only while it holds no stock.',
      submit: 'Save warehouse',
      fields: fields(w),
      run: async (v, key) => {
        await request(
          `/inventory/warehouses/${w.id}`,
          { name: v.name, code: v.code, kind: v.kind, active: v.active === 'true' },
          key,
        );
        await invalidate();
      },
    });
  }
  const rows = list.data?.items ?? [];
  return (
    <>
      <div className="inv-toolbar">
        <p className="muted inv-small">
          {fmt(rows.filter((w) => w.active !== false).length)} active of {fmt(rows.length)} warehouses
        </p>
        {can(...OPERATORS) && (
          <div className="inv-actions">
            <button className="button primary small" onClick={create}>
              <Plus size={14} />
              New warehouse
            </button>
          </div>
        )}
      </div>
      <QueryState pending={list.isPending} error={list.error} retry={() => list.refetch()}>
        <Table
          rows={rows}
          compact
          pageSize={Math.max(rows.length, 1)}
          onRow={can(...OPERATORS) ? edit : undefined}
          columns={[
            {
              label: 'Warehouse',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.name)}</strong>
                  <span>
                    {text(r.code)} · {text(r.factory)}
                  </span>
                </div>
              ),
            },
            {
              label: 'Kind',
              render: (r) => (
                <span className="inv-kind">
                  {WAREHOUSE_KIND_LABELS[text(r.kind)] || labelState(r.kind)}
                  {r.production_output === true && (
                    <span className="inv-chip small" title="Manufacturing posts finished output here">
                      <Factory size={11} />
                      Production output
                    </span>
                  )}
                </span>
              ),
            },
            { label: 'Lots with stock', render: (r) => fmt(r.lots) },
            { label: 'Quarantined', render: (r) => fmt(r.quarantined_lots) },
            { label: 'Open counts', render: (r) => fmt(r.open_counts) },
            ...(allowed(VALUATION_ROLES)
              ? [
                  { label: 'Stock value', render: (r: Row) => money(values.get(r.id)?.value) },
                  { label: 'Reserved value', render: (r: Row) => money(values.get(r.id)?.reserved_value) },
                ]
              : []),
            {
              label: 'Status',
              render: (r) => <StatusBadge value={r.active === false ? 'INACTIVE' : 'ACTIVE'} />,
            },
          ]}
        />
      </QueryState>
      {valuation.data && (
        <div className="inv-valuation">
          <div>
            <span>Total stock value</span>
            <strong>{money(valuation.data.total)}</strong>
          </div>
          {valuation.data.accounts.map((a) => (
            <div key={text(a.account)}>
              <span>{labelState(a.account)}</span>
              <strong>{money(a.value)}</strong>
            </div>
          ))}
          <p className="muted inv-small">
            Inventory accounts follow the item type, so moving stock between warehouses never changes the
            ledger account.
          </p>
        </div>
      )}
    </>
  );
}
