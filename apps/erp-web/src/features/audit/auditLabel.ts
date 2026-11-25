import { ACRONYMS, text } from '../../lib/format';

/**
 * Readable, past-tense labels for the action codes the API writes to `audit_events`
 * (every `Store.audit(...)` call in services/erp-api). Keep this in sync when the backend adds codes:
 * `AuditLabelCoverageTest` (services/erp-api) fails for a code without a label here. Unknown codes still get a
 * sensible label from {@link auditLabel}'s fallback.
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
  MO_AUTO_APPROVED: 'MO auto-approved by policy',
  MO_CHILDREN_CREATED: 'Child MOs created',
  PRODUCTION_POSTED: 'Output recorded',
  // Operations, routings, shift calendars and skills (V10).
  OP_STARTED: 'Operation started',
  OP_PAUSED: 'Operation paused',
  OP_RESUMED: 'Operation resumed',
  OP_SETUP_DONE: 'Operation setup done',
  OP_REPORTED: 'Operation quantities reported',
  OP_FINISHED: 'Operation finished',
  OP_SKILL_OVERRIDDEN: 'Skill requirement overridden',
  ROUTING_DRAFT_CREATED: 'Routing draft created',
  ROUTING_DRAFT_UPDATED: 'Routing draft updated',
  ROUTING_APPROVED: 'Routing approved',
  ROUTING_COPIED: 'Routing revision copied',
  ROUTING_MADE_CURRENT: 'Routing made current',
  SHIFT_PATTERN_CREATED: 'Shift pattern created',
  SHIFT_PATTERN_UPDATED: 'Shift pattern updated',
  CALENDAR_CREATED: 'Shift calendar created',
  CALENDAR_UPDATED: 'Shift calendar updated',
  CALENDAR_EXCEPTION_ADDED: 'Calendar exception added',
  CALENDAR_EXCEPTION_REMOVED: 'Calendar exception removed',
  WORK_CENTER_CALENDAR_ASSIGNED: 'Work center calendar assigned',
  SKILL_CREATED: 'Skill created',
  SKILL_UPDATED: 'Skill updated',
  USER_SKILL_SET: 'User skill recorded',
  USER_SKILL_REMOVED: 'User skill removed',
  // Purchasing, receiving and quality.
  PO_CREATED: 'PO created',
  PO_APPROVED: 'PO approved',
  // Procurement documents (V6) and approval policies (V9).
  PO_DRAFT_CREATED: 'PO draft created',
  PO_DRAFT_UPDATED: 'PO draft updated',
  PO_SUBMITTED: 'PO submitted for approval',
  PO_AUTO_APPROVED: 'PO auto-approved by policy',
  PO_CANCELLED: 'PO cancelled',
  PO_CLOSED_SHORT: 'PO closed short',
  SUPPLIER_RETURN_POSTED: 'Supplier return posted',
  GOODS_RECEIVED: 'Goods received',
  INVOICE_MATCHED: 'Supplier invoice matched',
  QC_PASSED: 'QC passed',
  QC_FAILED: 'QC failed',
  // Quality management (V12): inspection plans, nonconformances, dispositions, rework and CAPA.
  INSPECTION_PLAN_CREATED: 'Inspection plan created',
  INSPECTION_PLAN_UPDATED: 'Inspection plan updated',
  DEFECT_CODE_SAVED: 'Defect code saved',
  NCR_RAISED: 'Nonconformance raised',
  NCR_UPDATED: 'Nonconformance updated',
  NCR_CONCESSION_REQUESTED: 'Concession requested',
  NCR_CONCESSION_APPROVED: 'Concession approved',
  NCR_CONCESSION_REJECTED: 'Concession rejected',
  NCR_SCRAP_POSTED: 'Quality scrap posted',
  NCR_RETURN_REQUESTED: 'Return to supplier decided',
  NCR_RETURN_POSTED: 'Nonconforming stock returned to supplier',
  NCR_DISPOSITIONED: 'Nonconformance dispositioned',
  NCR_CLOSED: 'Nonconformance closed',
  NCR_CANCELLED: 'Nonconformance cancelled',
  REWORK_ORDER_CREATED: 'Rework order created',
  REWORK_COMPLETED: 'Rework completed',
  CAPA_CREATED: 'CAPA created',
  CAPA_UPDATED: 'CAPA updated',
  CAPA_ACTION_ADDED: 'CAPA action added',
  CAPA_ACTION_DONE: 'CAPA action done',
  CAPA_ACTION_CANCELLED: 'CAPA action cancelled',
  CAPA_STARTED: 'CAPA started',
  CAPA_SUBMITTED: 'CAPA sent for verification',
  CAPA_VERIFIED: 'CAPA verified effective',
  CAPA_REOPENED: 'CAPA not effective, reopened',
  CAPA_CANCELLED: 'CAPA cancelled',
  // Sales and delivery.
  SALE_CONFIRMED: 'SO confirmed',
  DISPATCH_POSTED: 'Dispatch posted',
  // Sales documents (V3).
  SO_DRAFT_CREATED: 'SO draft created',
  SO_DRAFT_UPDATED: 'SO draft updated',
  SO_CANCELLED: 'SO cancelled',
  SO_CLOSED_SHORT: 'SO closed short',
  CUSTOMER_RETURN_POSTED: 'Customer return posted',
  // Inventory operations (V5).
  ADJUSTMENT_CREATED: 'Adjustment drafted',
  ADJUSTMENT_APPROVAL_REQUESTED: 'Adjustment sent for approval',
  ADJUSTMENT_APPROVED: 'Adjustment approved',
  ADJUSTMENT_POSTED: 'Adjustment posted',
  ADJUSTMENT_CANCELLED: 'Adjustment cancelled',
  TRANSFER_CREATED: 'Transfer drafted',
  TRANSFER_POSTED: 'Transfer posted',
  TRANSFER_CANCELLED: 'Transfer cancelled',
  COUNT_STARTED: 'Cycle count started',
  COUNT_QUANTITIES_RECORDED: 'Count quantities recorded',
  COUNT_SUBMITTED: 'Count submitted for review',
  COUNT_REOPENED: 'Count reopened',
  COUNT_POSTED: 'Cycle count posted',
  COUNT_CANCELLED: 'Cycle count cancelled',
  WAREHOUSE_CREATED: 'Warehouse created',
  WAREHOUSE_UPDATED: 'Warehouse updated',
  // Master data and administration.
  ITEM_CREATED: 'Item created',
  // Item master (V4).
  ITEM_UPDATED: 'Item updated',
  ITEM_DEACTIVATED: 'Item deactivated',
  ITEM_REACTIVATED: 'Item reactivated',
  UNIT_CREATED: 'Unit created',
  UNIT_UPDATED: 'Unit updated',
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
  BOM_DRAFT_UPDATED: 'BOM draft updated',
  BOM_COPIED: 'BOM revision copied',
  PARTNER_CREATED: 'Partner created',
  PARTNER_UPDATED: 'Partner updated',
  PARTNER_DEACTIVATED: 'Partner deactivated',
  PARTNER_REACTIVATED: 'Partner reactivated',
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
  // Maintenance and downtime (V11).
  DOWNTIME_REPORTED: 'Downtime reported',
  DOWNTIME_CLOSED: 'Downtime closed',
  DOWNTIME_CORRECTED: 'Downtime corrected',
  DOWNTIME_VOIDED: 'Downtime withdrawn',
  MWO_REQUESTED: 'Maintenance requested',
  MWO_APPROVED: 'Maintenance approved',
  MWO_SCHEDULED: 'Maintenance scheduled',
  MWO_GENERATED: 'Preventive order generated',
  MWO_STARTED: 'Maintenance started',
  MWO_PROGRESS_RECORDED: 'Maintenance progress recorded',
  MWO_LABOUR_LOGGED: 'Maintenance labour logged',
  MWO_PARTS_ISSUED: 'Spare parts issued',
  MWO_PARTS_RETURNED: 'Spare parts returned',
  MWO_COMPLETED: 'Maintenance completed',
  MWO_CANCELLED: 'Maintenance cancelled',
  PM_PLAN_CREATED: 'Maintenance plan created',
  PM_PLAN_UPDATED: 'Maintenance plan updated',
  PM_ORDERS_GENERATED: 'Preventive orders generated',
  MACHINE_METER_RECORDED: 'Run-hour meter recorded',
  MACHINE_STATUS_CORRECTED: 'Machine status corrected',
  MAINTENANCE_SETTINGS_UPDATED: 'Maintenance labour rate updated',
  // OEE and utilization (V15).
  OEE_DAY_CLOSED: 'OEE day closed',
  OEE_DAY_RECALCULATED: 'OEE day recalculated',
  DEMO_OEE_HISTORY_ADDED: 'Demo OEE history added',
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
