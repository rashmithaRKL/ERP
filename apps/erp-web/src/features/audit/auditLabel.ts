import { text } from '../../lib/format';

/** Abbreviations that stay upper-case wherever they appear in an action code. */
const ACRONYMS = new Set(['MO', 'PO', 'SO', 'QC', 'BOM', 'GRN', 'FIFO', 'WIP']);

/**
 * Readable, past-tense labels for the action codes the API writes to `audit_events`
 * (every `Store.audit(...)` call in services/erp-api). Keep this in sync when the backend adds codes;
 * unknown codes still get a sensible label from {@link auditLabel}'s fallback.
 */
const LABELS: Record<string, string> = {
  // Manufacturing orders: MO_CREATED, then MO_<action> for each lifecycle transition.
  MO_CREATED: 'MO created',
  MO_APPROVE: 'MO approved',
  MO_RESERVE: 'Materials reserved',
  MO_ISSUE: 'Materials issued',
  MO_START: 'Production started',
  MO_PAUSE: 'Production paused',
  MO_RESUME: 'Production resumed',
  MO_CLOSE: 'MO closed',
  MO_CANCEL: 'MO cancelled',
  PRODUCTION_POSTED: 'Output recorded',
  // Purchasing, receiving and quality.
  PO_CREATED: 'PO created',
  PO_APPROVED: 'PO approved',
  GOODS_RECEIVED: 'Goods received',
  INVOICE_MATCHED: 'Supplier invoice matched',
  QC_PASSED: 'QC passed',
  QC_FAILED: 'QC failed',
  // Sales and delivery.
  SALE_CONFIRMED: 'SO confirmed',
  DISPATCH_POSTED: 'Dispatch posted',
  // Master data and administration.
  ITEM_CREATED: 'Item created',
  // Item master (V4): ITEM_UPDATED / ITEM_DEACTIVATED / UNIT_CREATED... use the fallback.
  ITEM_CONVERSION_SET: 'Unit conversion set',
  ITEM_CONVERSION_REMOVED: 'Unit conversion removed',
  ITEM_BARCODE_ADDED: 'Barcode added',
  ITEM_BARCODE_REMOVED: 'Barcode removed',
  ITEM_TEMPLATE_CREATED: 'Variant family created',
  ITEM_TEMPLATE_UPDATED: 'Variant family updated',
  ITEM_VARIANTS_GENERATED: 'Variants generated',
  ITEM_CATEGORY_CREATED: 'Category created',
  ITEM_CATEGORY_UPDATED: 'Category updated',
  BOM_DRAFT_CREATED: 'BOM draft created',
  BOM_APPROVED: 'BOM approved',
  PARTNER_CREATED: 'Partner created',
  WORK_CENTER_CREATED: 'Work center created',
  ORGANIZATION_UPDATED: 'Organization updated',
  USER_CREATED: 'User created',
  DEMO_INITIALIZED: 'Demo data initialized',
  // Administration (V8).
  USER_UPDATED: 'User updated',
  USER_DEACTIVATED: 'User deactivated',
  USER_REACTIVATED: 'User reactivated',
  USER_PASSWORD_RESET: 'Temporary password issued',
  PASSWORD_CHANGED: 'Password changed',
  APPROVAL_POLICY_UPDATED: 'Approval policy updated',
  INVENTORY_SETTINGS_UPDATED: 'Adjustment threshold updated',
  IMPORT_COMMITTED: 'CSV import committed',
  AUDIT_EXPORTED: 'Audit trail exported',
  PERIOD_CLOSED: 'Accounting period closed',
  PERIOD_REOPENED: 'Accounting period reopened',
};

/** Fallback: a trailing bare verb (e.g. the `CANCEL` in `PO_CANCEL`) is shown in the past tense. */
const PAST_TENSE: Record<string, string> = {
  approve: 'approved',
  cancel: 'cancelled',
  close: 'closed',
  complete: 'completed',
  confirm: 'confirmed',
  create: 'created',
  delete: 'deleted',
  dispatch: 'dispatched',
  fail: 'failed',
  issue: 'issued',
  match: 'matched',
  pass: 'passed',
  pause: 'paused',
  post: 'posted',
  receive: 'received',
  reject: 'rejected',
  release: 'released',
  reserve: 'reserved',
  resume: 'resumed',
  return: 'returned',
  start: 'started',
  update: 'updated',
};

/**
 * Audit action code to a sentence-case label, e.g. `MO_APPROVE` -> "MO approved",
 * `MO_RESERVE` -> "Materials reserved". Unknown codes fall back to sentence case with
 * acronyms kept upper-case: `GRN_REVERSED` -> "GRN reversed", `PO_CANCEL` -> "PO cancelled".
 */
export function auditLabel(action: unknown): string {
  const code = text(action).trim().toUpperCase();
  if (Object.hasOwn(LABELS, code)) return LABELS[code];
  const words = code.split(/[_\s]+/).filter(Boolean);
  return words
    .map((word, i) => {
      if (ACRONYMS.has(word)) return word;
      const lower = word.toLowerCase();
      const pastTense = i > 0 && i === words.length - 1 && Object.hasOwn(PAST_TENSE, lower);
      const value = pastTense ? PAST_TENSE[lower] : lower;
      return i === 0 ? value.charAt(0).toUpperCase() + value.slice(1) : value;
    })
    .join(' ');
}
