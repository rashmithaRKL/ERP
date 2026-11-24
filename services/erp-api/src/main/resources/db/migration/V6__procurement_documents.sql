-- V6: procurement documents. Multi-line purchase orders (draft -> submitted -> independently approved -> partial ->
-- received, or cancelled / closed short), per-line goods receipts with entered purchase units, supplier invoices whose
-- lines are matched to receipts with quantity and price variances, and supplier returns of released stock.
-- Existing single-item purchase orders become a header plus line 1; receipts gain the line id and their posted value;
-- invoiced receipts become one supplier invoice each. No posted stock/journal/audit row is touched.

-- ------------------------------------------------------------------ purchase order header
ALTER TABLE purchase_orders
    ADD COLUMN supplier_reference text,
    ADD COLUMN notes text NOT NULL DEFAULT '',
    ADD COLUMN total numeric(20,6) NOT NULL DEFAULT 0 CHECK (total >= 0),
    ADD COLUMN status_reason text,
    ADD COLUMN submitted_by text REFERENCES users(id),
    ADD COLUMN submitted_at timestamptz,
    ADD COLUMN approved_by text REFERENCES users(id),
    ADD COLUMN approved_at timestamptz,
    ADD COLUMN closed_by text REFERENCES users(id),
    ADD COLUMN closed_at timestamptz,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- ------------------------------------------------------------------ purchase order lines
-- quantity/unit_cost are in the entered purchase unit (the item's base unit or a conversion flagged for purchase);
-- base_quantity = quantity x factor (exact at the base unit's precision) and base_unit_cost = unit_cost / factor are
-- what receipts, lots, the stock ledger and MRP use. received/returned are base quantities.
CREATE TABLE purchase_order_lines (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    purchase_order_id text NOT NULL,
    line_no integer NOT NULL CHECK (line_no > 0),
    item_id text NOT NULL,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    unit text NOT NULL,
    factor numeric(24,12) NOT NULL CHECK (factor > 0),
    base_quantity numeric(20,6) NOT NULL CHECK (base_quantity > 0),
    unit_cost numeric(20,6) NOT NULL CHECK (unit_cost >= 0),
    base_unit_cost numeric(20,6) NOT NULL CHECK (base_unit_cost >= 0),
    line_total numeric(20,6) GENERATED ALWAYS AS (round(quantity * unit_cost, 6)) STORED,
    received numeric(20,6) NOT NULL DEFAULT 0 CHECK (received >= 0 AND received <= base_quantity),
    returned numeric(20,6) NOT NULL DEFAULT 0 CHECK (returned >= 0 AND returned <= received),
    expected_date date,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, purchase_order_id, id),
    UNIQUE (tenant_id, purchase_order_id, line_no),
    FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_orders(tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id),
    FOREIGN KEY (tenant_id, unit) REFERENCES units(tenant_id, code));
CREATE INDEX purchase_order_lines_tenant_item ON purchase_order_lines(tenant_id, item_id);

-- Every existing single-item order becomes line 1 in the item's base unit with the same quantity, cost and received
-- quantity (QC rejections had already been subtracted from received).
INSERT INTO purchase_order_lines(id, tenant_id, purchase_order_id, line_no, item_id, quantity, unit, factor,
                                 base_quantity, unit_cost, base_unit_cost, received, returned, created_at)
SELECT gen_random_uuid()::text, po.tenant_id, po.id, 1, po.item_id, po.quantity, i.uom, 1,
       po.quantity, po.unit_cost, po.unit_cost, po.received, 0, po.created_at
FROM purchase_orders po
JOIN items i ON i.tenant_id = po.tenant_id AND i.id = po.item_id;

-- Status: DRAFT used to mean "awaiting independent approval"; that is SUBMITTED now (DRAFT is the editable stage).
ALTER TABLE purchase_orders DROP CONSTRAINT purchase_orders_status_check;
UPDATE purchase_orders
SET total = round(quantity * unit_cost, 6),
    status = CASE WHEN status = 'DRAFT' THEN 'SUBMITTED' ELSE status END,
    submitted_by = created_by,
    submitted_at = created_at,
    updated_at = created_at;
ALTER TABLE purchase_orders
    ADD CONSTRAINT purchase_orders_status_check
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL', 'RECEIVED', 'CLOSED', 'CANCELLED')),
    ALTER COLUMN status SET DEFAULT 'DRAFT';

-- ------------------------------------------------------------------ receipts (one per received line and lot)
ALTER TABLE receipts
    ADD COLUMN purchase_order_line_id text,
    ADD COLUMN number text,
    ADD COLUMN delivery_note text,
    ADD COLUMN entered_quantity numeric(20,6),
    ADD COLUMN entered_unit text,
    ADD COLUMN unit_cost numeric(20,6),
    ADD COLUMN value_amount numeric(20,6),
    ADD COLUMN invoiced numeric(20,6) NOT NULL DEFAULT 0,
    ADD COLUMN returned numeric(20,6) NOT NULL DEFAULT 0,
    ADD COLUMN credited numeric(20,6) NOT NULL DEFAULT 0,
    ADD COLUMN created_by text REFERENCES users(id);
UPDATE receipts r
SET purchase_order_line_id = l.id,
    entered_quantity = r.quantity,
    entered_unit = l.unit
FROM purchase_order_lines l
WHERE l.tenant_id = r.tenant_id AND l.purchase_order_id = r.purchase_order_id;
-- The posted value of each receipt is its GOODS_RECEIPT ledger row (one per receipt lot); lot cost is a fallback.
UPDATE receipts r
SET number = 'GRN-' || upper(substr(replace(r.id, '-', ''), 1, 12)),
    unit_cost = lt.unit_cost,
    value_amount = COALESCE(
        (SELECT e.value_delta FROM stock_ledger e
         WHERE e.tenant_id = r.tenant_id AND e.lot_id = r.lot_id AND e.reason = 'GOODS_RECEIPT' AND e.delta = r.quantity
         ORDER BY e.created_at, e.id
         LIMIT 1),
        round(r.quantity * lt.unit_cost, 6)),
    invoiced = CASE WHEN r.invoice_reference IS NULL THEN 0 ELSE r.quantity END
FROM lots lt
WHERE lt.tenant_id = r.tenant_id AND lt.id = r.lot_id;
ALTER TABLE receipts
    ALTER COLUMN purchase_order_line_id SET NOT NULL,
    ALTER COLUMN number SET NOT NULL,
    ALTER COLUMN entered_quantity SET NOT NULL,
    ALTER COLUMN entered_unit SET NOT NULL,
    ALTER COLUMN unit_cost SET NOT NULL,
    ALTER COLUMN value_amount SET NOT NULL,
    ADD CONSTRAINT receipts_tenant_order_receipt_key UNIQUE (tenant_id, purchase_order_id, id),
    ADD CONSTRAINT receipts_tenant_line_receipt_key UNIQUE (tenant_id, purchase_order_line_id, id),
    ADD CONSTRAINT receipts_purchase_order_line_fk
        FOREIGN KEY (tenant_id, purchase_order_id, purchase_order_line_id)
        REFERENCES purchase_order_lines(tenant_id, purchase_order_id, id),
    ADD CONSTRAINT receipts_entered_unit_fk FOREIGN KEY (tenant_id, entered_unit) REFERENCES units(tenant_id, code),
    ADD CONSTRAINT receipts_amounts_check CHECK (entered_quantity > 0 AND unit_cost >= 0 AND value_amount >= 0),
    -- invoiced: matched on supplier invoices; returned: sent back to the supplier (returns of released stock);
    -- credited: the part of returned that had already been invoiced (settled by debit note against payables).
    ADD CONSTRAINT receipts_quantities_check
        CHECK (invoiced >= 0 AND returned >= 0 AND credited >= 0 AND credited <= invoiced AND credited <= returned
               AND returned <= quantity AND invoiced + returned - credited <= quantity);
CREATE INDEX receipts_tenant_created ON receipts(tenant_id, created_at DESC, id DESC);
CREATE INDEX receipts_tenant_order ON receipts(tenant_id, purchase_order_id, created_at);
CREATE INDEX receipts_tenant_lot ON receipts(tenant_id, lot_id);

-- ------------------------------------------------------------------ supplier invoices (posted history)
-- amount = what the supplier billed (credited to ACCOUNTS_PAYABLE); matched_amount = goods-received clearing (GRNI)
-- released at the receipts' posted cost; price_variance = amount - matched_amount (PURCHASE_PRICE_VARIANCE).
CREATE TABLE supplier_invoices (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    purchase_order_id text NOT NULL,
    supplier_id text NOT NULL,
    supplier_reference text NOT NULL CHECK (length(btrim(supplier_reference)) > 0),
    invoice_date date NOT NULL,
    amount numeric(20,6) NOT NULL CHECK (amount >= 0),
    matched_amount numeric(20,6) NOT NULL CHECK (matched_amount >= 0),
    price_variance numeric(20,6) NOT NULL,
    status text NOT NULL CHECK (status IN ('MATCHED', 'VARIANCE')),
    notes text NOT NULL DEFAULT '',
    created_by text REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (price_variance = amount - matched_amount),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    UNIQUE (tenant_id, purchase_order_id, id),
    FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_orders(tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES partners(tenant_id, id));
CREATE INDEX supplier_invoices_tenant_created ON supplier_invoices(tenant_id, created_at DESC, id DESC);
CREATE INDEX supplier_invoices_tenant_order ON supplier_invoices(tenant_id, purchase_order_id, created_at);
CREATE INDEX supplier_invoices_tenant_reference ON supplier_invoices(tenant_id, supplier_id, supplier_reference);

-- One line per matched receipt. quantity/unit_price are base units as billed; open_quantity is what the receipt still
-- had to invoice before this line (received - invoiced - returned uninvoiced), so quantity_variance <= 0.
CREATE TABLE supplier_invoice_lines (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    invoice_id text NOT NULL,
    purchase_order_id text NOT NULL,
    purchase_order_line_id text NOT NULL,
    receipt_id text NOT NULL,
    line_no integer NOT NULL CHECK (line_no > 0),
    item_id text NOT NULL,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    unit_price numeric(20,6) NOT NULL CHECK (unit_price >= 0),
    amount numeric(20,6) NOT NULL CHECK (amount >= 0),
    po_unit_cost numeric(20,6) NOT NULL CHECK (po_unit_cost >= 0),
    matched_amount numeric(20,6) NOT NULL CHECK (matched_amount >= 0),
    price_variance numeric(20,6) NOT NULL,
    open_quantity numeric(20,6) NOT NULL CHECK (open_quantity > 0),
    quantity_variance numeric(20,6) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (quantity <= open_quantity),
    CHECK (quantity_variance = quantity - open_quantity),
    CHECK (price_variance = amount - matched_amount),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, invoice_id, line_no),
    FOREIGN KEY (tenant_id, purchase_order_id, invoice_id) REFERENCES supplier_invoices(tenant_id, purchase_order_id, id),
    FOREIGN KEY (tenant_id, purchase_order_id, purchase_order_line_id)
        REFERENCES purchase_order_lines(tenant_id, purchase_order_id, id),
    FOREIGN KEY (tenant_id, purchase_order_line_id, receipt_id) REFERENCES receipts(tenant_id, purchase_order_line_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id));
CREATE INDEX supplier_invoice_lines_tenant_receipt ON supplier_invoice_lines(tenant_id, receipt_id);

-- Existing invoice matches (receipts.invoice_reference) become one fully matched invoice per receipt at the PO cost,
-- dated by the matching journal entry.
INSERT INTO supplier_invoices(id, tenant_id, number, purchase_order_id, supplier_id, supplier_reference, invoice_date,
                              amount, matched_amount, price_variance, status, notes, created_by, created_at)
SELECT 'sinv-' || r.id, r.tenant_id, 'SINV-' || upper(substr(replace(r.id, '-', ''), 1, 12)), r.purchase_order_id,
       po.supplier_id, r.invoice_reference, j.posted::date, round(r.quantity * r.unit_cost, 6),
       round(r.quantity * r.unit_cost, 6), 0, 'MATCHED', 'Matched before multi-line invoices (V6 migration)', NULL,
       j.posted
FROM receipts r
JOIN purchase_orders po ON po.tenant_id = r.tenant_id AND po.id = r.purchase_order_id
CROSS JOIN LATERAL (
    SELECT COALESCE(
        (SELECT e.created_at FROM journal_entries e
         WHERE e.tenant_id = r.tenant_id AND e.reference = r.invoice_reference
           AND e.description = 'Three-way matched supplier invoice for ' || po.number
         ORDER BY e.created_at, e.id
         LIMIT 1),
        r.created_at) AS posted) j
WHERE r.invoice_reference IS NOT NULL;
INSERT INTO supplier_invoice_lines(id, tenant_id, invoice_id, purchase_order_id, purchase_order_line_id, receipt_id,
                                   line_no, item_id, quantity, unit_price, amount, po_unit_cost, matched_amount,
                                   price_variance, open_quantity, quantity_variance, created_at)
SELECT 'sinvl-' || r.id, r.tenant_id, 'sinv-' || r.id, r.purchase_order_id, r.purchase_order_line_id, r.id, 1,
       l.item_id, r.quantity, r.unit_cost, round(r.quantity * r.unit_cost, 6), r.unit_cost,
       round(r.quantity * r.unit_cost, 6), 0, r.quantity, 0, i.created_at
FROM receipts r
JOIN purchase_order_lines l ON l.tenant_id = r.tenant_id AND l.id = r.purchase_order_line_id
JOIN supplier_invoices i ON i.tenant_id = r.tenant_id AND i.id = 'sinv-' || r.id
WHERE r.invoice_reference IS NOT NULL;

-- ------------------------------------------------------------------ supplier returns (posted history)
-- Released stock of a receipt lot sent back to the supplier. value_amount is the stock value removed by the ledger;
-- the part not yet invoiced reverses goods-received clearing (clearing_*, like an incoming QC rejection), the part
-- already invoiced is settled against accounts payable by the debit note (payable_*).
CREATE TABLE supplier_returns (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    purchase_order_id text NOT NULL,
    purchase_order_line_id text NOT NULL,
    receipt_id text NOT NULL,
    lot_id text NOT NULL,
    supplier_id text NOT NULL,
    item_id text NOT NULL,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    value_amount numeric(20,6) NOT NULL CHECK (value_amount >= 0),
    clearing_quantity numeric(20,6) NOT NULL CHECK (clearing_quantity >= 0),
    clearing_amount numeric(20,6) NOT NULL CHECK (clearing_amount >= 0),
    payable_quantity numeric(20,6) NOT NULL CHECK (payable_quantity >= 0),
    payable_amount numeric(20,6) NOT NULL CHECK (payable_amount >= 0),
    debit_note text NOT NULL CHECK (length(btrim(debit_note)) > 0),
    reason text NOT NULL CHECK (length(btrim(reason)) > 0),
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (clearing_quantity + payable_quantity = quantity),
    CHECK (clearing_amount + payable_amount = value_amount),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    FOREIGN KEY (tenant_id, purchase_order_id, purchase_order_line_id)
        REFERENCES purchase_order_lines(tenant_id, purchase_order_id, id),
    FOREIGN KEY (tenant_id, purchase_order_line_id, receipt_id) REFERENCES receipts(tenant_id, purchase_order_line_id, id),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES partners(tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id));
CREATE INDEX supplier_returns_tenant_created ON supplier_returns(tenant_id, created_at DESC, id DESC);
CREATE INDEX supplier_returns_tenant_order ON supplier_returns(tenant_id, purchase_order_id, created_at);
CREATE INDEX supplier_returns_tenant_receipt ON supplier_returns(tenant_id, receipt_id);

CREATE TRIGGER supplier_invoices_immutable BEFORE UPDATE OR DELETE ON supplier_invoices
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER supplier_invoice_lines_immutable BEFORE UPDATE OR DELETE ON supplier_invoice_lines
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER supplier_returns_immutable BEFORE UPDATE OR DELETE ON supplier_returns
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- ------------------------------------------------------------------ header: drop the single-item columns
-- Their values now live on line 1 (item, quantity, unit cost, received).
ALTER TABLE purchase_orders
    DROP COLUMN item_id,
    DROP COLUMN quantity,
    DROP COLUMN unit_cost,
    DROP COLUMN received;

-- A draft created from planning recommendations may not have a supplier yet; it needs one before submission.
ALTER TABLE purchase_orders
    ALTER COLUMN supplier_id DROP NOT NULL,
    ADD CONSTRAINT purchase_orders_supplier_required CHECK (supplier_id IS NOT NULL OR status IN ('DRAFT', 'CANCELLED'));
-- V3's partner_must_be_active() now runs on insert with a supplier and whenever a draft's supplier changes.
DROP TRIGGER purchase_partner_active ON purchase_orders;
CREATE TRIGGER purchase_partner_active BEFORE INSERT ON purchase_orders
    FOR EACH ROW WHEN (NEW.supplier_id IS NOT NULL) EXECUTE FUNCTION partner_must_be_active();
CREATE TRIGGER purchase_partner_changed BEFORE UPDATE OF supplier_id ON purchase_orders
    FOR EACH ROW WHEN (NEW.supplier_id IS NOT NULL AND NEW.supplier_id IS DISTINCT FROM OLD.supplier_id)
    EXECUTE FUNCTION partner_must_be_active();

CREATE INDEX purchase_orders_tenant_created ON purchase_orders(tenant_id, created_at DESC, id DESC);
CREATE INDEX purchase_orders_tenant_status ON purchase_orders(tenant_id, status, created_at DESC);
CREATE INDEX purchase_orders_tenant_supplier ON purchase_orders(tenant_id, supplier_id);
