import { useId, useLayoutEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { motion, useIsPresent } from 'motion/react';
import { ArrowDown, ArrowUp, ArrowUpDown, ChevronLeft, ChevronRight, Search, X, LoaderCircle, PackageOpen } from 'lucide-react';
import { fmt, text } from './api';
import type { Row } from './api';
import { easeOut, spring, useMotionPreferences } from './motion';

export function Badge({ value }: { value: unknown }) {
  const label = text(value).replaceAll('_', ' ').toLowerCase();
  const tone = /passed|released|completed|closed|delivered|running|approved/.test(label) ? 'green' : /failed|rejected|urgent|cancelled/.test(label) ? 'red' : /hold|quarantine|quality|pending|maintenance|high|partial/.test(label) ? 'amber' : /progress|reserved|confirmed|normal/.test(label) ? 'blue' : 'gray';
  return <span className={`badge ${tone}`}><i />{label}</span>;
}
export function Progress({ value, label = false }: { value: number; label?: boolean }) {
  const { reduced } = useMotionPreferences();
  const progress = Number.isFinite(value) ? Math.min(Math.max(value, 0), 100) : 0;
  return <div className="progress-wrap"><div className="progress-track" role="progressbar" aria-label="Completion" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}>
    <motion.div initial={false} animate={{ width: `${progress}%` }} transition={reduced ? { duration: 0 } : spring} />
  </div>{label && <span>{fmt(progress)}%</span>}</div>;
}
export function Empty({ title = 'Nothing here yet', body = 'New records will appear here as your team gets to work.' }: { title?: string; body?: string }) {
  return <div className="empty"><PackageOpen size={32} strokeWidth={1.4} /><strong>{title}</strong><p>{body}</p></div>;
}
export function Overlay({ title, subtitle, children, onClose, wide = false, drawer = false }: { title: string; subtitle?: string; children: ReactNode; onClose: () => void; wide?: boolean; drawer?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const present = useIsPresent();
  const { reduced } = useMotionPreferences();
  useLayoutEffect(() => {
    const dialog = ref.current;
    const trigger = document.activeElement;
    dialog?.showModal();
    dialog?.querySelector<HTMLElement>('input:not([type="hidden"]), select, textarea')?.focus();
    return () => {
      // Close before React removes the node, then restore keyboard focus after exit.
      dialog?.close();
      queueMicrotask(() => {
        if (!(trigger instanceof HTMLElement) || !trigger.isConnected) return;
        const active = document.activeElement;
        const otherDialogs = Array.from(document.querySelectorAll('dialog[open]')).filter(item => item !== dialog);
        if ((active === document.body || active === dialog || (active && dialog?.contains(active)))
          && otherDialogs.every(item => item.contains(trigger))) trigger.focus({ preventScroll: true });
      });
    };
  }, []);
  return <motion.dialog ref={ref} className={`dialog ${wide ? 'wide' : ''} ${drawer ? 'drawer' : ''}`}
    inert={!present}
    data-motion-state={present ? 'open' : 'exiting'}
    initial={{ opacity: 0, x: !reduced && drawer ? 40 : 0, y: !reduced && !drawer ? 14 : 0, scale: !reduced && !drawer ? 0.98 : 1 }}
    animate={{ opacity: 1, x: 0, y: 0, scale: 1 }}
    exit={{ opacity: 0, x: !reduced && drawer ? 28 : 0, y: !reduced && !drawer ? 8 : 0, scale: !reduced && !drawer ? 0.985 : 1, transition: { duration: reduced ? 0.06 : 0.16, ease: easeOut } }}
    transition={reduced ? { duration: 0.08 } : { ...spring, opacity: { duration: 0.16 } }}
    onCancel={e => { e.preventDefault(); if (present) onClose(); }}
    onClick={e => { if (present && e.target === e.currentTarget) onClose(); }} aria-labelledby={titleId}>
    <div className="dialog-heading"><div><h2 id={titleId}>{title}</h2>{subtitle && <p>{subtitle}</p>}</div><button className="icon-button" aria-label="Close dialog" disabled={!present} onClick={onClose}><X size={20} /></button></div>{children}
  </motion.dialog>;
}
export type Field = { name: string; label: string; type?: string; options?: { value: string; label: string }[]; defaultValue?: string; min?: string; step?: string; hint?: string; optional?: boolean };
export type FormSpec = { title: string; description?: string; fields: Field[]; submit?: string; run: (values: Record<string, string>, key: string) => Promise<unknown> };
export function FormDialog({ spec, onClose, onSuccess }: { spec: FormSpec; onClose: () => void; onSuccess: () => void }) {
  const [pending, setPending] = useState(false); const [error, setError] = useState(''); const requestKey = useRef(crypto.randomUUID());
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); const values = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>;
    setPending(true); setError('');
    try { await spec.run(values, requestKey.current); onSuccess(); onClose(); } catch (err) { setError((err as Error).message); } finally { setPending(false); }
  }
  return <Overlay title={spec.title} subtitle={spec.description} onClose={() => { if (!pending) onClose(); }}><form onSubmit={submit}><div className="form-fields">{spec.fields.map(f => <label className="field" key={f.name}><span>{f.label}{f.optional && <small>Optional</small>}</span>{f.options ? <select name={f.name} required={!f.optional} defaultValue={f.defaultValue || ''}><option value="" disabled={!f.optional}>Select {f.label.toLowerCase()}</option>{f.options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</select> : f.type === 'textarea' ? <textarea name={f.name} rows={3} required={!f.optional} maxLength={2000} defaultValue={f.defaultValue} /> : <input name={f.name} type={f.type || 'text'} required={!f.optional} defaultValue={f.defaultValue} min={f.min} step={f.step || (f.type === 'number' ? '0.000001' : undefined)} maxLength={f.type === 'text' || !f.type ? 160 : undefined} />}{f.hint && <em>{f.hint}</em>}</label>)}{error && <div role="alert" className="form-error">{error}</div>}</div><div className="dialog-footer"><button type="button" className="button secondary" onClick={onClose} disabled={pending}>Cancel</button><button className="button primary" disabled={pending}>{pending && <LoaderCircle className="spin" size={16} />}{spec.submit || 'Save changes'}</button></div></form></Overlay>;
}
export type Column = { label: string; key?: string; render?: (r: Row) => ReactNode; className?: string };
export function Table({ rows, columns, search = false, placeholder = 'Search records…', onRow, compact = false, pageSize = 8 }: { rows: Row[]; columns: Column[]; search?: boolean; placeholder?: string; onRow?: (r: Row) => void; compact?: boolean; pageSize?: number }) {
  const { reduced } = useMotionPreferences();
  const [query, setQuery] = useState(''); const [page, setPage] = useState(0); const [sort, setSort] = useState<{ key: string; desc: boolean }>();
  let filtered = rows.filter(r => Object.values(r).some(v => text(v).toLowerCase().includes(query.toLowerCase())));
  if (sort) filtered = [...filtered].sort((a, b) => (typeof a[sort.key] === 'number' ? Number(a[sort.key]) - Number(b[sort.key]) : text(a[sort.key]).localeCompare(text(b[sort.key]))) * (sort.desc ? -1 : 1));
  const safePage = Math.min(page, Math.max(0, Math.ceil(filtered.length / pageSize) - 1)); const visible = filtered.slice(safePage * pageSize, (safePage + 1) * pageSize);
  return <>{search && <div className="table-toolbar"><div className="search-input"><Search size={16} /><input aria-label={placeholder} placeholder={placeholder} value={query} onChange={e => { setQuery(e.target.value); setPage(0); }} /></div><span className="muted">{fmt(filtered.length)} records</span></div>}<div className={`table-scroll ${compact ? 'compact' : ''}`}><table><thead><tr>{columns.map((c, i) => <th key={i} className={c.className}>{c.key ? <button className="sort-button" onClick={() => { setSort({ key: c.key!, desc: sort && sort.key === c.key ? !sort.desc : false }); }}>{c.label}{sort?.key === c.key ? sort.desc ? <ArrowDown size={12} /> : <ArrowUp size={12} /> : <ArrowUpDown size={12} />}</button> : c.label}</th>)}</tr></thead><tbody>{visible.map((r, i) => <motion.tr key={r.id || i} layout={reduced ? false : 'position'} initial={false} animate={{ opacity: 1 }} transition={{ layout: { duration: 0.2, ease: easeOut } }} className={onRow ? 'clickable-row' : ''} onClick={onRow ? () => onRow(r) : undefined}>{columns.map((c, j) => <td className={c.className} key={j}>{c.render ? c.render(r) : text(r[c.key || ''])}</td>)}</motion.tr>)}</tbody></table>{!visible.length && <Empty title={query ? 'No matching records' : 'No records yet'} body={query ? 'Try a different search.' : undefined} />}</div>{!compact && <div className="table-footer"><span>Showing {filtered.length ? safePage * pageSize + 1 : 0}–{Math.min((safePage + 1) * pageSize, filtered.length)} of {fmt(filtered.length)}</span><div><button className="icon-button" aria-label="Previous page" disabled={safePage === 0} onClick={() => setPage(safePage - 1)}><ChevronLeft size={16} /></button><span>{safePage + 1} / {Math.max(1, Math.ceil(filtered.length / pageSize))}</span><button className="icon-button" aria-label="Next page" disabled={(safePage + 1) * pageSize >= filtered.length} onClick={() => setPage(safePage + 1)}><ChevronRight size={16} /></button></div></div>}</>;
}
