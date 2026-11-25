# Workflow acceptance

A screen alone does not satisfy a business workflow. Each supported command has server authorization, transactional database writes, validation, audit/outbox records and retry deduplication.

| Workflow | Implemented slice | Boundary |
|---|---|---|
| Purchase to stock | Draft multi-line PO in purchase units (exact base conversion) → submit → independent approval (not creator/submitter) → per-line GRN under a delivery note into an active RAW warehouse → quarantine lot per line → incoming QC (fail reverses stock/GRNI and reopens the line) → usable lot → supplier invoice matched to released receipts with quantity/price variances (GRNI released at receipt cost, price variance to PURCHASE_PRICE_VARIANCE, payables = billed) ; cancel with nothing received, close short with reason | Requisitions/RFQs, supplier price lists, payment runs and inventory revaluation of price variances later |
| Supplier return | Return released stock of a receipt lot (≤ received − returned, ≤ unreserved) with reason and debit note → stock out at lot balance value → Dr GRNI (part not yet invoiced) / Dr ACCOUNTS_PAYABLE (part invoiced, debit note), Cr RAW_INVENTORY → PO line returned grows; posted returns immutable | Return shipping documents and supplier credit-note reconciliation later |
| Planning to purchase | MRP shortages → draft POs in one request, one per preferred supplier (last active supplier the item was ordered from) plus one supplier-less draft the planner completes; MRP incoming = open base quantity of APPROVED/PARTIAL lines | Lead-time offsetting, MOQ and supplier price lists later |
| Sales to delivery | Draft multi-line SO → confirm (credit-limit warning) → MO per SO line from approved BOM → reserve → issue → start → output → final QC → finished stock → per-line partial/full dispatch → revenue/COGS per delivery; cancel (no delivery, no production in progress) or close short | Quotations, blocking credit control, taxes and shipping integration later |
| Customer return | Return against a delivery (≤ delivered − returned) → quarantine lot at delivered cost → reversing revenue/receivable and COGS journals → RETURN inspection → pass restocks / fail holds | Replacement shipments are new orders; credit notes as documents and cash refunds later |
| Material consumption | FIFO reservations → authorized issue → negative raw ledger → positive WIP value | Exact snapshot issue only; unplanned consumption requires a future controlled variance workflow |
| Partial production | Partial good/scrap output → proportional cost allocation → retained WIP → final output → zero WIP | Conversion costs entered per posting; no implicit whole-order cost allocation |
| Quality failure | Incoming failure → supplier return reversal; final failure → enforceable finished lot hold | Final rework/release/scrap disposition not implemented; unsafe dispatch remains blocked |
| Local operations | Same local API/PostgreSQL transaction boundaries | Browser offline queue and cloud reconciliation not implemented or verified |
| Costing | Actual FIFO materials + entered labour/machine → WIP/finished/scrap → dispatch COGS | Manufacturing subledger; full finance and statutory statements later |
| Multi-level BOM | Semi-finished BOM approved first → parent BOM approved (no cycle at any depth) → explosion/where-used/cost roll-up through all levels → copy approved revision to draft → edit → compare → approve | Alternative components, phantom BOMs, effectivity dates and engineering change orders later |
| Parent/child production | Parent MO (draft/approved) → child MOs for semi-finished shortages (due lead time before the parent) → independent approval of each child → reserve/issue/start/output → final QC releases the SF lot into the raw store → parent's ordinary reservation consumes it | Children are not rescheduled or cancelled automatically when the parent changes; capacity is not checked |
| Breakdown to repair (V11) | Operator reports a breakdown (open BREAKDOWN downtime + corrective work order) → a different planner approves (and may schedule) → technician starts (machine MAINTENANCE; refused while an MO is in progress on the machine) → checklist/findings, labour (journalized), spare parts issued FIFO and unused parts returned → complete (machine IDLE, downtime closed) | No approval-policy threshold for maintenance; machine RUNNING/IDLE transitions from production belong to routings (V10) |
| Preventive maintenance (V11) | Plan per machine (calendar days and optional run hours) → due/overdue list → idempotent generation of scheduled preventive orders → start/complete → plan rescheduled from the completion date; cancelling skips the occurrence; meter readings pull run-hour plans forward | Run hours are entered manually (not derived from production yet); generation is a command, not a background job |
| Downtime analysis (V11) | Report/close downtime with reason and planned flag (no overlap per machine) → append-only corrections → Pareto by reason, downtime minutes, MTBF/MTTR per machine for a period | Calendar time (no shift calendar); OEE availability is V15 |
| Multi-level MRP | Open MO demand → semi-finished netting (stock, QC stock, open/child MOs, safety stock) → planned production → raw/packaging gross demand → netting against stock and incoming purchases → purchase recommendations with order dates, pegged to driving orders → "Create MO" / "Create purchase" | Infinite capacity, lot-for-lot, single bucket per item; sales-line demand for finished goods is not exploded |

## Defined critical outcomes

1. Competing final-stock reservations: one winner, losing transaction rolls back, no oversell.
2. Duplicate completion key: exact prior result, one production posting.
3. New BOM revisions: approved versions cannot mutate; existing order snapshot remains fixed.
4. Lost HTTP response during material issue: retry same key; successful prior issue is returned. Network fault injection is still pending.
5. Quality-held dispatch: rejected transaction with no stock/accounting side effects.
6. Extra material use: unsupported additional-issue command; no uncontrolled posting path. Consumption variance approval is future scope.
7. Partial output: unfinished quantities/cost remain in WIP; final output clears it.
8. Damaged supplier receipt: incoming failure returns the lot and reverses clearing; PO remainder reopens.
9. Replayed local command: deduplicated by owner/key/action/hash. Distributed inbox replay is future scope.
10. Cross-tenant record/action: 404; cross-tenant foreign keys cannot be formed.
11. Mid-migration failure: PostgreSQL migration transaction rolls back; earlier migration remains applied. Dedicated regression test included.
12. Backup restore: restore script targets a new recovery database and prints reconciliation counts. Execution drill pending because Docker is unavailable here.
13. Closed accounting period: completion/dispatch/receipt postings fail and roll back atomically.
14. Offline inter-factory transfer: unsupported and not advertised. Future implementation must use in-transit custody.
15. Restricted self-approval: denied, including when creator is ADMIN; another authorized user must approve.
16. Purchase invoice above the order price: payables carry the billed amount, GRNI clears at receipt cost, the difference is a balanced purchase price variance; over-billing a receipt is refused.
17. Supplier return after partial invoicing: the uninvoiced part reverses GRNI, the invoiced part reduces payables; GRNI of a fully processed receipt nets to exactly zero (`procurement.SupplierReturnTest`).
18. Legacy single-item purchase orders and receipts migrate to header + line with posted values unchanged (`procurement.ProcurementMigrationTest`).
19. BOM cycle across levels (A → B → A, or deeper): approval rejected with the cycle path; the revision stays a draft.
20. Parent BOM with a semi-finished component that has no approved BOM: approval rejected until the component's revision is approved (bottom-up).
21. Child order for a shortage: quantity = requirement − released stock − open child supply; a second run finds nothing short (409); the same key replays the first result.
22. Child output satisfies the parent: after final QC releases the child's SF lot, the parent's reservation allocates it FIFO with the older stock; issue consumes it.
23. Parent link tampering: changing `parent_order_id` is rejected by the database trigger.
24. Overlapping downtime on one machine (a second open event, or a closed event inside another): refused (409); back-to-back events are allowed (`maintenance.DowntimeTest`).
25. Editing or deleting a closed downtime event: refused by the database; a planner's correction supersedes it and records the corrected or withdrawn event, and only effective events count in metrics.
26. Starting maintenance while an MO is in progress or partially completed on the machine: refused with the MO number until a planner pauses it; one work order in progress per machine.
27. Self-approval of a maintenance request: refused, also for ADMIN.
28. "Generate due work orders" twice (or retried): one preventive order per plan and due date; a retry with the same key replays the first result.
29. Spare-part issue beyond released, unreserved stock or in a closed period: refused with no ledger or journal rows; returns put parts back into the issuing lot at the issue value and the expense journal nets to the parts actually used.

## Multi-level browser acceptance (V7 demo data)

1. **Bills of materials** (planner or admin): filter *Multi-level*; the *PET bottle 500ml · clear* (FG-101) R01 card shows *2 levels*, *1 semi-finished*, a rolled-up cost per unit with the single-level figure below it and level bars. **Structure** opens the drawer: change the quantity, collapse/expand nodes and use *Level 1 / All levels*; **Cost roll-up** and **Where used** tabs. On the preform (SF-001) card, **Structure → Where used** shows FG-101 R01/R02.
2. On FG-101 **Compare** shows R01 → R02: carton changed (20 → 25), yield 99% → 98.5%, cost deltas. On R02 (draft) **Edit** changes a quantity; **Copy to draft** on R01 asks for a new reference (R03 suggested).
3. **Manufacturing orders**: the FG-101 order due in 7 days (approved) shows **Order structure** in its drawer with one child order (SF-001 preform, "Child of …" in the list); the child's drawer shows its parent. The child needs approval by someone other than the planner who created it.
4. **Material planning** (planner, warehouse or admin): the SF-001 preform row (level 1) shows planned production pegged to the draft FG-101 order due in 14 days (and safety stock); **Pegging** lists the orders; *Planned production* → **Create MO** prefills the BOM, quantity, start date and the parent order. *Purchase recommendations* → **Create purchase** prefills the purchasing form.
5. On the draft FG-101 order (due in 14 days), the order structure shows the released preforms already allocated to the earlier order; **Create child orders for shortages** creates a preform child for its full requirement. Material planning then pegs no order demand to the preform; only the safety-stock top-up remains as planned production.

## Maintenance browser acceptance (V11 demo data)

1. **Maintenance** as *demo.operator* (Kasun Silva): the summary shows one open breakdown (IM-03) and one overdue plan (BM-01). **Request maintenance** (heading) with "Machine stopped: Yes" on a running machine creates a corrective order *awaiting approval* and an open breakdown; the drawer says it awaits a different planner.
2. As *demo.planner* (Dilini Fernando): open that order → **Approve** (optionally with a window and technician). **Plans** → *Overdue* shows *Monthly blow-moulding service* (BM-01) → **Generate due orders** (*Due today or overdue*) creates one scheduled preventive order; running it again reports that the plan already has an order.
3. As *demo.maintenance* (Ruwan Wickramasinghe): **Work orders** → *In progress* shows the IM-03 hydraulic repair: record checklist/findings, **Log labour**, **Issue parts** (SP-001 bearing), return one, then **Complete work**: IM-03 becomes idle and its breakdown closes. Starting the BM-01 preventive order is refused while BM-01 production is in progress (pause it first as the planner).
4. **Downtime**: the Pareto lists breakdown, changeover, material shortage, quality hold, no operator and utilities minutes for 30 days; reliability shows MTBF/MTTR per work center; as planner **Correct** a closed event and tick *Show corrected events* to see the superseded original.
5. **Work centers**: PK-01 shows its completed repair in **Maintenance & history**; a machine drawer offers **Report breakdown**, **Report downtime**, **Meter reading** and (planner) **Correct status** (refused while a work order is in progress). The **Overview** attention panel and the bell show open breakdowns and overdue preventive work.

## Manual browser acceptance

Check each module loads without runtime errors; create an order, switch demo role for independent approval, reserve/issue/start, record partial/final output, finalize QC and dispatch. Test empty searches, validation failures, date filters, CSV download, keyboard dialog dismissal/focus, narrow layouts and theme controls. See the verification record for actions actually executed.
