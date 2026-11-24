import { createContext, useContext, useEffect, useId, useMemo, useState } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import { LayoutGroup, MotionConfig, motion, useIsPresent, useReducedMotion } from 'motion/react';
import { Accessibility, Monitor, Play, Sparkles } from 'lucide-react';

export const easeOut = [0.22, 1, 0.36, 1] as const;
export const spring = { type: 'spring', stiffness: 420, damping: 36, mass: 0.85 } as const;
export const reveal = {
  hidden: { opacity: 0, y: 10 },
  visible: { opacity: 1, y: 0, transition: { duration: 0.3, ease: easeOut } },
};
export const stagger = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.045, delayChildren: 0.025 } },
};

type Preference = 'system' | 'reduced';
type MotionPreferences = {
  preference: Preference;
  setPreference: (value: Preference) => void;
  reduced: boolean;
};
const PreferencesContext = createContext<MotionPreferences | null>(null);

export function MotionProvider({ children }: { children: ReactNode }) {
  const systemReduced = useReducedMotion();
  const [preference, setPreference] = useState<Preference>(() => {
    try {
      return localStorage.getItem('dex-motion') === 'reduced' ? 'reduced' : 'system';
    } catch {
      return 'system';
    }
  });
  const reduced = preference === 'reduced' || !!systemReduced;
  useEffect(() => {
    document.documentElement.dataset.motion = reduced ? 'reduced' : 'full';
    try {
      localStorage.setItem('dex-motion', preference);
    } catch {
      /* Storage can be disabled. */
    }
  }, [preference, reduced]);
  const value = useMemo(() => ({ preference, setPreference, reduced }), [preference, reduced]);
  return (
    <PreferencesContext.Provider value={value}>
      <MotionConfig reducedMotion={reduced ? 'always' : 'never'} transition={spring}>
        {children}
      </MotionConfig>
    </PreferencesContext.Provider>
  );
}

export function useMotionPreferences() {
  const preferences = useContext(PreferencesContext);
  if (!preferences) throw new Error('Motion components require MotionProvider.');
  return preferences;
}

/** Only presentation state is animated. Operational quantities remain the server's values. */
export function PageTransition({ children }: { children: ReactNode }) {
  const { reduced } = useMotionPreferences();
  const present = useIsPresent();
  return (
    <motion.div
      className="workspace-page"
      inert={!present}
      initial={{ opacity: 0, y: reduced ? 0 : 8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: reduced ? 0 : -4, transition: { duration: reduced ? 0.03 : 0.09 } }}
      transition={{ duration: reduced ? 0.08 : 0.22, ease: easeOut }}
    >
      {children}
    </motion.div>
  );
}

type Tab = { value: string; label: string; count?: number };

/**
 * Tab strip (filter views and drawer sections) following the WAI-ARIA tabs pattern: one tab stop, Arrow Left/Right
 * (wrapping), Home and End move focus and select immediately.
 */
export function Tabs({
  items,
  value,
  onChange,
  label,
  compact = false,
  children,
}: {
  items: Tab[];
  value: string;
  onChange: (value: string) => void;
  label: string;
  compact?: boolean;
  children?: ReactNode;
}) {
  const id = useId();
  const selected = Math.max(
    0,
    items.findIndex((item) => item.value === value),
  );
  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    const moves: Record<string, number> = {
      ArrowRight: selected + 1,
      ArrowLeft: selected - 1,
      Home: 0,
      End: items.length - 1,
    };
    if (!(e.key in moves) || !items.length) return;
    e.preventDefault();
    const index = (moves[e.key] + items.length) % items.length;
    onChange(items[index].value);
    e.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')[index]?.focus();
  }
  return (
    <LayoutGroup id={id}>
      <div className={`${compact ? 'mini-tabs' : 'list-tabs'} motion-tabs`}>
        <div className="tab-list" role="tablist" aria-label={label} onKeyDown={onKeyDown}>
          {items.map((item, index) => (
            <button
              type="button"
              role="tab"
              key={item.value}
              aria-selected={value === item.value}
              tabIndex={index === selected ? 0 : -1}
              className={value === item.value ? 'active' : ''}
              onClick={() => onChange(item.value)}
            >
              <span className="tab-label">{item.label}</span>
              {item.count !== undefined && <span className="tab-count">{item.count}</span>}
              {value === item.value && (
                <motion.span className="tab-indicator" layoutId="selected-tab" transition={spring} />
              )}
            </button>
          ))}
        </div>
        {children}
      </div>
    </LayoutGroup>
  );
}

export function MotionSettings() {
  const { preference, setPreference, reduced } = useMotionPreferences();
  const [preview, setPreview] = useState(0);
  return (
    <section className="panel motion-settings-panel">
      <div className="panel-heading">
        <div>
          <h2>Interface motion</h2>
          <p>Small details. A smoother working day.</p>
        </div>
        <Sparkles size={18} />
      </div>
      <div className="motion-settings-body">
        <div className="motion-setting-copy">
          <span className="motion-setting-icon">
            {reduced ? <Accessibility size={21} /> : <Monitor size={21} />}
          </span>
          <div>
            <strong>{reduced ? 'Reduced motion is active' : 'Smooth transitions are active'}</strong>
            <p>
              Navigation, panels and dialogs share one consistent rhythm. Your device’s reduced-motion setting
              is always respected.
            </p>
          </div>
        </div>
        <label className="field">
          <span>Animation preference</span>
          <select
            aria-label="Animation preference"
            value={preference}
            onChange={(e) => setPreference(e.target.value as Preference)}
          >
            <option value="system">Follow device preference</option>
            <option value="reduced">Reduce motion</option>
          </select>
        </label>
        <div className="motion-preview" aria-hidden="true">
          <motion.div
            key={preview}
            className="motion-preview-cards"
            variants={reduced ? { hidden: {}, visible: {} } : stagger}
            initial="hidden"
            animate="visible"
          >
            {['Plan', 'Produce', 'Deliver'].map((step, i) => (
              <motion.div
                className="motion-preview-card"
                key={step}
                variants={
                  reduced
                    ? { hidden: { opacity: 0 }, visible: { opacity: 1, transition: { duration: 0.08 } } }
                    : reveal
                }
              >
                <span className="motion-preview-step">0{i + 1}</span>
                <span>{step}</span>
                <i />
              </motion.div>
            ))}
          </motion.div>
        </div>
        <button
          type="button"
          className="button secondary small"
          onClick={() => setPreview((value) => value + 1)}
        >
          <Play size={13} />
          Preview transitions
        </button>
      </div>
    </section>
  );
}
