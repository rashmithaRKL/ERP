import type { ReactNode } from 'react';
import { motion } from 'motion/react';
import { easeOut, useMotionPreferences } from '../motion/motion';

/** Card section with an optional heading row (`title`, `subtitle`, right-aligned `action`). */
export function Panel({
  title,
  subtitle,
  action,
  children,
  className = '',
}: {
  title?: string;
  subtitle?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  const { reduced } = useMotionPreferences();
  return (
    <motion.section
      className={`panel ${className}`}
      initial={reduced ? false : { opacity: 0, y: 10 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, amount: 0.06 }}
      transition={{ duration: 0.3, ease: easeOut }}
      whileHover={!reduced && /bom-card|machine-card|job-card/.test(className) ? { y: -2 } : undefined}
    >
      {title && (
        <div className="panel-heading">
          <div>
            <h2>{title}</h2>
            {subtitle && <p>{subtitle}</p>}
          </div>
          {action}
        </div>
      )}
      {children}
    </motion.section>
  );
}
