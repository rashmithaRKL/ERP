import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { ClipboardList, EyeOff } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { option, Progress, selectField, Table } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { fmt, labelState, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { OPERATORS, useDebounced, useInventoryCommand, usePaged } from './api';
import { AdjustmentDrawer } from './AdjustmentDrawer';
import { CountSheet } from './CountSheet';
import { dateTime, LoadMore, QueryState, SearchBox, StatusBadge } from './shared';

const STATUSES = ['OPEN', 'all', 'COUNTING', 'REVIEW', 'POSTED', 'CANCELLED'];

/** Cycle counts: start a count, open the count sheet (entry / review / posting). */
export function CountsTab({ open: openCount }: { open: number }) {
  const data = useStateData();
  const { can, openForm } = useWorkspace();
  const { invalidate } = useInventoryCommand();
  const [status, setStatus] = useState('OPEN');
  const [warehouseId, setWarehouseId] = useState('');
  const [search, setSearch] = useState('');
  const [sheet, setSheet] = useState<string>();
  const [adjustment, setAdjustment] = useState<string>();
  const q = useDebounced(search);
  const { query, rows } = usePaged('counts', '/inventory/counts', {
    status: status === 'all' ? undefined : status,
    warehouseId,
    q,
    limit: 50,
  });
  const types = [...new Set(data.items.map((i) => text(i.type)))];
  function startCount() {
    openForm({
      title: 'Start a cycle count',
      description:
        'System quantities are snapshotted per lot now. Lots already in an open count, and receipts awaiting incoming inspection, cannot be included.',
      submit: 'Start count',
      fields: [
        selectField(
          'warehouseId',
          'Warehouse',
          option(
            data.warehouses.filter((w) => w.active !== false),
            'name',
          ),
        ),
        {
          ...selectField(
            'itemId',
            'Only this item',
            option(
              data.items.filter((i) => i.active !== false),
              'name',
            ),
          ),
          optional: true,
        },
        {
          ...selectField(
            'itemType',
            'Only this item type',
            types.map((t) => ({ value: t, label: labelState(t) })),
          ),
          optional: true,
        },
        selectField(
          'blind',
          'Counting mode',
          [
            { value: 'false', label: 'Show system quantity to counters' },
            { value: 'true', label: 'Blind count (hide system quantity)' },
          ],
          'true',
        ),
        { name: 'notes', label: 'Instructions for counters', type: 'textarea', optional: true },
      ],
      run: async (values, key) => {
        const created = await request<Row>(
          '/inventory/counts',
          {
            warehouseId: values.warehouseId,
            itemId: values.itemId || null,
            itemType: values.itemType || null,
            blind: values.blind === 'true',
            notes: values.notes || '',
          },
          key,
        );
        await invalidate();
        setSheet(created.id);
      },
    });
  }
  return (
    <>
      <Tabs
        compact
        label="Count status"
        items={STATUSES.map((s) => ({
          value: s,
          label: s === 'all' ? 'All' : s === 'OPEN' ? 'Open' : labelState(s),
          count: s === 'OPEN' && openCount ? openCount : undefined,
        }))}
        value={status}
        onChange={setStatus}
      />
      <div className="inv-toolbar">
        <div className="inv-filters">
          <SearchBox value={search} onChange={setSearch} placeholder="Search count, notes or warehouse…" />
          <select aria-label="Warehouse" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
            <option value="">All warehouses</option>
            {data.warehouses.map((w) => (
              <option key={w.id} value={w.id}>
                {text(w.name)}
              </option>
            ))}
          </select>
        </div>
        {can(...OPERATORS) && (
          <div className="inv-actions">
            <button className="button primary small" onClick={startCount}>
              <ClipboardList size={14} />
              Start count
            </button>
          </div>
        )}
      </div>
      <QueryState pending={query.isPending} error={query.error} retry={() => query.refetch()}>
        <Table
          rows={rows}
          compact
          pageSize={Math.max(rows.length, 1)}
          onRow={(r) => setSheet(r.id)}
          columns={[
            {
              label: 'Count',
              render: (r) => (
                <div className="cell-stack">
                  <strong>
                    {text(r.number)} {r.blind === true && <EyeOff size={12} aria-label="Blind count" />}
                  </strong>
                  <span>{text(r.created_by_name)}</span>
                </div>
              ),
            },
            {
              label: 'Scope',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.warehouse)}</strong>
                  <span>{r.item ? text(r.item) : r.item_type ? labelState(r.item_type) : 'All items'}</span>
                </div>
              ),
            },
            {
              label: 'Counted',
              render: (r) => (
                <div className="inv-progress">
                  <Progress value={(num(r.counted_count) / Math.max(num(r.line_count), 1)) * 100} />
                  <span>
                    {fmt(r.counted_count)}/{fmt(r.line_count)}
                  </span>
                </div>
              ),
            },
            { label: 'Status', render: (r) => <StatusBadge value={r.status} /> },
            {
              label: 'Variance adjustment',
              render: (r) =>
                r.adjustment_number ? (
                  <div className="cell-stack">
                    <strong>{text(r.adjustment_number)}</strong>
                    <span>{labelState(r.adjustment_status)}</span>
                  </div>
                ) : (
                  <span className="muted">—</span>
                ),
            },
            { label: 'Started', render: (r) => dateTime(r.created_at) },
          ]}
        />
        <LoadMore query={query} count={rows.length} />
      </QueryState>
      <AnimatePresence>
        {sheet && (
          <CountSheet
            key={sheet}
            id={sheet}
            onClose={() => setSheet(undefined)}
            onOpenAdjustment={(id) => {
              setSheet(undefined);
              setAdjustment(id);
            }}
          />
        )}
        {adjustment && (
          <AdjustmentDrawer key={adjustment} id={adjustment} onClose={() => setAdjustment(undefined)} />
        )}
      </AnimatePresence>
    </>
  );
}
