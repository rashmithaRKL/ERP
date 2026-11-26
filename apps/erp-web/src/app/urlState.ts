import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';

/**
 * Page state kept in the query string (V16), so links such as `/maintenance?tab=downtime` or
 * `/traceability?tab=recalls&recall=…` work while the page is already open and every view can be shared.
 *
 * `update` replaces the current history entry (tab switches and drawers do not pile up in the back button) and applies
 * all changes at once; `null`, `undefined` and `''` remove a parameter.
 */
export function useUrlParams() {
  const [params, setParams] = useSearchParams();
  const update = useCallback(
    (changes: Record<string, string | null | undefined>) =>
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          for (const [key, value] of Object.entries(changes)) {
            if (value == null || value === '') next.delete(key);
            else next.set(key, value);
          }
          return next;
        },
        { replace: true },
      ),
    [setParams],
  );
  return [params, update] as const;
}

/**
 * The page's tab from `?tab=` (one of `values`, else `fallback`) and a setter that writes it back (the fallback tab
 * removes the parameter). The tab follows the URL, so navigating to another `?tab=` on the same page switches it.
 */
export function useTabParam(values: readonly string[], fallback: string) {
  const [params, update] = useUrlParams();
  const raw = params.get('tab') ?? '';
  const tab = values.includes(raw) ? raw : fallback;
  const setTab = useCallback(
    (next: string) => update({ tab: next === fallback ? null : next }),
    [update, fallback],
  );
  return [tab, setTab] as const;
}
