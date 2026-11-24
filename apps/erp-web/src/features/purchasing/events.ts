import type { Row } from '../../lib/api';

/**
 * Lets the page-heading action "New purchase order" and the Planning page's "Create purchase" (both call
 * `createPurchase` in `forms.ts`) open the multi-line editor that `PurchasingPage` owns, without adding purchasing
 * state to the shared workspace. While the Purchasing page is not mounted nothing is registered and the caller falls
 * back to the quick single-line form.
 */
type Handler = (material?: Row) => void;

let handler: Handler | undefined;

/** Opens the editor on the Purchasing page; returns false when that page is not mounted. */
export function requestNewPurchaseOrder(material?: Row) {
  if (!handler) return false;
  handler(material);
  return true;
}

/** Registers the Purchasing page's editor opener; returns the unregister function (use it as an effect cleanup). */
export function onNewPurchaseOrder(listener: Handler) {
  handler = listener;
  return () => {
    if (handler === listener) handler = undefined;
  };
}
