import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowDownToLine, LoaderCircle, X } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Panel, Table } from '../../components';
import { request } from '../../lib/api';
import { text } from '../../lib/format';
import { auditLabel } from '../audit/auditLabel';
import { ADMIN, dateTime, download, params, useAdminPaged, useDebounced } from './api';
import { LoadMore, QueryState, SearchBox } from './shared';

/** The full, server-paged audit trail with filters and a server-side CSV export. ADMIN only. */
export function AuditTab() {
  const { notify } = useWorkspace();
  const [search, setSearch] = useState('');
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [exporting, setExporting] = useState(false);
  const q = useDebounced(search);
  const filters = { q, actor, action, from, to };
  const facets = useQuery({
    queryKey: [ADMIN, 'audit-facets'],
    queryFn: () => request<{ actors: string[]; actions: string[] }>('/admin/audit/facets'),
  });
  const { query, rows } = useAdminPaged('audit', '/admin/audit', { ...filters, limit: 50 });
  const filtered = !!(search || actor || action || from || to);
  const actions = [...(facets.data?.actions ?? [])].sort((a, b) =>
    auditLabel(a).localeCompare(auditLabel(b)),
  );

  async function exportCsv() {
    setExporting(true);
    try {
      const headers = await download(`/admin/audit/export${params(filters)}`, 'audit-trail.csv');
      notify(
        headers.get('X-Export-Truncated') === 'true'
          ? 'Audit export downloaded (first 50,000 events; narrow the filters for the rest).'
          : 'Audit export downloaded.',
      );
    } catch (e) {
      notify((e as Error).message);
    } finally {
      setExporting(false);
    }
  }

  return (
    <Panel
      title="Audit trail"
      subtitle="Who changed what, and when. Posted history is immutable; dates are Asia/Colombo days."
      action={
        <button className="button secondary small" onClick={exportCsv} disabled={exporting}>
          {exporting ? <LoaderCircle className="spin" size={14} /> : <ArrowDownToLine size={14} />}
          Export CSV
        </button>
      }
    >
      <div className="adm-toolbar">
        <div className="adm-filters">
          <SearchBox value={search} onChange={setSearch} placeholder="Search document, email or detail…" />
          <select aria-label="Actor" value={actor} onChange={(e) => setActor(e.target.value)}>
            <option value="">Anyone</option>
            {facets.data?.actors.map((a) => (
              <option key={a} value={a}>
                {a}
              </option>
            ))}
          </select>
          <select aria-label="Action" value={action} onChange={(e) => setAction(e.target.value)}>
            <option value="">Any action</option>
            {actions.map((a) => (
              <option key={a} value={a}>
                {auditLabel(a)}
              </option>
            ))}
          </select>
          <label className="adm-date">
            From
            <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="adm-date">
            To
            <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
          </label>
          {filtered && (
            <button
              className="text-button"
              onClick={() => {
                setSearch('');
                setActor('');
                setAction('');
                setFrom('');
                setTo('');
              }}
            >
              <X size={13} />
              Clear filters
            </button>
          )}
        </div>
      </div>
      <QueryState pending={query.isPending} error={query.error} retry={() => query.refetch()}>
        <Table
          rows={rows}
          compact
          pageSize={Math.max(rows.length, 1)}
          columns={[
            {
              label: 'Action',
              render: (r) => <span className="audit-action">{auditLabel(r.action)}</span>,
            },
            { label: 'Document', key: 'reference' },
            { label: 'Actor', key: 'actor' },
            {
              label: 'Detail',
              render: (r) => (
                <span className="notes-cell" title={text(r.detail)}>
                  {text(r.detail)}
                </span>
              ),
            },
            { label: 'Date and time', render: (r) => dateTime(r.created_at) },
          ]}
        />
        <LoadMore query={query} count={rows.length} />
      </QueryState>
    </Panel>
  );
}
