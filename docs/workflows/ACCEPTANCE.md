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
| Multi-level MRP | Open MO demand → semi-finished netting (stock, QC stock, open/child MOs, safety stock) → planned production → raw/packaging gross demand → netting against stock and incoming purchases → purchase recommendations with order dates, pegged to driving orders → "Create MO" / "Create purchase" | Infinite capacity, lot-for-lot, single bucket per item; sales-line demand for finished goods is not exploded |
| Routing to operations (V10) | Draft routing (operations, work centers, alternates, setup/run minutes, crew, skill, inspection point) → approval by another planner (not author or last editor) → current revision → new MO snapshots it into PENDING operations (planned minutes for the order quantity) → issue readies operation 1 → start (first start starts the order) / setup done / pause / resume / report / finish on the Shop floor, strictly in sequence → time logs per SETUP/RUN interval → output posting limited to the last operation's good quantity, final output after every operation is done | Overlapping operations, splitting an operation across work centers, rescheduling (V13) and labour/machine cost from time logs later |
| Shift calendars (V10) | Shift patterns (overnight, one break) → calendars (plant default, per work-center assignment) → holidays and overtime → available minutes per work center and day from one service (`routing.CalendarService`) | No history of calendar changes; no per-operator rosters |
| Skills (V10) | Skill catalogue → user levels with certification expiry → operations requiring a skill start/resume only for a qualified user; PLANNER/ADMIN override with a recorded, audited reason | No training records or automatic expiry reminders |

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
24. Approved routing tampering: updating or deleting an approved routing, its operations or alternates is rejected by database triggers; only an approved revision can be current (`routing.RoutingTest`).
25. Routing self-approval: the author and the last editor of a draft cannot approve it, ADMIN included.
26. Operation sequence: operation N+1 cannot start before N is done; an order not yet released cannot start operations; quantities never exceed the previous operation's good output (`manufacturing.OperationTrackingTest`).
27. One running operation per work center: a second start on a busy work center is refused (also enforced by a unique index on open time logs); a work center under MAINTENANCE refuses starts, and pausing never overwrites MAINTENANCE.
28. Missing, expired or too-low skill: an operator is refused; a planner/administrator starts only with an override reason, recorded on the time log and audited.
29. Orders without a routing keep the exact pre-V10 lifecycle (create → approve → reserve → issue → start → pause/resume → output → QC → close) and never change work-center status.
30. Calendar maths: breaks, overnight shifts split across days, holidays cancelling the shifts that start that day, overtime (including across midnight and on a holiday) counted once, assigned vs plant-default calendars, around-the-clock without a calendar (`routing.CalendarServiceTest`).

## Multi-level browser acceptance (V7 demo data)

1. **Bills of materials** (planner or admin): filter *Multi-level*; the *PET bottle 500ml · clear* (FG-101) R01 card shows *2 levels*, *1 semi-finished*, a rolled-up cost per unit with the single-level figure below it and level bars. **Structure** opens the drawer: change the quantity, collapse/expand nodes and use *Level 1 / All levels*; **Cost roll-up** and **Where used** tabs. On the preform (SF-001) card, **Structure → Where used** shows FG-101 R01/R02.
2. On FG-101 **Compare** shows R01 → R02: carton changed (20 → 25), yield 99% → 98.5%, cost deltas. On R02 (draft) **Edit** changes a quantity; **Copy to draft** on R01 asks for a new reference (R03 suggested).
3. **Manufacturing orders**: the FG-101 order due in 7 days (approved) shows **Order structure** in its drawer with one child order (SF-001 preform, "Child of …" in the list); the child's drawer shows its parent. The child needs approval by someone other than the planner who created it.
4. **Material planning** (planner, warehouse or admin): the SF-001 preform row (level 1) shows planned production pegged to the draft FG-101 order due in 14 days (and safety stock); **Pegging** lists the orders; *Planned production* → **Create MO** prefills the BOM, quantity, start date and the parent order. *Purchase recommendations* → **Create purchase** prefills the purchasing form.
5. On the draft FG-101 order (due in 14 days), the order structure shows the released preforms already allocated to the earlier order; **Create child orders for shortages** creates a preform child for its full requirement. Material planning then pegs no order demand to the preform; only the safety-stock top-up remains as planned production.

## Routings, shifts and shop-floor browser acceptance (V10 demo data)

1. **Routings & shifts → Routings** (demo.planner): FG-001, SF-001 and FG-101 have approved, *current* R01 revisions; FG-101 also has an R02 draft. Open FG-101 R02: the drawer lists three operations (work center, alternates, setup, run per unit with the rate, crew, skill). **Compare** shows operation 10 changed (run minutes per unit, instructions). The planner authored R02, so the footer says another planner must approve it; as demo.admin **Approve routing** makes R02 current. On R01, **Copy to new draft** suggests R03; **Make current** switches back. The heading's **New routing** opens the operation editor (alternates appear once a work center is chosen).
2. **Shift calendars**: the week grid shows available hours per work center: BM-01/BM-02 on the three-shift *CONTINUOUS* calendar, the others on the *TWO-SHIFT* plant default (day 06:00–14:00 Mon–Sat, evening 14:00–22:00 Mon–Fri, 30-minute breaks); the Poya holiday nine days after seeding is marked, the next Sunday shows 4 h overtime on the plant default. **Exceptions** lists and adds holidays/overtime; the select in each row reassigns the work center's calendar; shift patterns open for editing.
3. **Skills**: catalogue EBM, SBM, IM, QCS, FLT; in the matrix Kasun Silva (operator) holds EBM L3, SBM L2, IM L2, QCS L2 and an expired FLT; click a cell to change the level or expiry, or remove it.
4. **Shop floor** (demo.operator): pick **PK-01**: the FG-001 order's operation 20 *Label, inspect & pack* is running in setup with its elapsed time, planned vs actual minutes and the QCS skill hint; **Setup done**, **Report** (e.g. 2,000 good), **Finish** (remaining of the 4,960 input prefilled). Pick **BM-02**: the urgent FG-101 operation 10 is ready (Start setup / Skip setup); while PK-01 still runs, starting another PK-01 operation is refused. **BM-01** offers FG-101 operation 10 as an *alternate*; **IM-03** (maintenance) offers the preform operation but cannot start it. Orders without a routing keep their order cards below the queue.
5. Open the FG-001 order from its card: the drawer's **Operations** section shows routing R01, operation 10 done (4,960 good, 40 scrap, 295 min planned vs 4 h actual) and operation 20 running. **Record output** above the last operation's good quantity is refused; the final output needs every operation finished.
6. As demo.planner on the Shop floor, **Start** on the FG-101 operation (SBM required, the planner lacks it) asks for an override reason; the time log and audit trail record it. An operator without the skill sees "A qualified operator must start this operation".

## Manual browser acceptance

Check each module loads without runtime errors; create an order, switch demo role for independent approval, reserve/issue/start, record partial/final output, finalize QC and dispatch. Test empty searches, validation failures, date filters, CSV download, keyboard dialog dismissal/focus, narrow layouts and theme controls. See the verification record for actions actually executed.
