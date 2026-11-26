import { describe, expect, it } from 'vitest';
import type { Row, State } from '../../lib/api';
import { buildAttention, ITEMS_PER_GROUP } from './attention';
import type { AttentionInput } from './attention';

const TODAY = '2026-11-26';

function state(overrides: Partial<State> = {}): State {
  return {
    company: { id: 't' },
    factories: [],
    warehouses: [],
    partners: [],
    machines: [],
    items: [],
    orders: [],
    boms: [],
    bomLines: [],
    sales: [],
    purchases: [],
    lots: [],
    inspections: [],
    mrp: [],
    trend: [],
    metrics: { id: 'metrics', overdue: 0, pending_qc: 0 },
    sync: { id: 'sync' },
    maintenance: {
      open_breakdowns: 0,
      open_downtime: 0,
      overdue_plans: 0,
      awaiting_approval: 0,
      in_progress: 0,
      breakdowns: [],
      overdue: [],
    },
    qualityAlerts: {
      open_ncrs: 0,
      open_critical_ncrs: 0,
      overdue_capa_actions: 0,
      my_overdue_capa_actions: 0,
    },
    schedule: { published: null, late_orders: 0, unscheduled_orders: 0, late: [] },
    recalls: { open: 0, contained: 0, pending_customers: 0, active: [] },
    ...overrides,
  };
}

const input = (data: State, extra: Partial<AttentionInput> = {}): AttentionInput => ({
  data,
  user: { id: 'u-planner', role: 'PLANNER' },
  today: TODAY,
  purchaseApprovers: ['PLANNER'],
  canOpen: () => true,
  ...extra,
});

const rows = (n: number, make: (i: number) => Row) => Array.from({ length: n }, (_, i) => make(i));

describe('the shared attention model', () => {
  it('is caught up when nothing needs attention', () => {
    const a = buildAttention(input(state()));
    expect(a).toEqual({ groups: [], total: 0, caughtUp: true });
  });

  it('counts every record once and lists the same records the overview summarizes', () => {
    const data = state({
      maintenance: {
        open_breakdowns: 7,
        open_downtime: 8,
        overdue_plans: 1,
        awaiting_approval: 0,
        in_progress: 0,
        breakdowns: rows(5, (i) => ({ id: `d${i}`, number: `DT-${i}`, machine: `M${i}`, work_order: null })),
        overdue: [
          { id: 'p1', number: 'PM-1', title: 'Lubrication', machine: 'IM-01', next_due_date: '2026-11-20' },
        ],
      },
      qualityAlerts: { open_ncrs: 4, open_critical_ncrs: 3, overdue_capa_actions: 0 },
      recalls: {
        open: 1,
        contained: 1,
        pending_customers: 2,
        active: [
          {
            id: 'r1',
            number: 'RCL-1',
            status: 'OPEN',
            severity: 'CRITICAL',
            title: 'Resin',
            pending_customers: 0,
          },
          {
            id: 'r2',
            number: 'RCL-2',
            status: 'CONTAINED',
            severity: 'MAJOR',
            title: 'Caps',
            pending_customers: 2,
          },
        ],
      },
      mrp: [
        { id: 'm1', name: 'HDPE resin · RM-001', shortage: 12.5, uom: 'kg' },
        { id: 'm2', name: 'Caps', shortage: 0, uom: 'pcs' },
      ],
      orders: [
        { id: 'o1', number: 'MO-1', status: 'IN_PROGRESS', due_date: '2026-11-25', product: 'Bottle' },
        { id: 'o2', number: 'MO-2', status: 'COMPLETED', due_date: '2026-11-01', product: 'Bottle' },
        { id: 'o3', number: 'MO-3', status: 'APPROVED', due_date: TODAY, product: 'Preform' },
      ],
      metrics: { id: 'metrics', overdue: 1, pending_qc: 2 },
      inspections: [
        { id: 'i1', status: 'PENDING', item: 'Resin', lot: 'L-1' },
        { id: 'i2', status: 'PENDING', item: 'Bottle', lot: 'L-2' },
        { id: 'i3', status: 'PASSED', item: 'Caps', lot: 'L-3' },
      ],
    });
    const a = buildAttention(input(data));
    const byKey = Object.fromEntries(a.groups.map((g) => [g.key, g]));
    expect(a.groups.map((g) => g.key)).toEqual([
      'recalls-open',
      'breakdowns',
      'orders-overdue',
      'ncrs-critical',
      'shortages',
      'recall-customers',
      'plans-overdue',
      'inspections',
    ]);
    expect(a.groups.map((g) => g.tone)).toEqual([
      'red',
      'red',
      'red',
      'red',
      'amber',
      'amber',
      'amber',
      'blue',
    ]);
    // 1 recall + 7 breakdowns + 1 overdue order + 3 critical NCRs + 1 shortage + 2 customers + 1 plan + 2 inspections
    expect(a.total).toBe(18);
    expect(a.caughtUp).toBe(false);
    // Five breakdown rows plus "2 more" (the state key lists at most five).
    expect(byKey.breakdowns.items).toHaveLength(ITEMS_PER_GROUP + 1);
    expect(byKey.breakdowns.items.at(-1)).toMatchObject({
      title: '2 more open breakdowns',
      path: '/maintenance?tab=downtime',
    });
    expect(byKey.breakdowns.title).toBe('7 machines are down with breakdowns');
    // A summary row stands for all critical NCRs: no "more" row.
    expect(byKey['ncrs-critical'].items).toHaveLength(1);
    // One row per contained recall, covering its customers; a single recall links straight to its drawer.
    expect(byKey['recall-customers'].items).toEqual([
      expect.objectContaining({
        title: 'Recall RCL-2: 2 customers to notify',
        path: '/traceability?tab=recalls&recall=r2',
      }),
    ]);
    expect(byKey['recalls-open'].path).toBe('/traceability?tab=recalls&recall=r1');
    expect(byKey.shortages.title).toBe('HDPE resin is running low');
    expect(byKey['orders-overdue'].items.map((i) => i.title)).toEqual(['MO-1 is overdue']);
    expect(byKey.inspections.count).toBe(2);
    expect(byKey['plans-overdue'].path).toBe('/maintenance?tab=plans');
  });

  it('only shows groups whose page the role can open', () => {
    const data = state({
      mrp: [{ id: 'm1', name: 'Resin', shortage: 3, uom: 'kg' }],
      maintenance: {
        open_breakdowns: 1,
        open_downtime: 1,
        overdue_plans: 0,
        awaiting_approval: 0,
        in_progress: 0,
        breakdowns: [{ id: 'd1', number: 'DT-1', machine: 'BM-01' }],
        overdue: [],
      },
      metrics: { id: 'metrics', overdue: 0, pending_qc: 1 },
      inspections: [{ id: 'i1', status: 'PENDING', item: 'Resin', lot: 'L-1' }],
    });
    const sales = buildAttention(
      input(data, {
        user: { id: 'u-sales', role: 'SALES' },
        canOpen: (path) => !['/planning', '/maintenance', '/purchasing'].includes(path),
      }),
    );
    expect(sales.groups.map((g) => g.key)).toEqual(['inspections']);
    expect(sales.total).toBe(1);
  });

  it('asks approvers to approve other people’s purchase orders and flags late deliveries', () => {
    const data = state({
      purchases: [
        {
          id: 'po1',
          number: 'PO-1',
          status: 'SUBMITTED',
          created_by: 'u-wh',
          submitted_by: 'u-wh',
          supplier: 'Lanka Polymer',
        },
        {
          id: 'po2',
          number: 'PO-2',
          status: 'SUBMITTED',
          created_by: 'u-planner',
          submitted_by: 'u-planner',
        },
        { id: 'po3', number: 'PO-3', status: 'APPROVED', due_date: '2026-11-20', supplier: 'Ceylon Caps' },
        { id: 'po4', number: 'PO-4', status: 'PARTIAL', due_date: '2026-12-20', supplier: 'Ceylon Caps' },
      ],
    });
    const planner = buildAttention(input(data));
    expect(planner.groups.map((g) => [g.key, g.count])).toEqual([
      ['purchase-approvals', 1],
      ['deliveries-late', 1],
    ]);
    const warehouse = buildAttention(input(data, { user: { id: 'u-wh', role: 'WAREHOUSE' } }));
    expect(warehouse.groups.map((g) => g.key)).toEqual(['deliveries-late']);
  });

  it('tells planners when the published schedule may be out of date', () => {
    const data = state({
      schedule: {
        published: { id: 'run1', number: 'SCH-3' },
        late_orders: 2,
        unscheduled_orders: 0,
        late: [
          {
            id: 'x',
            order_id: 'o1',
            number: 'MO-9',
            product: 'Bottle',
            due_date: TODAY,
            lateness_minutes: 150,
          },
        ],
        changes_since_publish: 4,
      },
    });
    const planner = buildAttention(input(data));
    expect(planner.groups.map((g) => g.key)).toEqual(['schedule-late', 'schedule-stale']);
    expect(planner.total).toBe(3);
    expect(planner.groups[0].items[0].body).toBe('Bottle · due 2026-11-26 · projected late by 3 h');
    expect(planner.groups[0].items.at(-1)?.title).toBe('1 more late order');
    expect(planner.groups[1].items[0].body).toBe('4 changes since SCH-3 was run');
    const operator = buildAttention(input(data, { user: { id: 'u-op', role: 'OPERATOR' } }));
    expect(operator.groups.map((g) => g.key)).toEqual(['schedule-late']);
  });
});
