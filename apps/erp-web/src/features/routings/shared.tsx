import { useEffect, useState } from 'react';
import { ShieldAlert, ShieldCheck } from 'lucide-react';
import type { Value } from '../../lib/api';
import { labelState, text } from '../../lib/format';
import { minutesText } from './api';

const OPERATION_TONES: Record<string, string> = {
  PENDING: 'gray',
  READY: 'blue',
  IN_PROGRESS: 'amber',
  PAUSED: 'red',
  DONE: 'green',
  CANCELLED: 'gray',
  DRAFT: 'gray',
  APPROVED: 'green',
};

/** Status chip for operations and routing revisions (the generic `Badge` has no tone for READY/DONE/PAUSED). */
export function StatusChip({ value, label }: { value: Value | undefined; label?: string }) {
  const code = text(value);
  return (
    <span className={`badge ${OPERATION_TONES[code] || 'gray'}`}>
      <i />
      {label ?? labelState(code)}
    </span>
  );
}

/** Skill requirement with the signed-in user's qualification (`skill_check` from the operation reads). */
export function SkillHint({ row }: { row: Record<string, Value> }) {
  if (!row.skill_id) return null;
  const check = text(row.skill_check);
  const ok = check === 'QUALIFIED' || check === 'NOT_REQUIRED';
  const level = Number(row.skill_level || 1);
  const need = `${text(row.skill_code)}${level > 1 ? ` L${level}+` : ''}`;
  const reason =
    check === 'MISSING'
      ? 'you do not hold this skill'
      : check === 'EXPIRED'
        ? `your certification expired ${text(row.skill_certified_until)}`
        : check === 'LEVEL_TOO_LOW'
          ? `you hold level ${text(row.skill_level_held)}`
          : '';
  return (
    <span
      className={`skill-hint ${ok ? 'ok' : 'warn'}`}
      title={`${text(row.skill_name)}${reason ? ` — ${reason}` : ''}`}
    >
      {ok ? <ShieldCheck size={13} /> : <ShieldAlert size={13} />}
      {need}
      {!ok && <span className="skill-reason">· {reason}</span>}
    </span>
  );
}

/** Minutes since `since` (ISO timestamp), refreshed every 30 seconds; null when `since` is empty. */
export function useElapsedMinutes(since: Value | undefined) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!since) return;
    const timer = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(timer);
  }, [since]);
  if (!since) return null;
  return Math.max(0, (now - new Date(text(since)).getTime()) / 60000);
}

/** Planned vs actual minutes ("80 min / 95 min"), actual including a running interval. */
export function PlannedActual({ planned, actual }: { planned: Value | undefined; actual: number }) {
  const over = actual > Number(planned || 0) && Number(planned || 0) > 0;
  return (
    <span className="planned-actual">
      <span>{minutesText(planned)}</span>
      <span className="muted">/</span>
      <strong className={over ? 'over' : ''}>{actual ? minutesText(actual) : '—'}</strong>
    </span>
  );
}
