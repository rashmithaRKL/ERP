-- Inventory operations: warehouse master data, stock adjustments with threshold approval, warehouse transfers and
-- cycle counts. Stock still changes only through stock_ledger (apply_stock_balance); these tables are the documents
-- that explain each movement.

-- ---------------------------------------------------------------- warehouses: code, kind, active flag
ALTER TABLE warehouses ADD COLUMN code text;
ALTER TABLE warehouses ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE warehouses ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
WITH numbered AS (
    SELECT tenant_id, id, kind, row_number() OVER (PARTITION BY tenant_id, kind ORDER BY id) AS n FROM warehouses)
UPDATE warehouses w
SET code = (CASE numbered.kind WHEN 'RAW' THEN 'RM' WHEN 'FINISHED' THEN 'FG' ELSE upper(left(numbered.kind, 6)) END)
           || CASE WHEN numbered.n > 1 THEN '-' || numbered.n ELSE '' END
FROM numbered
WHERE numbered.tenant_id = w.tenant_id AND numbered.id = w.id;

-- Inserts that do not name a code (tenant bootstrap, test fixtures) get RM / FG / RM-2 ... automatically.
CREATE FUNCTION default_warehouse_code() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE base text; candidate text; n integer := 1;
BEGIN
  IF NEW.code IS NOT NULL THEN RETURN NEW; END IF;
  base := CASE NEW.kind WHEN 'RAW' THEN 'RM' WHEN 'FINISHED' THEN 'FG' ELSE upper(left(NEW.kind, 6)) END;
  candidate := base;
  WHILE EXISTS (SELECT 1 FROM warehouses WHERE tenant_id = NEW.tenant_id AND code = candidate) LOOP
    n := n + 1;
    candidate := base || '-' || n;
  END LOOP;
  NEW.code := candidate;
  RETURN NEW;
END; $$;
CREATE TRIGGER warehouse_default_code BEFORE INSERT ON warehouses FOR EACH ROW EXECUTE FUNCTION default_warehouse_code();

ALTER TABLE warehouses ALTER COLUMN code SET NOT NULL;
ALTER TABLE warehouses ADD CONSTRAINT warehouses_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,19}$');
ALTER TABLE warehouses ADD CONSTRAINT warehouses_tenant_code UNIQUE (tenant_id, code);
-- NOT VALID: enforced for new and changed rows without failing on legacy data.
ALTER TABLE warehouses ADD CONSTRAINT warehouses_kind CHECK (kind IN ('RAW', 'FINISHED', 'WIP', 'QUARANTINE', 'OTHER')) NOT VALID;

-- An inactive warehouse holds no stock (deactivation requires it to be empty), so it may not receive any: no new
-- lots and no positive ledger movements. This also covers receipts and production output posted by other domains.
CREATE FUNCTION require_active_warehouse() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME = 'stock_ledger' THEN
    -- Nested: NEW.delta exists only on stock_ledger rows.
    IF NEW.delta < 0 THEN RETURN NEW; END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM warehouses WHERE tenant_id = NEW.tenant_id AND id = NEW.warehouse_id AND NOT active) THEN
    RAISE EXCEPTION 'Warehouse is inactive' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER lot_requires_active_warehouse BEFORE INSERT ON lots FOR EACH ROW EXECUTE FUNCTION require_active_warehouse();
CREATE TRIGGER stock_in_requires_active_warehouse BEFORE INSERT ON stock_ledger FOR EACH ROW EXECUTE FUNCTION require_active_warehouse();

-- A quarantined lot moved by a transfer keeps the source lot's pending inspection: when that lot's quality decision is
-- recorded, every still-quarantined lot transferred from it (source_type TRANSFER, source_id = source lot) follows.
-- The update cascades through chains of transfers because it fires this trigger again.
CREATE FUNCTION follow_transfer_source_quality() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'QUARANTINE' AND NEW.status <> 'QUARANTINE' THEN
    UPDATE lots SET status = NEW.status
    WHERE tenant_id = NEW.tenant_id AND source_type = 'TRANSFER' AND source_id = NEW.id AND status = 'QUARANTINE';
  END IF;
  RETURN NULL;
END; $$;
CREATE TRIGGER transferred_lots_follow_quality AFTER UPDATE OF status ON lots FOR EACH ROW EXECUTE FUNCTION follow_transfer_source_quality();

-- ---------------------------------------------------------------- settings
CREATE TABLE inventory_settings (
    tenant_id text PRIMARY KEY REFERENCES tenants(id),
    approval_threshold numeric(20,6) NOT NULL DEFAULT 50000 CHECK (approval_threshold >= 0),
    updated_by text REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT now());
INSERT INTO inventory_settings(tenant_id) SELECT id FROM tenants;

-- ---------------------------------------------------------------- stock adjustments
CREATE TABLE stock_adjustments (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    reason text NOT NULL CHECK (reason IN ('DAMAGE', 'EXPIRY', 'FOUND', 'COUNT_VARIANCE', 'SAMPLE', 'OTHER')),
    notes text NOT NULL DEFAULT '',
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVAL_REQUIRED', 'POSTED', 'CANCELLED')),
    value_increase numeric(20,6) NOT NULL DEFAULT 0 CHECK (value_increase >= 0),
    value_decrease numeric(20,6) NOT NULL DEFAULT 0 CHECK (value_decrease >= 0),
    approval_threshold numeric(20,6) CHECK (approval_threshold >= 0),
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    submitted_by text REFERENCES users(id),
    submitted_at timestamptz,
    approved_by text REFERENCES users(id),
    posted_at timestamptz,
    cancelled_by text REFERENCES users(id),
    cancelled_at timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number));
CREATE TABLE stock_adjustment_lines (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    adjustment_id text NOT NULL,
    line_no integer NOT NULL CHECK (line_no > 0),
    kind text NOT NULL CHECK (kind IN ('INCREASE', 'DECREASE', 'FOUND')),
    lot_id text,
    item_id text NOT NULL,
    warehouse_id text NOT NULL,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    unit_cost numeric(20,6) NOT NULL CHECK (unit_cost >= 0),
    lot_number text,
    value_delta numeric(20,6),
    UNIQUE (tenant_id, adjustment_id, line_no),
    CHECK (kind = 'FOUND' OR lot_id IS NOT NULL),
    FOREIGN KEY (tenant_id, adjustment_id) REFERENCES stock_adjustments(tenant_id, id),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses(tenant_id, id));

-- ---------------------------------------------------------------- warehouse transfers
CREATE TABLE stock_transfers (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    from_warehouse_id text NOT NULL,
    to_warehouse_id text NOT NULL,
    notes text NOT NULL DEFAULT '',
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
    total_value numeric(20,6) NOT NULL DEFAULT 0 CHECK (total_value >= 0),
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    posted_by text REFERENCES users(id),
    posted_at timestamptz,
    cancelled_by text REFERENCES users(id),
    cancelled_at timestamptz,
    CHECK (from_warehouse_id <> to_warehouse_id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    FOREIGN KEY (tenant_id, from_warehouse_id) REFERENCES warehouses(tenant_id, id),
    FOREIGN KEY (tenant_id, to_warehouse_id) REFERENCES warehouses(tenant_id, id));
CREATE TABLE stock_transfer_lines (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    transfer_id text NOT NULL,
    line_no integer NOT NULL CHECK (line_no > 0),
    source_lot_id text NOT NULL,
    item_id text NOT NULL,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    destination_lot_id text,
    value_amount numeric(20,6) CHECK (value_amount >= 0),
    UNIQUE (tenant_id, transfer_id, line_no),
    UNIQUE (tenant_id, transfer_id, source_lot_id),
    FOREIGN KEY (tenant_id, transfer_id) REFERENCES stock_transfers(tenant_id, id),
    FOREIGN KEY (tenant_id, source_lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, destination_lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id));

-- ---------------------------------------------------------------- cycle counts
CREATE TABLE stock_counts (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    warehouse_id text NOT NULL,
    item_id text,
    item_type text,
    blind boolean NOT NULL DEFAULT false,
    notes text NOT NULL DEFAULT '',
    status text NOT NULL DEFAULT 'COUNTING' CHECK (status IN ('COUNTING', 'REVIEW', 'POSTED', 'CANCELLED')),
    adjustment_id text,
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    submitted_by text REFERENCES users(id),
    submitted_at timestamptz,
    posted_by text REFERENCES users(id),
    posted_at timestamptz,
    cancelled_by text REFERENCES users(id),
    cancelled_at timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses(tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id),
    FOREIGN KEY (tenant_id, adjustment_id) REFERENCES stock_adjustments(tenant_id, id));
CREATE TABLE stock_count_lines (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    count_id text NOT NULL,
    line_no integer NOT NULL CHECK (line_no > 0),
    lot_id text NOT NULL,
    item_id text NOT NULL,
    snapshot_qty numeric(20,6) NOT NULL CHECK (snapshot_qty >= 0),
    unit_cost numeric(20,6) NOT NULL CHECK (unit_cost >= 0),
    counted_qty numeric(20,6) CHECK (counted_qty >= 0),
    counted_by text REFERENCES users(id),
    counted_at timestamptz,
    system_qty numeric(20,6) CHECK (system_qty >= 0),
    variance_qty numeric(20,6),
    variance_value numeric(20,6),
    moved boolean NOT NULL DEFAULT false,
    open boolean NOT NULL DEFAULT true,
    UNIQUE (tenant_id, count_id, line_no),
    UNIQUE (tenant_id, count_id, lot_id),
    FOREIGN KEY (tenant_id, count_id) REFERENCES stock_counts(tenant_id, id),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id));
-- A lot can be in at most one open (COUNTING or REVIEW) count.
CREATE UNIQUE INDEX stock_count_lines_one_open_count ON stock_count_lines(tenant_id, lot_id) WHERE open;

-- ---------------------------------------------------------------- posted and cancelled documents are immutable
CREATE FUNCTION protect_closed_inventory_document() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('POSTED', 'CANCELLED') THEN
    RAISE EXCEPTION 'Posted and cancelled inventory documents are immutable' USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER stock_adjustment_immutable BEFORE UPDATE OR DELETE ON stock_adjustments FOR EACH ROW EXECUTE FUNCTION protect_closed_inventory_document();
CREATE TRIGGER stock_transfer_immutable BEFORE UPDATE OR DELETE ON stock_transfers FOR EACH ROW EXECUTE FUNCTION protect_closed_inventory_document();
CREATE TRIGGER stock_count_immutable BEFORE UPDATE OR DELETE ON stock_counts FOR EACH ROW EXECUTE FUNCTION protect_closed_inventory_document();

CREATE FUNCTION protect_closed_adjustment_line() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') AND EXISTS (SELECT 1 FROM stock_adjustments
      WHERE tenant_id = OLD.tenant_id AND id = OLD.adjustment_id AND status IN ('POSTED', 'CANCELLED')) THEN
    RAISE EXCEPTION 'Lines of posted and cancelled inventory documents are immutable' USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') AND EXISTS (SELECT 1 FROM stock_adjustments
      WHERE tenant_id = NEW.tenant_id AND id = NEW.adjustment_id AND status IN ('POSTED', 'CANCELLED')) THEN
    RAISE EXCEPTION 'Lines of posted and cancelled inventory documents are immutable' USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER stock_adjustment_line_immutable BEFORE INSERT OR UPDATE OR DELETE ON stock_adjustment_lines FOR EACH ROW EXECUTE FUNCTION protect_closed_adjustment_line();

CREATE FUNCTION protect_closed_transfer_line() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') AND EXISTS (SELECT 1 FROM stock_transfers
      WHERE tenant_id = OLD.tenant_id AND id = OLD.transfer_id AND status IN ('POSTED', 'CANCELLED')) THEN
    RAISE EXCEPTION 'Lines of posted and cancelled inventory documents are immutable' USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') AND EXISTS (SELECT 1 FROM stock_transfers
      WHERE tenant_id = NEW.tenant_id AND id = NEW.transfer_id AND status IN ('POSTED', 'CANCELLED')) THEN
    RAISE EXCEPTION 'Lines of posted and cancelled inventory documents are immutable' USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER stock_transfer_line_immutable BEFORE INSERT OR UPDATE OR DELETE ON stock_transfer_lines FOR EACH ROW EXECUTE FUNCTION protect_closed_transfer_line();

CREATE FUNCTION protect_closed_count_line() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') AND EXISTS (SELECT 1 FROM stock_counts
      WHERE tenant_id = OLD.tenant_id AND id = OLD.count_id AND status IN ('POSTED', 'CANCELLED')) THEN
    RAISE EXCEPTION 'Lines of posted and cancelled inventory documents are immutable' USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') AND EXISTS (SELECT 1 FROM stock_counts
      WHERE tenant_id = NEW.tenant_id AND id = NEW.count_id AND status IN ('POSTED', 'CANCELLED')) THEN
    RAISE EXCEPTION 'Lines of posted and cancelled inventory documents are immutable' USING ERRCODE = 'check_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER stock_count_line_immutable BEFORE INSERT OR UPDATE OR DELETE ON stock_count_lines FOR EACH ROW EXECUTE FUNCTION protect_closed_count_line();

-- ---------------------------------------------------------------- read-model indexes
CREATE INDEX lots_tenant_created_id ON lots(tenant_id, created_at DESC, id DESC);
CREATE INDEX lots_tenant_warehouse_status ON lots(tenant_id, warehouse_id, status);
CREATE INDEX lots_tenant_source ON lots(tenant_id, source_type, source_id);
CREATE INDEX stock_ledger_tenant_lot ON stock_ledger(tenant_id, lot_id, created_at DESC);
CREATE INDEX stock_ledger_tenant_item ON stock_ledger(tenant_id, item_id, created_at DESC);
CREATE INDEX stock_ledger_tenant_warehouse ON stock_ledger(tenant_id, warehouse_id, created_at DESC);
CREATE INDEX stock_ledger_tenant_reference ON stock_ledger(tenant_id, reference);
CREATE INDEX stock_adjustments_tenant_created ON stock_adjustments(tenant_id, created_at DESC, id DESC);
CREATE INDEX stock_adjustments_tenant_status ON stock_adjustments(tenant_id, status, created_at DESC);
CREATE INDEX stock_adjustment_lines_tenant_lot ON stock_adjustment_lines(tenant_id, lot_id);
CREATE INDEX stock_adjustment_lines_tenant_adjustment ON stock_adjustment_lines(tenant_id, adjustment_id, line_no);
CREATE INDEX stock_transfers_tenant_created ON stock_transfers(tenant_id, created_at DESC, id DESC);
CREATE INDEX stock_transfers_tenant_status ON stock_transfers(tenant_id, status, created_at DESC);
CREATE INDEX stock_transfer_lines_tenant_transfer ON stock_transfer_lines(tenant_id, transfer_id, line_no);
CREATE INDEX stock_transfer_lines_tenant_lot ON stock_transfer_lines(tenant_id, source_lot_id);
CREATE INDEX stock_counts_tenant_created ON stock_counts(tenant_id, created_at DESC, id DESC);
CREATE INDEX stock_counts_tenant_warehouse_status ON stock_counts(tenant_id, warehouse_id, status);
CREATE INDEX stock_count_lines_tenant_count ON stock_count_lines(tenant_id, count_id, line_no);
