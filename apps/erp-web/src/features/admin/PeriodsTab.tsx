import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AnimatePresence } from 'motion/react';
import {
  CalendarCheck,
  CircleCheck,
  Info,
  LoaderCircle,
  LockKeyhole,
  RotateCcw,
  TriangleAlert,
} from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Panel, Table } from '../../components';
import type { Row } from '../../lib/api';
import { request } from '../../lib/api';
import { fmt, money, text } from '../../lib/format';
import { ADMIN, dateTime, longDate, previousMonthEnd, useInvalidateAdmin } from './api';
import { QueryState, Tone } from './shared';

type Periods = { items: Row[]; today: string; latestClosed: string | null; postingBlocked: boolean };

/** Accounting periods: close in order, reopen the latest closed period with a reason. ADMIN and (V24) FINANCE. */
export function PeriodsTab() {
  const { notify, refresh } = useWorkspace();
  const invalidate = useInvalidateAdmin();
  const periods = useQuery({
    queryKey: [ADMIN, 'periods'],
    queryFn: () => request<Periods>('/admin/periods'),
  });
  const [endDate, setEndDate] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [reopen, setReopen] = useState(false);
  const closeKey = useRef(crypto.randomUUID());
  const data = periods.data;
  const suggested = data
    ? (() => {
        const monthEnd = previousMonthEnd(data.today);
        return !data.latestClosed || monthEnd > data.latestClosed ? monthEnd : data.today;
      })()
    : '';
  const value = endDate || suggested;

  async function close(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await request('/admin/periods/close', { endDate: value }, closeKey.current);
      closeKey.current = crypto.randomUUID();
      setEndDate('');
      notify(`Period ending ${longDate(value)} closed successfully.`);
      await Promise.all([invalidate(), refresh()]);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="admin-section">
      <QueryState pending={periods.isPending} error={periods.error} retry={() => periods.refetch()}>
        {data && (
          <>
            <Panel
              title="Close a period"
              subtitle="System postings are dated today; manual journals and reversals carry their own posting date. Nothing can be posted into a closed period, and a closed period that covers today stops every posting until it is reopened."
            >
              <form className="adm-period-form" onSubmit={close}>
                {data.postingBlocked ? (
                  <div className="adm-note danger">
                    <LockKeyhole size={16} />
                    <span>
                      <strong>Postings are blocked.</strong> The period ending {longDate(data.latestClosed)}{' '}
                      covers today, so receipts, issues, output, dispatches and adjustments are refused.
                      Reopen it below to post again.
                    </span>
                  </div>
                ) : (
                  <div className="adm-note success">
                    <CircleCheck size={16} />
                    <span>
                      <strong>Posting is open.</strong>{' '}
                      {data.latestClosed
                        ? `Closed through ${longDate(data.latestClosed)}.`
                        : 'No period has been closed yet.'}
                    </span>
                  </div>
                )}
                <label className="field">
                  <span>Period end date</span>
                  <input
                    type="date"
                    required
                    max={data.today}
                    min={data.latestClosed ?? undefined}
                    value={value}
                    onChange={(e) => setEndDate(e.target.value)}
                  />
                </label>
                <button className="button primary" disabled={busy || !value}>
                  {busy ? <LoaderCircle className="spin" size={15} /> : <CalendarCheck size={15} />}
                  Close period
                </button>
                {value === data.today && (
                  <div className="adm-note warning">
                    <TriangleAlert size={16} />
                    <span>
                      This period ends today: once it is closed, nothing can be posted for the rest of the day
                      unless you reopen it.
                    </span>
                  </div>
                )}
                {error && (
                  <div className="form-error" role="alert" style={{ flexBasis: '100%' }}>
                    {error}
                  </div>
                )}
              </form>
            </Panel>
            <Panel
              title="Periods"
              subtitle="Each period starts the day after the previous one ends. Only the latest closed period can be reopened."
            >
              <Table
                rows={data.items}
                compact
                pageSize={Math.max(data.items.length, 1)}
                columns={[
                  {
                    label: 'Period',
                    render: (r) => (
                      <div className="cell-stack">
                        <strong>
                          {r.start_date ? `${longDate(r.start_date)} – ` : 'Up to '}
                          {longDate(r.end_date)}
                        </strong>
                        <span>
                          {fmt(r.journal_entries)} journal entries · {money(r.journal_total)}
                        </span>
                      </div>
                    ),
                  },
                  {
                    label: 'Status',
                    render: (r) =>
                      r.closed ? <Tone tone="gray">Closed</Tone> : <Tone tone="amber">Reopened</Tone>,
                  },
                  {
                    label: 'Closed',
                    render: (r) =>
                      r.closed_at ? (
                        <div className="cell-stack">
                          <strong>{text(r.closed_by) || '—'}</strong>
                          <span>{dateTime(r.closed_at)}</span>
                        </div>
                      ) : (
                        '—'
                      ),
                  },
                  {
                    label: 'Reopened',
                    render: (r) =>
                      r.reopened_at ? (
                        <div className="cell-stack">
                          <strong title={text(r.reopen_reason)}>{text(r.reopen_reason)}</strong>
                          <span>
                            {text(r.reopened_by)} · {dateTime(r.reopened_at)}
                          </span>
                        </div>
                      ) : (
                        '—'
                      ),
                  },
                  {
                    label: '',
                    render: (r) =>
                      r.closed && r.end_date === data.latestClosed ? (
                        <button className="button secondary small" onClick={() => setReopen(true)}>
                          <RotateCcw size={13} />
                          Reopen
                        </button>
                      ) : null,
                  },
                ]}
              />
              {!data.items.length && (
                <div className="adm-note" style={{ margin: '0 20px 20px' }}>
                  <Info size={16} />
                  <span>Close your first period above, for example the last day of the previous month.</span>
                </div>
              )}
            </Panel>
          </>
        )}
      </QueryState>
      <AnimatePresence>
        {reopen && data?.latestClosed && (
          <ReopenDialog
            key="reopen"
            endDate={data.latestClosed}
            onClose={() => setReopen(false)}
            onDone={async () => {
              setReopen(false);
              notify(`Period ending ${longDate(data.latestClosed)} reopened successfully.`);
              await Promise.all([invalidate(), refresh()]);
            }}
          />
        )}
      </AnimatePresence>
    </div>
  );
}

function ReopenDialog({
  endDate,
  onClose,
  onDone,
}: {
  endDate: string;
  onClose: () => void;
  onDone: () => void;
}) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await request('/admin/periods/reopen', { reason: reason.trim() }, key.current);
      onDone();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Overlay
      title={`Reopen the period ending ${longDate(endDate)}`}
      subtitle="The reason is kept in the audit trail. Close the period again once the correction is posted."
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <form onSubmit={submit}>
        <div className="form-fields">
          <label className="field">
            <span>
              Reason<small>10–400 characters</small>
            </span>
            <textarea
              name="reason"
              rows={3}
              required
              minLength={10}
              maxLength={400}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          {error && (
            <div role="alert" className="form-error">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button className="button primary" disabled={busy || reason.trim().length < 10}>
            {busy && <LoaderCircle className="spin" size={16} />}
            Reopen period
          </button>
        </div>
      </form>
    </Overlay>
  );
}
