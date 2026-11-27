import { Suspense, useEffect, useState } from 'react';
import type { SyntheticEvent } from 'react';
import { NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { AnimatePresence, LayoutGroup, motion } from 'motion/react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowDownToLine,
  ArrowUpRight,
  CalendarDays,
  ChevronDown,
  ChevronRight,
  CircleCheck,
  CircleHelp,
  Factory,
  LoaderCircle,
  MapPin,
  Menu,
  Moon,
  Plus,
  RefreshCw,
  Search,
  Settings2,
  ShieldCheck,
  Sparkles,
  Star,
  Sun,
  TriangleAlert,
  X,
} from 'lucide-react';
import { FormDialog } from '../components';
import { ChangePasswordDialog } from '../features/admin/ChangePasswordDialog';
import { ForcePasswordChange } from '../features/admin/ForcePasswordChange';
import { MfaChallenge, MfaSetupRequired, TwoStepDialog } from '../features/admin/TwoStep';
import BomDialog from '../features/boms/BomDialog';
import { OrderDrawer } from '../features/manufacturing/OrderDrawer';
import { request, session, SESSION_CHANGED, token } from '../lib/api';
import { csv, text } from '../lib/format';
import { PageTransition, spring, useMotionPreferences } from '../motion/motion';
import NotFoundPage from '../pages/NotFoundPage';
import { AttentionCenter, NotificationButton } from './AttentionCenter';
import { CommandPalette } from './CommandPalette';
import { HelpDialog } from './HelpDialog';
import { preloadWhenIdle } from './lazyPage';
import { Login } from './Login';
import { PageErrorBoundary, PageSkeleton } from './PageLoader';
import { ProfileDialog } from './ProfileDialog';
import { findRoute, navGroups, navItems, preloadRoute, routes } from './routes';
import type { PageRoute } from './routes';
import { useWorkspaceState, WorkspaceProvider } from './workspace';

/** Delegated hover/focus handler: starts loading the page behind a navigation link before it is clicked. */
const preloadLink = (event: SyntheticEvent) => {
  const link = event.target instanceof Element ? event.target.closest('a[href]') : null;
  if (link instanceof HTMLAnchorElement) preloadRoute(link.pathname);
};

/**
 * App shell: session gate, sidebar, header, page heading/context bar, routed page, toast and the shared
 * dialogs (forms, BOM editor, order drawer, command palette, attention center, profile, help).
 * Page content lives in `pages/` and is registered in `./routes.tsx`.
 */
export default function App() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const { reduced } = useMotionPreferences();
  const [narrow, setNarrow] = useState(() => window.matchMedia('(max-width:800px)').matches);
  const [commandOpen, setCommandOpen] = useState(false);
  const [commandSearch, setCommandSearch] = useState('');
  const [notifications, setNotifications] = useState(false);
  const [profile, setProfile] = useState(false);
  const [password, setPassword] = useState(false);
  const [twoStep, setTwoStep] = useState(false);
  const [help, setHelp] = useState(false);
  const [mobile, setMobile] = useState(false);
  const [dark, setDark] = useState(() => localStorage.getItem('dex-theme') === 'dark');
  const userQuery = useQuery({ queryKey: ['user'], queryFn: session, retry: false });
  const user = userQuery.data;
  const workspace = useWorkspaceState(user);
  const { data, stateQuery, summary, favorites, notify, refresh } = workspace;
  const path = location.pathname;
  const current = findRoute(path);
  const title = current?.label ?? 'Overview';
  const ready = !!user && !!data;
  useEffect(() => {
    // The first page's chunk downloads while the session and /api/state requests are in flight.
    preloadRoute(window.location.pathname);
  }, []);
  useEffect(() => {
    // Then the other pages this role can open, one at a time while the browser is idle.
    if (!ready || !user) return;
    return preloadWhenIdle(
      routes.filter((route) => !route.roles || route.roles.includes(user.role)).map((route) => route.page),
    );
  }, [ready, user]);
  useEffect(() => {
    const media = window.matchMedia('(max-width:800px)');
    const update = () => {
      setNarrow(media.matches);
      if (!media.matches) setMobile(false);
    };
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    localStorage.setItem('dex-theme', dark ? 'dark' : 'light');
  }, [dark]);
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setCommandOpen((x) => !x);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);
  useEffect(() => {
    setMobile(false);
  }, [path]);
  useEffect(() => {
    // A revoked session or a required password change: re-read /auth/me (login, forced change or demo re-entry).
    const recheck = () => void qc.invalidateQueries({ queryKey: ['user'] });
    window.addEventListener(SESSION_CHANGED, recheck);
    return () => window.removeEventListener(SESSION_CHANGED, recheck);
  }, [qc]);
  async function changeRole(role: string) {
    try {
      await request(`/auth/demo?role=${role}`, {});
      await token();
      await qc.invalidateQueries({ queryKey: ['user'] });
      setProfile(false);
      navigate('/');
    } catch (e) {
      notify((e as Error).message);
    }
  }
  if (userQuery.isPending)
    return (
      <div className="boot">
        <div className="brand">
          <span>Dex</span>Lanka
        </div>
        <LoaderCircle className="spin" />
        <p>Opening your workspace…</p>
      </div>
    );
  if (userQuery.isError)
    return (
      <div className="boot">
        <TriangleAlert size={32} />
        <h2>The workspace could not connect</h2>
        <p>{(userQuery.error as Error).message}</p>
        <p>
          Start the backend with <code>npm run api</code>, then retry.
        </p>
        <button className="button primary" onClick={() => userQuery.refetch()}>
          Try again
        </button>
      </div>
    );
  if (!user) return <Login onSuccess={() => qc.invalidateQueries({ queryKey: ['user'] })} />;
  if (user.mfaPending)
    return <MfaChallenge user={user} onDone={() => qc.invalidateQueries({ queryKey: ['user'] })} />;
  if (user.mustChangePassword)
    return <ForcePasswordChange user={user} onDone={() => qc.invalidateQueries({ queryKey: ['user'] })} />;
  if (user.mfaSetupRequired)
    return <MfaSetupRequired user={user} onDone={() => qc.invalidateQueries({ queryKey: ['user'] })} />;
  const ws = { ...workspace, user };
  const { active, shortages } = summary;
  // The heading's Export button: the route's rows (derived or fetched), manufacturing orders by default, none for null.
  const exportRows: PageRoute['exportRows'] =
    current?.exportRows === undefined ? (state) => state.orders : current.exportRows;
  async function exportPage() {
    if (!data || !exportRows) return;
    try {
      const rows = await exportRows(data);
      if (!rows.length) return notify('There is nothing to export on this page yet.');
      csv(title.replaceAll(' ', '-').replace(/[()&]/g, '').toLowerCase(), rows);
      notify('CSV export downloaded.');
    } catch (e) {
      notify((e as Error).message);
    }
  }
  const action = current?.action;

  return (
    <WorkspaceProvider value={ws}>
      <div className="app-shell">
        <a className="skip-link" href="#main-content">
          Skip to content
        </a>
        <AnimatePresence>
          {mobile && (
            <motion.button
              key="navigation-scrim"
              className="mobile-scrim"
              aria-label="Close navigation"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: reduced ? 0 : 0.18 }}
              onClick={() => setMobile(false)}
            />
          )}
        </AnimatePresence>
        <LayoutGroup id="workspace-navigation">
          <aside
            id="primary-navigation"
            inert={narrow && !mobile}
            className={`sidebar ${mobile ? 'mobile-open' : ''}`}
            onPointerOver={preloadLink}
            onFocus={preloadLink}
          >
            <NavLink to="/" className="brand-wrap">
              <img src="/favicon.svg" alt="" />
              <div>
                <div className="brand">
                  <span>Dex</span>Lanka<span className="pro-label">PRO</span>
                </div>
                <div className="brand-caption">MANUFACTURING, CONNECTED.</div>
              </div>
            </NavLink>
            <button className="workspace-switch" onClick={() => navigate('/settings')}>
              <span className="workspace-logo">
                <Factory size={19} />
              </span>
              <span>
                <strong>{text(data?.company.name) || text(user.tenant.name)}</strong>
                <small>Manufacturing workspace</small>
              </span>
              <ChevronDown size={14} />
            </button>
            <motion.nav layoutScroll aria-label="Main navigation">
              {favorites.length > 0 && (
                <div className="nav-group favorites">
                  <div className="nav-group-label">FAVORITES</div>
                  {navItems
                    .filter((n) => favorites.includes(n.path) && (!n.roles || ws.allowed(n.roles)))
                    .map((n) => (
                      <NavLink key={n.path} to={n.path} end className="nav-item">
                        <Star size={16} />
                        <span className="nav-label">{n.label}</span>
                      </NavLink>
                    ))}
                </div>
              )}
              {navGroups.map((group) => (
                <div className="nav-group" key={group}>
                  <div className="nav-group-label">{group}</div>
                  {navItems
                    .filter((n) => n.group === group && (!n.roles || ws.allowed(n.roles)))
                    .map((n) => (
                      <NavLink
                        key={n.path}
                        to={n.path}
                        end
                        className={({ isActive }) => `nav-item ${isActive ? 'selected' : ''}`}
                      >
                        {({ isActive }) => (
                          <>
                            {isActive && (
                              <motion.span
                                className="nav-selection"
                                layoutId="active-navigation"
                                transition={spring}
                              />
                            )}
                            <n.icon size={17} strokeWidth={1.65} />
                            <span className="nav-label">{n.label}</span>
                            {n.path === '/manufacturing' && !!active.length && (
                              <span className="nav-count">{active.length}</span>
                            )}
                            {n.path === '/planning' && !!shortages.length && (
                              <span className="nav-alert" role="img" aria-label="Material shortages" />
                            )}
                          </>
                        )}
                      </NavLink>
                    ))}
                </div>
              ))}
            </motion.nav>
            <div className="sidebar-bottom">
              <div className="environment-card">
                <span className="environment-icon">
                  <ShieldCheck size={17} />
                </span>
                <div>
                  <strong>{user.tenant.demo ? 'Demo workspace' : 'Factory workspace'}</strong>
                  <small>
                    {user.tenant.demo ? 'Explore with sample data' : 'Your operational workspace'}
                  </small>
                </div>
                <ArrowUpRight size={15} />
              </div>
              <NavLink to="/settings" className={`nav-item ${path === '/settings' ? 'selected' : ''}`}>
                <>
                  {path === '/settings' && (
                    <motion.span className="nav-selection" layoutId="active-navigation" transition={spring} />
                  )}
                  <Settings2 size={17} />
                  <span>Administration</span>
                </>
              </NavLink>
              <button className="nav-item" onClick={() => setHelp(true)}>
                <CircleHelp size={17} />
                Help & resources
                <ArrowUpRight size={13} />
              </button>
              <div className="sidebar-foot">
                <span className="server-dot" />
                {stateQuery.isError ? 'Connection needs attention' : 'Local deployment'}
                <span>v0.1.0</span>
              </div>
            </div>
          </aside>
        </LayoutGroup>
        <div className="main-shell">
          <header className="topbar">
            <div className="breadcrumbs">
              <button
                className="icon-button mobile-menu"
                aria-label="Open navigation"
                aria-controls="primary-navigation"
                aria-expanded={mobile}
                onClick={() => setMobile(true)}
              >
                <Menu size={20} />
              </button>
              <Factory size={16} />
              <span>Workspace</span>
              <ChevronRight size={13} />
              <strong>{title}</strong>
            </div>
            <div className="topbar-right">
              <button
                className="global-search"
                aria-label="Search workspace"
                onClick={() => {
                  setCommandSearch('');
                  setCommandOpen(true);
                }}
              >
                <Search size={15} />
                <span>Search anything…</span>
                <kbd>⌘ K</kbd>
              </button>
              <span className="header-divider" />
              <button
                className="icon-button theme-button"
                aria-label={dark ? 'Use light theme' : 'Use dark theme'}
                onClick={() => setDark(!dark)}
              >
                {dark ? <Sun size={18} /> : <Moon size={18} />}
              </button>
              <NotificationButton onOpen={() => setNotifications(true)} />
              <button className="avatar" aria-label="Open profile menu" onClick={() => setProfile(true)}>
                {user.name
                  .split(' ')
                  .map((x) => x[0])
                  .slice(0, 2)
                  .join('')}
              </button>
            </div>
          </header>
          <main id="main-content" tabIndex={-1}>
            <AnimatePresence mode="wait" initial={false}>
              <PageTransition key={path}>
                <div className="page-heading">
                  <div>
                    <div className="eyebrow">
                      <span className="eyebrow-line" />
                      {current?.eyebrow ?? text(data?.company.name || user.tenant.name).toUpperCase()}
                    </div>
                    <h1>
                      {current?.heading ?? title}
                      <button
                        className={`favorite-button ${favorites.includes(path) ? 'is-favorite' : ''}`}
                        aria-label="Toggle page favorite"
                        onClick={() => ws.toggleFavorite(path)}
                      >
                        <Star size={17} />
                      </button>
                    </h1>
                    <p>{current?.description || 'Manage your factory operations in one place.'}</p>
                  </div>
                  <div className="page-actions">
                    {exportRows && (
                      <button className="button secondary export-button" onClick={() => void exportPage()}>
                        <ArrowDownToLine size={15} />
                        Export
                      </button>
                    )}
                    {action && ws.can(...action.roles) && (
                      <button className="button primary" onClick={() => action.run(ws)}>
                        <Plus size={16} />
                        {action.label}
                      </button>
                    )}
                  </div>
                </div>
                {user.tenant.demo && (
                  <div className="demo-ribbon">
                    <span>
                      <Sparkles size={13} />
                      <strong>Demo workspace</strong>
                      <span className="ribbon-detail">You’re viewing sample manufacturing data.</span>
                    </span>
                    <button onClick={() => setHelp(true)}>
                      About this workspace
                      <ArrowUpRight size={12} />
                    </button>
                  </div>
                )}
                <div className="context-bar">
                  <div className="context-left">
                    <div className="plant-selector">
                      <Factory size={15} />
                      <span>{text(data?.factories[0]?.name) || 'Factory'}</span>
                      <span className="plant-tag">LK</span>
                    </div>
                    <span className="context-separator" />
                    <span className="context-location">
                      <MapPin size={13} />
                      {text(data?.factories[0]?.location)}
                    </span>
                  </div>
                  <div className="context-right">
                    <span className="connection-state">
                      <i className={stateQuery.isError ? 'red-dot' : ''} />
                      {stateQuery.isError ? 'Connection interrupted' : 'Local server connected'}
                    </span>
                    <button
                      className="icon-button"
                      title="Refresh data"
                      aria-label="Refresh data"
                      onClick={() => stateQuery.refetch()}
                    >
                      <RefreshCw size={14} className={stateQuery.isFetching ? 'spin' : ''} />
                    </button>
                    <div className="period-selector">
                      <CalendarDays size={14} />
                      <select
                        aria-label="Reporting period"
                        value={ws.days}
                        onChange={(e) => ws.setDays(Number(e.target.value))}
                      >
                        <option value={7}>Last 7 days</option>
                        <option value={14}>Last 14 days</option>
                        <option value={30}>Last 30 days</option>
                        <option value={90}>Last 90 days</option>
                      </select>
                      <ChevronDown size={12} />
                    </div>
                  </div>
                </div>
                {stateQuery.isPending && <PageSkeleton />}
                {stateQuery.isError && (
                  <div className="error-panel">
                    <TriangleAlert />
                    <div>
                      <strong>We couldn’t load the workspace</strong>
                      <p>{(stateQuery.error as Error).message}</p>
                    </div>
                    <button className="button secondary" onClick={() => stateQuery.refetch()}>
                      Retry
                    </button>
                  </div>
                )}
                {data && (
                  // Pages are code-split: the boundary shows the skeleton while a page chunk downloads and a
                  // recoverable error if it cannot. `location` is passed explicitly so the outgoing page keeps
                  // rendering its own route while AnimatePresence plays the exit transition.
                  <PageErrorBoundary>
                    <Suspense fallback={<PageSkeleton />}>
                      <Routes location={location}>
                        {routes.map((route) => (
                          <Route key={route.path} path={route.path} caseSensitive element={<route.page />} />
                        ))}
                        <Route path="*" element={<NotFoundPage />} />
                      </Routes>
                    </Suspense>
                  </PageErrorBoundary>
                )}
              </PageTransition>
            </AnimatePresence>
            <footer className="workspace-footer">
              <span>
                <img src="/favicon.svg" alt="" />
                DexLanka Manufacturing Pro<span className="footer-dot">·</span>Built for the factory floor.
              </span>
              <span>
                {user.tenant.demo ? 'Sample data · Asia/Colombo' : 'Asia/Colombo'}
                <span className="footer-dot">·</span>
                <button onClick={() => setHelp(true)}>
                  Help & documentation
                  <ArrowUpRight size={12} />
                </button>
              </span>
            </footer>
          </main>
        </div>
        <AnimatePresence>
          {ws.toast && (
            <motion.div
              key={ws.toast}
              initial={{ opacity: 0, y: reduced ? 0 : 16, scale: reduced ? 1 : 0.98 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: reduced ? 0 : 8, transition: { duration: 0.14 } }}
              transition={reduced ? { duration: 0.08 } : spring}
              className={`toast ${/success|downloaded/.test(ws.toast) ? 'success' : ''}`}
              role="status"
            >
              <span>
                {/success|downloaded/.test(ws.toast) ? (
                  <CircleCheck size={19} />
                ) : (
                  <TriangleAlert size={19} />
                )}
                {ws.toast}
              </span>
              <button
                className="icon-button"
                aria-label="Dismiss notification"
                onClick={() => ws.setToast('')}
              >
                <X size={16} />
              </button>
            </motion.div>
          )}
        </AnimatePresence>
        <AnimatePresence>
          {ws.bomEditor && data && (
            <BomDialog
              key="bom-editor"
              data={data}
              onClose={() => ws.closeBomEditor()}
              onSuccess={() => {
                refresh();
                notify('Transaction posted successfully.');
              }}
            />
          )}
          {ws.form && (
            <FormDialog
              key="transaction-form"
              spec={ws.form}
              onClose={() => ws.closeForm()}
              onSuccess={() => {
                refresh();
                notify('Transaction posted successfully.');
              }}
            />
          )}
          {ws.orderId && (
            <OrderDrawer
              key={`order-${ws.orderId}`}
              orderId={ws.orderId}
              error={ws.actionError}
              onClose={() => ws.closeOrder()}
            />
          )}
          {commandOpen && (
            <CommandPalette
              key="command"
              search={commandSearch}
              onSearch={setCommandSearch}
              onClose={() => setCommandOpen(false)}
            />
          )}
          {notifications && <AttentionCenter key="notifications" onClose={() => setNotifications(false)} />}
          {profile && (
            <ProfileDialog
              key="profile"
              onChangeRole={changeRole}
              onChangePassword={() => {
                setProfile(false);
                setPassword(true);
              }}
              onTwoStep={() => {
                setProfile(false);
                setTwoStep(true);
              }}
              onClose={() => setProfile(false)}
            />
          )}
          {password && (
            <ChangePasswordDialog
              key="password"
              email={user.email}
              onClose={() => setPassword(false)}
              onChanged={() => {
                setPassword(false);
                notify('Password changed successfully. Your other sessions were signed out.');
              }}
            />
          )}
          {twoStep && <TwoStepDialog key="two-step" onClose={() => setTwoStep(false)} />}
          {help && <HelpDialog key="help" onClose={() => setHelp(false)} />}
        </AnimatePresence>
      </div>
    </WorkspaceProvider>
  );
}
