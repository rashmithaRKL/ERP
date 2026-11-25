import { useEffect, useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { CalendarClock, Route } from 'lucide-react';
import { useWorkspace } from '../app/workspace';
import type { RoutingDetail } from '../features/routings/api';
import { CalendarsTab } from '../features/routings/CalendarsTab';
import { onNewRouting } from '../features/routings/events';
import { RoutingCompare } from '../features/routings/RoutingCompare';
import { RoutingDrawer } from '../features/routings/RoutingDrawer';
import { RoutingEditor } from '../features/routings/RoutingEditor';
import { RoutingsTab } from '../features/routings/RoutingsTab';
import { SkillsTab } from '../features/routings/SkillsTab';
import { Tabs } from '../motion/motion';

type Editor = { draft?: RoutingDetail } | undefined;

/**
 * Route `/routings` (V10, "Routings & shifts"): versioned routings (list, drawer, draft editor, independent approval,
 * copy, compare, current revision), shift calendars (week of available hours per work center, calendars, holidays and
 * overtime, shift patterns) and skills (catalogue and user skill matrix). Components live in `features/routings`.
 */
export default function RoutingsPage() {
  const { can } = useWorkspace();
  const [tab, setTab] = useState('routings');
  const [open, setOpen] = useState<string>();
  const [editor, setEditor] = useState<Editor>();
  const [compare, setCompare] = useState<{ from: string; to: string }>();
  useEffect(
    () =>
      onNewRouting(() => {
        setTab('routings');
        setEditor({});
      }),
    [],
  );
  return (
    <>
      <div className="info-banner routing-banner">
        <Route size={20} />
        <div>
          <strong>How every product is made, and when work centers can work</strong>
          <p>
            New manufacturing orders snapshot the product&apos;s current approved routing into operations that
            operators run on the Shop floor. Shift calendars give each work center its available hours
            {can('PLANNER') ? '; another planner approves each routing you author.' : '.'}
          </p>
        </div>
        <CalendarClock size={20} className="banner-trailing" />
      </div>
      <div className="panel routing-tabbar">
        <Tabs
          label="Routings and shifts sections"
          value={tab}
          onChange={setTab}
          items={[
            { value: 'routings', label: 'Routings' },
            { value: 'calendars', label: 'Shift calendars' },
            { value: 'skills', label: 'Skills' },
          ]}
        />
      </div>
      {tab === 'routings' && <RoutingsTab onOpen={setOpen} />}
      {tab === 'calendars' && <CalendarsTab />}
      {tab === 'skills' && <SkillsTab />}
      <AnimatePresence>
        {open && !editor && !compare && (
          <RoutingDrawer
            key={open}
            routingId={open}
            onClose={() => setOpen(undefined)}
            onOpen={setOpen}
            onEdit={(draft) => setEditor({ draft })}
            onCompare={(from, to) => setCompare({ from, to })}
          />
        )}
        {editor && (
          <RoutingEditor
            draft={editor.draft}
            onClose={() => setEditor(undefined)}
            onSaved={(id) => setOpen(id)}
          />
        )}
        {compare && (
          <RoutingCompare from={compare.from} to={compare.to} onClose={() => setCompare(undefined)} />
        )}
      </AnimatePresence>
    </>
  );
}
