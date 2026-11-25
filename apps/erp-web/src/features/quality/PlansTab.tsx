import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { Pencil, Plus } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Table } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, fmt, labelState, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { DECIDERS, useDefectCodes } from './api';
import { PlanEditor } from './PlanEditor';
import { QualityBadge, QualitySearch, useDebounced } from './shared';

const PAGE = 25;
const PLAN_TYPES = ['INCOMING', 'FINAL', 'RETURN', 'REWORK'];

/** Inspection plans (`GET /api/quality/plans`) with the plan editor, and the defect-code catalogue. */
export function PlansTab() {
  const ws = useWorkspace();
  const [status, setStatus] = useState('active');
  const [type, setType] = useState('');
  const [input, setInput] = useState('');
  const [editing, setEditing] = useState<string | 'new'>();
  const q = useDebounced(input);
  const paged = useCursorPage(['quality', 'plans'], '/quality/plans', { status, type, q }, PAGE);
  const defects = useDefectCodes();
  const decider = ws.can(...DECIDERS);

  function defectForm(code?: Row) {
    ws.openForm({
      title: code ? `Defect code ${text(code.code)}` : 'New defect code',
      description: 'Defect codes classify nonconformances and inspection characteristics.',
      submit: code ? 'Save changes' : 'Create code',
      fields: [
        ...(code
          ? []
          : [{ name: 'code', label: 'Code (A–Z, 0–9, - or _)', hint: 'Up to 20 characters, e.g. BURR' }]),
        { name: 'name', label: 'Name', defaultValue: code ? text(code.name) : undefined },
        {
          name: 'description',
          label: 'Description',
          type: 'textarea',
          optional: true,
          defaultValue: code ? text(code.description) : undefined,
        },
        {
          name: 'active',
          label: 'Status',
          options: [
            { value: 'true', label: 'Active' },
            { value: 'false', label: 'Inactive' },
          ],
          defaultValue: code ? String(code.active) : 'true',
        },
      ],
      run: (v, key) =>
        request(
          code ? `/quality/defect-codes/${text(code.code)}` : '/quality/defect-codes',
          {
            code: code ? text(code.code) : v.code.trim().toUpperCase(),
            name: v.name,
            description: v.description || null,
            active: v.active === 'true',
          },
          key,
        ),
    });
  }

  return (
    <>
      <Tabs
        compact
        label="Plan status"
        items={['active', 'inactive', 'all'].map((t) => ({ value: t, label: labelState(t) }))}
        value={status}
        onChange={setStatus}
      />
      <div className="server-toolbar">
        <QualitySearch value={input} onChange={setInput} placeholder="Search plan, item or category…" />
        <select aria-label="Inspection type" value={type} onChange={(e) => setType(e.target.value)}>
          <option value="">All inspection types</option>
          {PLAN_TYPES.map((t) => (
            <option key={t} value={t}>
              {labelState(t)}
            </option>
          ))}
        </select>
        {decider && (
          <button className="button primary small qa-toolbar-action" onClick={() => setEditing('new')}>
            <Plus size={14} />
            New plan
          </button>
        )}
      </div>
      <PageState paged={paged}>
        {paged.rows.length ? (
          <Table
            rows={paged.rows}
            compact
            pageSize={PAGE}
            label="Inspection plans"
            onRow={(r) => setEditing(r.id)}
            columns={[
              {
                label: 'Plan',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.name)}</strong>
                    <span>
                      {text(r.number)} · version {text(r.version)}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Applies to',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{r.item_id ? text(r.item) : `Category ${text(r.category)}`}</strong>
                    <span>
                      {r.item_id ? text(r.sku) : 'All items in the category and its sub-categories'}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Inspection',
                render: (r) => (
                  <span className="capitalize">
                    {labelState(r.inspection_type)}
                    {r.inspection_type === 'FINAL' ? ' (also returns & rework)' : ''}
                  </span>
                ),
              },
              {
                label: 'Characteristics',
                render: (r) =>
                  `${fmt(r.characteristics)}${num(r.critical) ? ` · ${fmt(r.critical)} critical` : ''}`,
              },
              { label: 'Updated', render: (r) => date(r.updated_at) },
              { label: 'Status', render: (r) => <QualityBadge value={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
            ]}
          />
        ) : (
          <Empty
            title="No inspection plans"
            body="Without a plan, inspectors record a pass/fail decision with notes. Add a plan to measure characteristics."
          />
        )}
      </PageState>
      <PageFooter paged={paged} label="plans" />

      <div className="qa-subsection">
        <div className="qa-subsection-head">
          <div>
            <h3>Defect codes</h3>
            <p>Used to classify nonconformances and the characteristics of inspection plans.</p>
          </div>
          {decider && (
            <button className="button secondary small" onClick={() => defectForm()}>
              <Plus size={14} />
              New defect code
            </button>
          )}
        </div>
        <Table
          rows={(defects.data?.items ?? []).map((d) => ({ ...d, id: text(d.code) }))}
          compact
          pageSize={12}
          label="Defect codes"
          columns={[
            { label: 'Code', key: 'code' },
            { label: 'Name', key: 'name' },
            {
              label: 'Description',
              render: (d) => <span className="notes-cell">{text(d.description) || '—'}</span>,
            },
            { label: 'NCRs', render: (d) => fmt(d.ncrs) },
            {
              label: 'Status',
              render: (d) => (
                <div className="cell-stack">
                  <QualityBadge value={d.active ? 'ACTIVE' : 'INACTIVE'} />
                  {d.system && <span>System code</span>}
                </div>
              ),
            },
            {
              label: '',
              render: (d) =>
                decider ? (
                  <button
                    className="icon-button"
                    aria-label={`Edit defect code ${text(d.code)}`}
                    onClick={() => defectForm(d)}
                  >
                    <Pencil size={14} />
                  </button>
                ) : (
                  ''
                ),
            },
          ]}
        />
      </div>
      <AnimatePresence>
        {editing && (
          <PlanEditor
            key="plan"
            id={editing === 'new' ? undefined : editing}
            onClose={() => setEditing(undefined)}
          />
        )}
      </AnimatePresence>
    </>
  );
}
