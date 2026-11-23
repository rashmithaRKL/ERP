/**
 * Lets the page heading's "New sales order" action (app/routes.tsx, rendered by the shell) open the order editor
 * that `SalesPage` owns, without adding sales state to the shared workspace.
 */
const bus = new EventTarget();

export const requestNewSalesOrder = () => bus.dispatchEvent(new Event('new-sales-order'));

/** Subscribes to "new sales order" requests; returns the unsubscribe function (use it as an effect cleanup). */
export function onNewSalesOrder(listener: () => void) {
  bus.addEventListener('new-sales-order', listener);
  return () => bus.removeEventListener('new-sales-order', listener);
}
