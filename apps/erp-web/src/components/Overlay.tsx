import { useId, useLayoutEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { motion, useIsPresent } from 'motion/react';
import { X } from 'lucide-react';
import { easeOut, spring, useMotionPreferences } from '../motion/motion';

/** Native modal <dialog> (or side drawer) with enter/exit motion. Render inside <AnimatePresence>. */
export function Overlay({
  title,
  subtitle,
  children,
  onClose,
  wide = false,
  drawer = false,
}: {
  title: string;
  subtitle?: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
  drawer?: boolean;
}) {
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
        const otherDialogs = Array.from(document.querySelectorAll('dialog[open]')).filter(
          (item) => item !== dialog,
        );
        if (
          (active === document.body || active === dialog || (active && dialog?.contains(active))) &&
          otherDialogs.every((item) => item.contains(trigger))
        )
          trigger.focus({ preventScroll: true });
      });
    };
  }, []);
  return (
    <motion.dialog
      ref={ref}
      className={`dialog ${wide ? 'wide' : ''} ${drawer ? 'drawer' : ''}`}
      inert={!present}
      data-motion-state={present ? 'open' : 'exiting'}
      initial={{
        opacity: 0,
        x: !reduced && drawer ? 40 : 0,
        y: !reduced && !drawer ? 14 : 0,
        scale: !reduced && !drawer ? 0.98 : 1,
      }}
      animate={{ opacity: 1, x: 0, y: 0, scale: 1 }}
      exit={{
        opacity: 0,
        x: !reduced && drawer ? 28 : 0,
        y: !reduced && !drawer ? 8 : 0,
        scale: !reduced && !drawer ? 0.985 : 1,
        transition: { duration: reduced ? 0.06 : 0.16, ease: easeOut },
      }}
      transition={reduced ? { duration: 0.08 } : { ...spring, opacity: { duration: 0.16 } }}
      onCancel={(e) => {
        e.preventDefault();
        if (present) onClose();
      }}
      onClick={(e) => {
        if (present && e.target === e.currentTarget) onClose();
      }}
      aria-labelledby={titleId}
    >
      <div className="dialog-heading">
        <div>
          <h2 id={titleId}>{title}</h2>
          {subtitle && <p>{subtitle}</p>}
        </div>
        <button className="icon-button" aria-label="Close dialog" disabled={!present} onClick={onClose}>
          <X size={20} />
        </button>
      </div>
      {children}
    </motion.dialog>
  );
}
