import { motion } from 'motion/react';
import { fmt } from '../lib/format';
import { spring, useMotionPreferences } from '../motion/motion';

export function Progress({ value, label = false }: { value: number; label?: boolean }) {
  const { reduced } = useMotionPreferences();
  const progress = Number.isFinite(value) ? Math.min(Math.max(value, 0), 100) : 0;
  return (
    <div className="progress-wrap">
      <div
        className="progress-track"
        role="progressbar"
        aria-label="Completion"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={progress}
      >
        <motion.div
          initial={false}
          animate={{ width: `${progress}%` }}
          transition={reduced ? { duration: 0 } : spring}
        />
      </div>
      {label && <span>{fmt(progress)}%</span>}
    </div>
  );
}
