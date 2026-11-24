import { use } from 'react';
import type { ComponentType } from 'react';

type PageModule = { default: ComponentType };

/** A route page in its own chunk. Render it like a component; `preload()` starts (or reuses) the download. */
export type LazyPage = ComponentType & { preload: () => Promise<PageModule> };

/**
 * Code-split route page for the registry in `routes.tsx`: `lazyPage(() => import('../pages/MyPage'))`.
 *
 * Until its chunk has loaded the page suspends to the shell's `<Suspense>` skeleton. Once loaded it renders
 * synchronously, so a page preloaded on hover, focus or idle never flashes the skeleton. A failed download is not
 * remembered: the next `preload()` or render tries again.
 */
export function lazyPage(load: () => Promise<PageModule>): LazyPage {
  let loaded: ComponentType | undefined;
  let pending: Promise<PageModule> | undefined;
  const preload = () =>
    (pending ??= load().then(
      (module) => {
        loaded = module.default;
        return module;
      },
      (error: unknown) => {
        pending = undefined;
        throw error;
      },
    ));
  function LazyRoute() {
    const Page = loaded ?? use(preload()).default;
    return <Page />;
  }
  return Object.assign(LazyRoute, { preload });
}

const whenIdle = (callback: () => void) => {
  if (typeof window.requestIdleCallback === 'function')
    window.requestIdleCallback(callback, { timeout: 4000 });
  else setTimeout(callback, 250);
};

/**
 * Downloads page chunks one at a time while the browser is idle, so later navigation does not wait for the network.
 * Skipped when the browser asks to save data. Returns a function that stops the remaining downloads.
 */
export function preloadWhenIdle(pages: LazyPage[]): () => void {
  const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection;
  if (connection?.saveData) return () => undefined;
  let stopped = false;
  const queue = [...pages];
  const next = () => {
    const page = queue.shift();
    if (stopped || !page) return;
    whenIdle(() => {
      if (!stopped) page.preload().then(next, next);
    });
  };
  next();
  return () => {
    stopped = true;
  };
}
