import { motion } from 'motion/react';
import { ArrowUpRight, Timer } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { reveal, spring, useMotionPreferences } from '../motion/motion';

/** KPI card. Place inside a `motion.div` using the `stagger` variants so cards reveal in sequence. */
export function Stat({
  label,
  value,
  icon: Icon,
  note,
  tone = 'neutral',
  foot,
}: {
  label: string;
  value: string;
  icon: LucideIcon;
  note: string;
  tone?: string;
  foot?: string;
}) {
  const { reduced } = useMotionPreferences();
  return (
    <motion.div
      className="stat-card"
      variants={reduced ? undefined : reveal}
      whileHover={reduced ? undefined : { y: -2 }}
      transition={spring}
    >
      <div className="stat-label">
        {label}
        <span className="stat-icon">
          <Icon size={17} />
        </span>
      </div>
      <strong className="stat-value">{value}</strong>
      <div className="stat-bottom">
        <span className={`stat-note ${tone}`}>
          {tone === 'green' ? (
            <ArrowUpRight size={13} />
          ) : tone === 'amber' ? (
            <Timer size={13} />
          ) : (
            <span className="mini-dot" />
          )}
          {note}
        </span>
        {foot && <span>{foot}</span>}
      </div>
    </motion.div>
  );
}
