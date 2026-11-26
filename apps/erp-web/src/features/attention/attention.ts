import {
  CalendarClock,
  ClipboardCheck,
  ListChecks,
  Network,
  Package,
  ShieldAlert,
  ShoppingCart,
  Timer,
  Truck,
  Wrench,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { Row, State } from '../../lib/api';
import { fmt, num, text } from '../../lib/format';
import { isActive } from '../../lib/rows';

/**
 * The single attention model (V16). The Overview "Needs your attention" panel, the attention center and the header bell
 * all read {@link buildAttention}, so their counts, items, links and the "all caught up" state always agree.
 *
 * A **group** is one kind of exception (open breakdowns, late scheduled orders, …) with the number of records behind it
 * (`count`, tenant-wide where the state key provides a count) and up to {@link ITEMS_PER_GROUP} detail items for the
 * attention center; when a group has more records than items, a final "more" item links to the page. The total is the
 * sum of the group counts. A group only appears when the user's role can open the page it links to.
 */

export type AttentionTone = 'red' | 'amber' | 'blue';

export type AttentionItem = { key: string; title: string; body: string; path: string };

export type AttentionGroup = {
  key: string;
  tone: AttentionTone;
  icon: LucideIcon;
  /** Records behind the group (e.g. open breakdowns), never less than the detail items. */
  count: number;
  /** One-line summary for the overview card. */
  title: string;
  body: string;
  /** Link text and target of the overview card. */
  action: string;
  path: string;
  /** Detail rows for the attention center (at most ITEMS_PER_GROUP, plus a "more" row). */
  items: AttentionItem[];
};

export type Attention = { groups: AttentionGroup[]; total: number; caughtUp: boolean };

export type AttentionInput = {
  data: State;
  user: { id: string; role: string };
  /** Business date (Asia/Colombo, `yyyy-mm-dd`). */
  today: string;
  /** Roles that may approve purchase orders under the tenant's policy (ADMIN implied). */
  purchaseApprovers: string[];
  /** Whether the user's role sees the page behind `path` in its navigation. */
  canOpen: (path: string) => boolean;
};

/** Detail items per group in the attention center. */
export const ITEMS_PER_GROUP = 5;

const plural = (count: number, one: string, many: string) => `${fmt(count)} ${count === 1 ? one : many}`;
const day = (value: unknown) => text(value).substring(0, 10);
const hoursLate = (minutes: unknown) => {
  const h = Math.round(num(minutes) / 60);
  return h >= 48 ? `${fmt(Math.round(h / 24))} days` : `${fmt(Math.max(h, 1))} h`;
};

/** A detail row and the number of records it stands for (1 by default; a summary row covers the whole group). */
type Draft = AttentionItem & { covers?: number };

/** Detail items, cut to ITEMS_PER_GROUP, plus "N more" when the group counts more records than the items cover. */
function itemsOf(
  key: string,
  count: number,
  rows: Draft[],
  path: string,
  more: [string, string],
): AttentionItem[] {
  const shown = rows.slice(0, ITEMS_PER_GROUP);
  const covered = shown.reduce((sum, r) => sum + (r.covers ?? 1), 0);
  const items = shown.map(({ key: k, title, body, path: p }) => ({ key: k, title, body, path: p }));
  return count > covered
    ? [
        ...items,
        {
          key: `${key}:more`,
          title: plural(count - covered, `more ${more[0]}`, `more ${more[1]}`),
          body: '',
          path,
        },
      ]
    : items;
}

export function buildAttention({ data, user, today, purchaseApprovers, canOpen }: AttentionInput): Attention {
  const groups: AttentionGroup[] = [];
  const add = (group: Omit<AttentionGroup, 'items'> & { rows: Draft[]; more: [string, string] }) => {
    const { rows, more, ...rest } = group;
    const count = Math.max(group.count, 0);
    if (!count || !canOpen(rest.path.split('?')[0])) return;
    groups.push({ ...rest, count, items: itemsOf(rest.key, count, rows, rest.path, more) });
  };
  const isApprover = user.role === 'ADMIN' || purchaseApprovers.includes(user.role);

  // ---- red: things that stop or endanger delivery
  const recalls = data.recalls;
  const openRecalls = (recalls?.active ?? []).filter((r) => r.status === 'OPEN');
  add({
    key: 'recalls-open',
    tone: 'red',
    icon: Network,
    count: num(recalls?.open),
    title: plural(num(recalls?.open), 'recall is not contained yet', 'recalls are not contained yet'),
    body: 'Put the affected stock on hold before it moves on.',
    action: 'Open recalls',
    path:
      num(recalls?.open) === 1 && openRecalls.length === 1
        ? `/traceability?tab=recalls&recall=${encodeURIComponent(openRecalls[0].id)}`
        : '/traceability?tab=recalls',
    rows: openRecalls.map((r) => ({
      key: `recall:${r.id}`,
      title: `Recall ${text(r.number)} is not contained yet`,
      body: `${text(r.severity).toLowerCase()} · ${text(r.title)}`,
      path: `/traceability?tab=recalls&recall=${encodeURIComponent(r.id)}`,
    })),
    more: ['open recall', 'open recalls'],
  });

  const maintenance = data.maintenance;
  add({
    key: 'breakdowns',
    tone: 'red',
    icon: Wrench,
    count: num(maintenance?.open_breakdowns),
    title: plural(
      num(maintenance?.open_breakdowns),
      'machine is down with a breakdown',
      'machines are down with breakdowns',
    ),
    body: 'Approve and start the repair to get production running again.',
    action: 'Open downtime',
    path: '/maintenance?tab=downtime',
    rows: (maintenance?.breakdowns ?? []).map((b) => ({
      key: `breakdown:${b.id}`,
      title: `${text(b.machine)} is down (breakdown)`,
      body: b.work_order
        ? `${text(b.number)} · repair ${text(b.work_order)}`
        : `${text(b.number)} · no repair requested yet`,
      path: '/maintenance?tab=downtime',
    })),
    more: ['open breakdown', 'open breakdowns'],
  });

  const schedule = data.schedule;
  const firstLate = schedule?.late?.[0];
  add({
    key: 'schedule-late',
    tone: 'red',
    icon: CalendarClock,
    count: num(schedule?.late_orders),
    title: plural(num(schedule?.late_orders), 'order will finish late', 'orders will finish late'),
    body: firstLate
      ? `${text(firstLate.number)} (${text(firstLate.product)}) is projected past its due date in the published schedule.`
      : 'The published schedule projects them past their due dates.',
    action: 'Open production schedule',
    path: '/schedule',
    rows: (schedule?.late ?? []).map((o) => ({
      key: `late:${text(o.order_id) || o.id}`,
      title: `${text(o.number)} will finish late`,
      body: `${text(o.product)} · due ${day(o.due_date)} · projected late by ${hoursLate(o.lateness_minutes)}`,
      path: '/schedule',
    })),
    more: ['late order', 'late orders'],
  });

  const overdueOrders = data.orders.filter((o) => isActive(o) && o.due_date && day(o.due_date) < today);
  add({
    key: 'orders-overdue',
    tone: 'red',
    icon: Timer,
    count: Math.max(num(data.metrics.overdue), overdueOrders.length),
    title: plural(
      Math.max(num(data.metrics.overdue), overdueOrders.length),
      'overdue manufacturing order',
      'overdue manufacturing orders',
    ),
    body: 'Review progress and delivery commitments.',
    action: 'Review overdue orders',
    path: '/manufacturing',
    rows: overdueOrders.map((o) => ({
      key: `overdue:${o.id}`,
      title: `${text(o.number)} is overdue`,
      body: `${text(o.product)} · due ${day(o.due_date)}`,
      path: '/manufacturing',
    })),
    more: ['overdue order', 'overdue orders'],
  });

  const alerts = data.qualityAlerts;
  add({
    key: 'ncrs-critical',
    tone: 'red',
    icon: ShieldAlert,
    count: num(alerts?.open_critical_ncrs),
    title: `${plural(num(alerts?.open_critical_ncrs), 'critical nonconformance', 'critical nonconformances')} open`,
    body: 'Held stock is waiting for a disposition decision.',
    action: 'Review nonconformances',
    path: '/quality?tab=ncrs',
    rows: [
      {
        key: 'ncrs-critical:summary',
        title: `${plural(num(alerts?.open_critical_ncrs), 'critical nonconformance', 'critical nonconformances')} open`,
        body: 'Held stock is waiting for a disposition decision',
        path: '/quality?tab=ncrs',
        covers: num(alerts?.open_critical_ncrs),
      },
    ],
    more: ['critical nonconformance', 'critical nonconformances'],
  });

  // ---- amber: act soon
  const shortages = data.mrp.filter((m) => num(m.shortage) > 0);
  add({
    key: 'shortages',
    tone: 'amber',
    icon: Package,
    count: shortages.length,
    title:
      shortages.length === 1
        ? `${text(shortages[0].name).split(' · ')[0]} is running low`
        : `${fmt(shortages.length)} materials are running low`,
    body: 'Net requirements exceed released stock and incoming supply.',
    action: 'Review material planning',
    path: '/planning',
    rows: shortages.map((m) => ({
      key: `shortage:${m.id}`,
      title: `${text(m.name).split(' · ')[0]} needs replenishment`,
      body: `${fmt(m.shortage, 3)} ${text(m.uom)} net shortage`,
      path: '/planning',
    })),
    more: ['material short', 'materials short'],
  });

  const notify = (recalls?.active ?? []).filter(
    (r) => r.status === 'CONTAINED' && num(r.pending_customers) > 0,
  );
  add({
    key: 'recall-customers',
    tone: 'amber',
    icon: Network,
    count: num(recalls?.pending_customers),
    title: plural(
      num(recalls?.pending_customers),
      'recalled customer to notify',
      'recalled customers to notify',
    ),
    body: 'Record each customer notification to close the recall.',
    action: 'Open recalls',
    path:
      notify.length === 1
        ? `/traceability?tab=recalls&recall=${encodeURIComponent(notify[0].id)}`
        : '/traceability?tab=recalls',
    rows: notify.map((r) => ({
      key: `recall-customers:${r.id}`,
      title: `Recall ${text(r.number)}: ${plural(num(r.pending_customers), 'customer', 'customers')} to notify`,
      body: `${text(r.severity).toLowerCase()} · ${text(r.title)}`,
      path: `/traceability?tab=recalls&recall=${encodeURIComponent(r.id)}`,
      covers: num(r.pending_customers),
    })),
    more: ['customer to notify', 'customers to notify'],
  });

  add({
    key: 'plans-overdue',
    tone: 'amber',
    icon: Wrench,
    count: num(maintenance?.overdue_plans),
    title: plural(
      num(maintenance?.overdue_plans),
      'preventive plan is overdue',
      'preventive plans are overdue',
    ),
    body: 'Generate the due work orders and schedule the maintenance windows.',
    action: 'Open preventive plans',
    path: '/maintenance?tab=plans',
    rows: (maintenance?.overdue ?? []).map((p) => ({
      key: `plan:${p.id}`,
      title: `${text(p.title)} overdue on ${text(p.machine)}`,
      body: `Preventive plan ${text(p.number)} was due ${day(p.next_due_date)}`,
      path: '/maintenance?tab=plans',
    })),
    more: ['overdue plan', 'overdue plans'],
  });

  add({
    key: 'capa-overdue',
    tone: 'amber',
    icon: ListChecks,
    count: num(alerts?.overdue_capa_actions),
    title: `${plural(num(alerts?.overdue_capa_actions), 'CAPA action is', 'CAPA actions are')} overdue`,
    body: 'Corrective and preventive actions are past their due date.',
    action: 'Open CAPA',
    path: '/quality?tab=capa',
    rows: [
      {
        key: 'capa-overdue:summary',
        title: `${plural(num(alerts?.overdue_capa_actions), 'CAPA action', 'CAPA actions')} overdue`,
        body:
          num(alerts?.my_overdue_capa_actions) > 0
            ? `${fmt(alerts?.my_overdue_capa_actions)} of them assigned to you`
            : 'Corrective and preventive actions past their due date',
        path: '/quality?tab=capa',
        covers: num(alerts?.overdue_capa_actions),
      },
    ],
    more: ['overdue CAPA action', 'overdue CAPA actions'],
  });

  const awaiting = isApprover
    ? data.purchases.filter(
        (p) => p.status === 'SUBMITTED' && p.created_by !== user.id && p.submitted_by !== user.id,
      )
    : [];
  add({
    key: 'purchase-approvals',
    tone: 'amber',
    icon: ShoppingCart,
    count: awaiting.length,
    title: plural(
      awaiting.length,
      'purchase order awaits your approval',
      'purchase orders await your approval',
    ),
    body: 'Approve them in Purchasing before goods can be received.',
    action: 'Open purchasing',
    path: '/purchasing',
    rows: awaiting.map((p: Row) => ({
      key: `po-approval:${p.id}`,
      title: `${text(p.number)} awaits approval`,
      body: `${text(p.supplier) || 'No supplier yet'} · ${text(p.item)}`,
      path: '/purchasing',
    })),
    more: ['purchase order to approve', 'purchase orders to approve'],
  });

  const late = data.purchases.filter(
    (p) => ['APPROVED', 'PARTIAL'].includes(text(p.status)) && p.due_date && day(p.due_date) < today,
  );
  add({
    key: 'deliveries-late',
    tone: 'amber',
    icon: Truck,
    count: late.length,
    title: plural(late.length, 'supplier delivery is late', 'supplier deliveries are late'),
    body: 'Approved purchase orders are past their expected delivery date.',
    action: 'Open purchasing',
    path: '/purchasing',
    rows: late.map((p: Row) => ({
      key: `po-late:${p.id}`,
      title: `${text(p.number)} delivery is late`,
      body: `${text(p.supplier)} · expected ${day(p.due_date)}`,
      path: '/purchasing',
    })),
    more: ['late delivery', 'late deliveries'],
  });

  // V16: orders, operations, maintenance windows or downtime changed after the published schedule was created.
  const changes = num(schedule?.changes_since_publish);
  if (user.role === 'ADMIN' || user.role === 'PLANNER')
    add({
      key: 'schedule-stale',
      tone: 'amber',
      icon: CalendarClock,
      count: schedule?.published && changes > 0 ? 1 : 0,
      title: 'The published schedule may be out of date',
      body: `${plural(changes, 'change', 'changes')} to orders, operations, maintenance, downtime or calendars since it was run. Run the scheduler again.`,
      action: 'Open production schedule',
      path: '/schedule',
      rows: [
        {
          key: 'schedule-stale:summary',
          title: 'The published schedule may be out of date',
          body: `${plural(changes, 'change', 'changes')} since ${text(schedule?.published?.number)} was run`,
          path: '/schedule',
        },
      ],
      more: ['', ''],
    });

  // ---- blue: routine work waiting
  const pending = data.inspections.filter((i) => i.status === 'PENDING');
  add({
    key: 'inspections',
    tone: 'blue',
    icon: ClipboardCheck,
    count: Math.max(num(data.metrics.pending_qc), pending.length),
    title: plural(
      Math.max(num(data.metrics.pending_qc), pending.length),
      'inspection awaits review',
      'inspections await review',
    ),
    body: 'Quarantined stock needs a quality decision.',
    action: 'Open quality control',
    path: '/quality',
    rows: pending.map((q) => ({
      key: `inspection:${q.id}`,
      title: `${text(q.item)} awaiting inspection`,
      body: `Lot ${text(q.lot)}`,
      path: '/quality',
    })),
    more: ['inspection waiting', 'inspections waiting'],
  });

  const total = groups.reduce((sum, g) => sum + g.count, 0);
  return { groups, total, caughtUp: total === 0 };
}
