/**
 * Lets the page-heading action "New routing" open the routing editor that `RoutingsPage` owns, without adding routing
 * state to the shared workspace. While the page is not mounted nothing is registered and the request is ignored.
 */
type Handler = () => void;

let handler: Handler | undefined;

/** Opens the routing editor on the Routings & shifts page; returns false when that page is not mounted. */
export function requestNewRouting() {
  if (!handler) return false;
  handler();
  return true;
}

/** Registers the page's editor opener; returns the unregister function (use it as an effect cleanup). */
export function onNewRouting(listener: Handler) {
  handler = listener;
  return () => {
    if (handler === listener) handler = undefined;
  };
}
