import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import { request } from '../lib/api';
import type { Row, State, User } from '../lib/api';
import { num } from '../lib/format';
import { isActive } from '../lib/rows';
import type { Field, FormSpec } from '../components';

/** Cross-page figures derived from the `/api/state` payload (shell badges, overview, planning, quality…). */
export type WorkspaceSummary = {
  /** Manufacturing orders that are not closed, cancelled or completed. */
  active: Row[];
  /** MRP lines with a net shortage. */
  shortages: Row[];
  /** Inspections awaiting a decision. */
  pendingQc: Row[];
  /** Good output in the reporting period (pieces). */
  output: number;
  /** Scrap in the reporting period (pieces). */
  scrap: number;
  /** Good output / (good + scrap), in percent. */
  yieldPct: number;
};

export function summarize(data: State | undefined): WorkspaceSummary {
  const active = data?.orders.filter(isActive) || [];
  const shortages = data?.mrp.filter((m) => num(m.shortage) > 0) || [];
  const pendingQc = data?.inspections.filter((i) => i.status === 'PENDING') || [];
  const output = num(data?.metrics.output);
  const scrap = num(data?.metrics.scrap);
  const yieldPct = output + scrap ? (output / (output + scrap)) * 100 : 0;
  return { active, shortages, pendingQc, output, scrap, yieldPct };
}

export type Workspace = {
  /** The signed-in user (pages only render after login). */
  user: User;
  /** The shared `/api/state?days=` payload. Pages are only rendered once it has loaded; see `useStateData()`. */
  data: State | undefined;
  stateQuery: UseQueryResult<State, Error>;
  summary: WorkspaceSummary;
  /** Reporting period in days (header period selector). Part of the `['state', days, userId]` query key. */
  days: number;
  setDays: (days: number) => void;
  /** True when the user's role is in `roles` (no implicit ADMIN). Used for navigation visibility. */
  allowed: (roles: string[]) => boolean;
  /** True for ADMIN or any of `roles`. `can()` means "admin only". */
  can: (...roles: string[]) => boolean;
  /** A `mutate()` call is in flight. Disable action buttons while true. */
  busy: boolean;
  /**
   * POST `body` to `url`, then refresh workspace data and show a toast. Errors are shown as a toast
   * (and inside the order drawer). Ignored while another `mutate()` is in flight.
   */
  mutate: (url: string, body?: unknown) => Promise<void>;
  /** Invalidate the `['state']` and `['order']` queries. */
  refresh: () => Promise<void>;
  /** Show a toast. Messages containing "success" or "downloaded" use the success style. */
  notify: (message: string) => void;
  /** Open the shared transaction form dialog. On success it refreshes data and shows a toast. */
  openForm: (spec: FormSpec) => void;
  /** `openForm` shortcut that POSTs the form values to `url` with the dialog's idempotency key. */
  postForm: (title: string, url: string, fields: Field[], description?: string, submit?: string) => void;
  /** Open the manufacturing order drawer. */
  openOrder: (id: string) => void;
  /** Open the BOM revision editor dialog. */
  openBomEditor: () => void;
  /** Workspace-level text filter for `filterRows()`. Reset whenever the route changes. */
  search: string;
  setSearch: (value: string) => void;
  /** Favorite page paths (persisted in localStorage `dex-favorites`). */
  favorites: string[];
  toggleFavorite: (path: string) => void;

  // Overlay state rendered by the shell (app/App.tsx). Pages should use the helpers above instead.
  toast: string;
  setToast: (message: string) => void;
  form: FormSpec | undefined;
  closeForm: () => void;
  orderId: string | undefined;
  closeOrder: () => void;
  /** Last `mutate()` error, shown in the order drawer. Cleared when the drawer target changes. */
  actionError: string;
  bomEditor: boolean;
  closeBomEditor: () => void;
};

/**
 * Owns the workspace state for the shell. Called once by `App` (before the login gate, so the state
 * survives session refetches); everything else reads it through `useWorkspace()`.
 */
export function useWorkspaceState(user: User | null | undefined): Omit<Workspace, 'user'> {
  const qc = useQueryClient();
  const path = useLocation().pathname;
  const [actionError, setActionError] = useState('');
  const [bomEditor, setBomEditor] = useState(false);
  const [days, setDays] = useState(14);
  const [form, setForm] = useState<FormSpec>();
  const [detail, setDetail] = useState<string>();
  const [toast, setToast] = useState('');
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState('');
  const [favorites, setFavorites] = useState<string[]>(() =>
    JSON.parse(localStorage.getItem('dex-favorites') || '[]'),
  );
  const stateQuery = useQuery({
    queryKey: ['state', days, user?.id],
    queryFn: () => request<State>(`/state?days=${days}`),
    enabled: !!user,
  });
  const data = stateQuery.data;
  useEffect(() => {
    setActionError('');
  }, [detail]);
  useEffect(() => {
    setSearch('');
  }, [path]);
  useEffect(() => {
    if (!toast) return;
    const timeout = setTimeout(() => setToast(''), 6500);
    return () => clearTimeout(timeout);
  }, [toast]);
  const allowed = (roles: string[]) => !!user && roles.includes(user.role);
  const can = (...roles: string[]) => allowed(['ADMIN', ...roles]);
  const notify = (message: string) => setToast(message);
  async function refresh() {
    await Promise.all([
      qc.invalidateQueries({ queryKey: ['state'] }),
      qc.invalidateQueries({ queryKey: ['order'] }),
    ]);
  }
  async function mutate(url: string, body: unknown = {}) {
    if (busy) return;
    setBusy(true);
    setActionError('');
    try {
      await request(url, body);
      await refresh();
      notify('Transaction posted successfully.');
    } catch (e) {
      setActionError((e as Error).message);
      notify((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  function openForm(spec: FormSpec) {
    setForm(spec);
  }
  const postForm = (title: string, url: string, fields: Field[], description?: string, submit?: string) =>
    openForm({ title, description, fields, submit, run: (v, k) => request(url, v, k) });
  function toggleFavorite(page: string) {
    const next = favorites.includes(page) ? favorites.filter((x) => x !== page) : [...favorites, page];
    setFavorites(next);
    localStorage.setItem('dex-favorites', JSON.stringify(next));
  }
  return {
    data,
    stateQuery,
    summary: summarize(data),
    days,
    setDays,
    allowed,
    can,
    busy,
    mutate,
    refresh,
    notify,
    openForm,
    postForm,
    openOrder: (id) => setDetail(id),
    openBomEditor: () => setBomEditor(true),
    search,
    setSearch,
    favorites,
    toggleFavorite,
    toast,
    setToast,
    form,
    closeForm: () => setForm(undefined),
    orderId: detail,
    closeOrder: () => setDetail(undefined),
    actionError,
    bomEditor,
    closeBomEditor: () => setBomEditor(false),
  };
}

const WorkspaceContext = createContext<Workspace | null>(null);

export function WorkspaceProvider({ value, children }: { value: Workspace; children: ReactNode }) {
  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

/** Shared session, data and action helpers for pages and features. */
export function useWorkspace(): Workspace {
  const workspace = useContext(WorkspaceContext);
  if (!workspace) throw new Error('useWorkspace() must be used inside the signed-in workspace shell.');
  return workspace;
}

/** The loaded `/api/state` payload. Route pages are only rendered once it is available. */
export function useStateData(): State {
  const { data } = useWorkspace();
  if (!data) throw new Error('useStateData() was called before workspace data loaded.');
  return data;
}
