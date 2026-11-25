-- V14: genealogy explorer and recalls.
--
-- 1. Indexes so the trace engine (traceability.TraceService) can walk every lot-to-lot and lot-to-document link in both
--    directions with index lookups.
-- 2. lot_genealogy_links: one row per material-flow link from a lot to a lot (the recursive CTEs walk this view).
-- 3. Recalls (RCL-...): suspects, the affected-lot and shipment snapshot, customer notifications; containment raises
--    RECALL nonconformance reports (quality.NcrService), it never sets a lot status itself.

-- ---------------------------------------------------------------- genealogy indexes
-- Material issued to manufacturing orders, by lot (forward from a lot to the orders that consumed it).
CREATE INDEX trace_reservations_issued_lot ON reservations(tenant_id, lot_id) WHERE status = 'ISSUED';
-- Deliveries by lot (forward to customers) and by delivery reference (entry point).
CREATE INDEX trace_deliveries_lot ON deliveries(tenant_id, lot_id);
CREATE INDEX trace_deliveries_reference ON deliveries(tenant_id, reference);
-- Transfers backwards (destination lot -> line); forwards uses stock_transfer_lines_tenant_lot (V5).
CREATE INDEX trace_transfer_lines_destination ON stock_transfer_lines(tenant_id, destination_lot_id)
    WHERE destination_lot_id IS NOT NULL;
-- A customer-return lot back to its return document (lots.source_id also points at it; this serves the lot side).
CREATE INDEX trace_customer_returns_lot ON customer_returns(tenant_id, lot_id);
-- Rework input and extra material by lot (open rework orders have no rework_lot_links yet).
CREATE INDEX trace_rework_orders_input ON rework_orders(tenant_id, input_lot_id);
CREATE INDEX trace_rework_materials_lot ON rework_materials(tenant_id, lot_id);
-- Terminal outflows by lot.
CREATE INDEX trace_supplier_returns_lot ON supplier_returns(tenant_id, lot_id);
CREATE INDEX trace_part_movements_lot ON maintenance_part_movements(tenant_id, lot_id);
-- Goods-receipt (GRN) number entry point.
CREATE INDEX trace_receipts_number ON receipts(tenant_id, number);

-- ---------------------------------------------------------------- lot-to-lot links
-- Material flow from one lot into another (from = consumed/source lot, to = the lot it went into). Every link points at
-- a lot created by the linking event, so the posted data is a DAG; the trace engine is cycle-safe regardless.
--   MANUFACTURING   a lot issued to a manufacturing order (reservation ISSUED) -> every output lot of that order
--   TRANSFER        a posted transfer line: source lot -> destination lot (same item, a new lot)
--   REWORK          a completed rework order: reworked lot and extra material -> output lot (rework_lot_links)
--   CUSTOMER_RETURN a delivered lot -> the quarantine lot of a customer return of that delivery
CREATE VIEW lot_genealogy_links AS
SELECT r.tenant_id, r.lot_id AS from_lot_id, o.id AS to_lot_id, 'MANUFACTURING'::text AS via, r.order_id AS via_id
FROM reservations r
JOIN lots o ON o.tenant_id = r.tenant_id AND o.source_type = 'MANUFACTURING' AND o.source_id = r.order_id
WHERE r.status = 'ISSUED'
UNION ALL
SELECT tl.tenant_id, tl.source_lot_id, tl.destination_lot_id, 'TRANSFER'::text, tl.transfer_id
FROM stock_transfer_lines tl
WHERE tl.destination_lot_id IS NOT NULL
UNION ALL
SELECT k.tenant_id, k.input_lot_id, k.output_lot_id, 'REWORK'::text, k.rework_order_id
FROM rework_lot_links k
UNION ALL
SELECT cr.tenant_id, d.lot_id, cr.lot_id, 'CUSTOMER_RETURN'::text, cr.id
FROM customer_returns cr
JOIN deliveries d ON d.tenant_id = cr.tenant_id AND d.id = cr.delivery_id;

-- ---------------------------------------------------------------- recalls
CREATE TABLE recalls (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    title text NOT NULL CHECK (length(btrim(title)) > 0),
    reason text NOT NULL CHECK (length(btrim(reason)) > 0),
    severity text NOT NULL CHECK (severity IN ('MINOR', 'MAJOR', 'CRITICAL')),
    -- FORWARD: the suspect lots and everything made from them. FORWARD_AND_SIBLINGS: additionally every lot made from
    -- the origin lots (purchased, opening or found stock) the suspect lots were made from.
    scope text NOT NULL CHECK (scope IN ('FORWARD', 'FORWARD_AND_SIBLINGS')),
    -- LOTS: chosen suspect lots. SUPPLIER: every lot received from supplier_id between received_from and received_to
    -- (Asia/Colombo dates), optionally of one item.
    basis text NOT NULL CHECK (basis IN ('LOTS', 'SUPPLIER')),
    defect_code text NOT NULL,
    supplier_id text,
    item_id text,
    received_from date,
    received_to date,
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CONTAINED', 'CLOSED', 'CANCELLED')),
    trace_depth integer NOT NULL CHECK (trace_depth BETWEEN 1 AND 50),
    affected_lots integer NOT NULL DEFAULT 0 CHECK (affected_lots >= 0),
    in_stock_lots integer NOT NULL DEFAULT 0 CHECK (in_stock_lots >= 0),
    held_lots integer NOT NULL DEFAULT 0 CHECK (held_lots >= 0),
    shipments integer NOT NULL DEFAULT 0 CHECK (shipments >= 0),
    customers integer NOT NULL DEFAULT 0 CHECK (customers >= 0),
    truncated boolean NOT NULL DEFAULT false,
    computed_at timestamptz NOT NULL DEFAULT now(),
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    contained_by text,
    contained_at timestamptz,
    closed_by text,
    closed_at timestamptz,
    close_note text NOT NULL DEFAULT '',
    override_reason text,
    cancelled_by text,
    cancelled_at timestamptz,
    cancel_reason text,
    CHECK ((basis = 'SUPPLIER') = (supplier_id IS NOT NULL)),
    CHECK (basis = 'SUPPLIER' OR (item_id IS NULL AND received_from IS NULL AND received_to IS NULL)),
    CHECK (basis <> 'SUPPLIER' OR (received_from IS NOT NULL AND received_to IS NOT NULL AND received_from <= received_to)),
    CHECK ((status IN ('CONTAINED', 'CLOSED')) = (contained_at IS NOT NULL AND contained_by IS NOT NULL)),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL AND closed_by IS NOT NULL)),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL)),
    -- An override closes a recall with customers still to notify or held lots not dispositioned: only a different
    -- person than the one who opened it, and always with a reason.
    CHECK (override_reason IS NULL OR (status = 'CLOSED' AND length(btrim(override_reason)) > 0 AND closed_by <> created_by)),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    FOREIGN KEY (tenant_id, defect_code) REFERENCES defect_codes(tenant_id, code),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES partners(tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id),
    FOREIGN KEY (tenant_id, contained_by) REFERENCES users(tenant_id, id),
    FOREIGN KEY (tenant_id, closed_by) REFERENCES users(tenant_id, id),
    FOREIGN KEY (tenant_id, cancelled_by) REFERENCES users(tenant_id, id));
CREATE INDEX recalls_tenant_created ON recalls(tenant_id, created_at DESC, id DESC);
CREATE INDEX recalls_tenant_status ON recalls(tenant_id, status, created_at DESC);

-- The suspect lots a recall starts from (chosen, or resolved from the supplier receipts).
CREATE TABLE recall_suspects (
    tenant_id text NOT NULL REFERENCES tenants(id),
    recall_id text NOT NULL,
    lot_id text NOT NULL,
    PRIMARY KEY (tenant_id, recall_id, lot_id),
    FOREIGN KEY (tenant_id, recall_id) REFERENCES recalls(tenant_id, id),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lots(tenant_id, id));
CREATE INDEX recall_suspects_lot ON recall_suspects(tenant_id, lot_id);

-- Affected-lot snapshot: every lot of the recall's trace with its stock at the last computation (open, refresh,
-- containment). containment is set for the lots that had stock when the recall was contained.
CREATE TABLE recall_lots (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    recall_id text NOT NULL,
    lot_id text NOT NULL,
    item_id text NOT NULL,
    warehouse_id text NOT NULL,
    relation text NOT NULL CHECK (relation IN ('SUSPECT', 'SOURCE', 'DOWNSTREAM', 'SIBLING')),
    depth integer NOT NULL CHECK (depth >= 0),
    quantity numeric(20,6) NOT NULL CHECK (quantity >= 0),
    reserved numeric(20,6) NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    value_amount numeric(20,6) NOT NULL DEFAULT 0 CHECK (value_amount >= 0),
    uom text NOT NULL,
    lot_status text NOT NULL CHECK (lot_status IN ('QUARANTINE', 'RELEASED', 'REJECTED')),
    -- HELD: put on hold by this recall's RECALL NCR (ncr_id); ALREADY_HELD: quarantined or rejected at containment;
    -- UNDER_NCR: released but already under an open NCR (ncr_id = that NCR); AWAITING_INSPECTION: an inspection was
    -- pending.
    containment text CHECK (containment IN ('HELD', 'ALREADY_HELD', 'UNDER_NCR', 'AWAITING_INSPECTION')),
    ncr_id text,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, recall_id, lot_id),
    CHECK (containment IS NULL OR quantity > 0),
    CHECK ((COALESCE(containment, '') IN ('HELD', 'UNDER_NCR')) = (ncr_id IS NOT NULL)),
    FOREIGN KEY (tenant_id, recall_id) REFERENCES recalls(tenant_id, id),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses(tenant_id, id),
    FOREIGN KEY (tenant_id, ncr_id) REFERENCES ncrs(tenant_id, id));
CREATE INDEX recall_lots_recall ON recall_lots(tenant_id, recall_id, depth);
CREATE INDEX recall_lots_lot ON recall_lots(tenant_id, lot_id);
CREATE INDEX recall_lots_ncr ON recall_lots(tenant_id, ncr_id) WHERE ncr_id IS NOT NULL;

-- Shipment snapshot: every delivery of an affected lot (customer, order, delivery reference, quantities).
CREATE TABLE recall_shipments (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    recall_id text NOT NULL,
    delivery_id text NOT NULL,
    sales_order_id text NOT NULL,
    customer_id text NOT NULL,
    lot_id text NOT NULL,
    item_id text NOT NULL,
    reference text NOT NULL,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    returned numeric(20,6) NOT NULL DEFAULT 0 CHECK (returned >= 0 AND returned <= quantity),
    delivered_at timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, recall_id, delivery_id),
    FOREIGN KEY (tenant_id, recall_id) REFERENCES recalls(tenant_id, id),
    FOREIGN KEY (tenant_id, delivery_id) REFERENCES deliveries(tenant_id, id),
    FOREIGN KEY (tenant_id, sales_order_id) REFERENCES sales_orders(tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES partners(tenant_id, id),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id));
CREATE INDEX recall_shipments_recall ON recall_shipments(tenant_id, recall_id, customer_id);

-- Customer notification tracking: one row per affected customer, PENDING -> NOTIFIED (date, contact, notes).
CREATE TABLE recall_customers (
    tenant_id text NOT NULL REFERENCES tenants(id),
    recall_id text NOT NULL,
    customer_id text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'NOTIFIED')),
    notified_on date,
    contact text,
    notes text NOT NULL DEFAULT '',
    notified_by text,
    recorded_at timestamptz,
    PRIMARY KEY (tenant_id, recall_id, customer_id),
    CHECK ((status = 'NOTIFIED') = (notified_on IS NOT NULL AND notified_by IS NOT NULL AND recorded_at IS NOT NULL
        AND contact IS NOT NULL AND length(btrim(contact)) > 0)),
    FOREIGN KEY (tenant_id, recall_id) REFERENCES recalls(tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES partners(tenant_id, id),
    FOREIGN KEY (tenant_id, notified_by) REFERENCES users(tenant_id, id));
CREATE INDEX recall_customers_pending ON recall_customers(tenant_id, status);

-- ---------------------------------------------------------------- immutability
-- A recall is never deleted. Once contained, everything that defined its scope and affected set is frozen: only the
-- transition to CLOSED (with its closing fields) is possible. Closed and cancelled recalls are immutable.
CREATE FUNCTION protect_recall() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Recall % is a permanent record and cannot be deleted', OLD.number;
    END IF;
    IF OLD.status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'Recall % is %; its record is immutable', OLD.number, lower(OLD.status);
    END IF;
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id THEN
        RAISE EXCEPTION 'A recall keeps its identity';
    END IF;
    IF OLD.status = 'CONTAINED' AND (
            NEW.status NOT IN ('CONTAINED', 'CLOSED')
            OR (NEW.number, NEW.title, NEW.reason, NEW.severity, NEW.scope, NEW.basis, NEW.defect_code,
                NEW.supplier_id, NEW.item_id, NEW.received_from, NEW.received_to, NEW.trace_depth,
                NEW.affected_lots, NEW.in_stock_lots, NEW.held_lots, NEW.shipments, NEW.customers, NEW.truncated,
                NEW.computed_at, NEW.created_by, NEW.created_at, NEW.contained_by, NEW.contained_at)
               IS DISTINCT FROM
               (OLD.number, OLD.title, OLD.reason, OLD.severity, OLD.scope, OLD.basis, OLD.defect_code,
                OLD.supplier_id, OLD.item_id, OLD.received_from, OLD.received_to, OLD.trace_depth,
                OLD.affected_lots, OLD.in_stock_lots, OLD.held_lots, OLD.shipments, OLD.customers, OLD.truncated,
                OLD.computed_at, OLD.created_by, OLD.created_at, OLD.contained_by, OLD.contained_at)) THEN
        RAISE EXCEPTION 'Recall % is contained; its scope and affected items are immutable history', OLD.number;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recall_guard BEFORE UPDATE OR DELETE ON recalls
    FOR EACH ROW EXECUTE FUNCTION protect_recall();

-- Suspects, affected lots and shipments change only while the recall is OPEN (recomputation).
CREATE FUNCTION protect_recall_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_recall text := CASE WHEN TG_OP = 'DELETE' THEN OLD.recall_id ELSE NEW.recall_id END;
    v_tenant text := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    v_status text;
    v_number text;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.recall_id <> OLD.recall_id OR NEW.tenant_id <> OLD.tenant_id) THEN
        RAISE EXCEPTION 'A recall snapshot row cannot move to another recall';
    END IF;
    SELECT status, number INTO v_status, v_number FROM recalls WHERE tenant_id = v_tenant AND id = v_recall;
    IF v_status IS DISTINCT FROM 'OPEN' THEN
        RAISE EXCEPTION 'Recall % is %; its affected-item snapshot is immutable history', v_number, lower(v_status);
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER recall_suspects_immutable BEFORE INSERT OR UPDATE OR DELETE ON recall_suspects
    FOR EACH ROW EXECUTE FUNCTION protect_recall_snapshot();
CREATE TRIGGER recall_lots_immutable BEFORE INSERT OR UPDATE OR DELETE ON recall_lots
    FOR EACH ROW EXECUTE FUNCTION protect_recall_snapshot();
CREATE TRIGGER recall_shipments_immutable BEFORE INSERT OR UPDATE OR DELETE ON recall_shipments
    FOR EACH ROW EXECUTE FUNCTION protect_recall_snapshot();

-- Customers are added or removed only while the recall is OPEN; a notification (PENDING -> NOTIFIED) is recorded once
-- and never changed; nothing changes once the recall is closed or cancelled.
CREATE FUNCTION protect_recall_customer() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_recall text := CASE WHEN TG_OP = 'DELETE' THEN OLD.recall_id ELSE NEW.recall_id END;
    v_tenant text := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    v_status text;
    v_number text;
BEGIN
    SELECT status, number INTO v_status, v_number FROM recalls WHERE tenant_id = v_tenant AND id = v_recall;
    IF TG_OP IN ('INSERT', 'DELETE') THEN
        IF v_status IS DISTINCT FROM 'OPEN' THEN
            RAISE EXCEPTION 'Recall % is %; its customer list is immutable history', v_number, lower(v_status);
        END IF;
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF NEW.recall_id <> OLD.recall_id OR NEW.tenant_id <> OLD.tenant_id OR NEW.customer_id <> OLD.customer_id THEN
        RAISE EXCEPTION 'A recall customer row keeps its recall and customer';
    END IF;
    IF v_status NOT IN ('OPEN', 'CONTAINED') THEN
        RAISE EXCEPTION 'Recall % is %; its notifications are immutable history', v_number, lower(v_status);
    END IF;
    IF OLD.status = 'NOTIFIED' THEN
        RAISE EXCEPTION 'The notification of this customer for recall % is already recorded', v_number;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recall_customers_guard BEFORE INSERT OR UPDATE OR DELETE ON recall_customers
    FOR EACH ROW EXECUTE FUNCTION protect_recall_customer();
