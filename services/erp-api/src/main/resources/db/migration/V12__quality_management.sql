-- V12: quality management. Inspection plans with characteristics and the measurements recorded against them,
-- a configurable defect-code catalogue, nonconformance reports (NCR) with dispositions (use as is / scrap / return to
-- supplier / rework), rework orders with explicit input → output lot links (genealogy), and CAPA with actions.
-- Stock and money still move only through the stock ledger and balanced journals (posted by the quality services).

-- ------------------------------------------------------------------ defect codes (per tenant, configurable)
CREATE TABLE defect_codes (
    tenant_id text NOT NULL REFERENCES tenants(id),
    code text NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    description text NOT NULL DEFAULT '',
    active boolean NOT NULL DEFAULT true,
    -- System codes are used by automatic NCRs and cannot be deactivated.
    system boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, code));

CREATE FUNCTION seed_default_defect_codes(p_tenant text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO defect_codes(tenant_id,code,name,description,system) VALUES
    (p_tenant,'INSP','Failed inspection','Inspection failed without a more specific defect classification',true),
    (p_tenant,'DIM','Dimensional','Dimension, wall thickness or weight outside its tolerance',false),
    (p_tenant,'VIS','Visual / cosmetic','Surface, colour, flash or other appearance defect',false),
    (p_tenant,'LEAK','Leak / seal','Leak, seal or closure integrity failure',false),
    (p_tenant,'CONT','Contamination','Foreign matter, dirt or cross-contamination',false),
    (p_tenant,'MAT','Material property','Raw material property outside its specification',false),
    (p_tenant,'PACK','Packaging / labelling','Damaged packaging, wrong or missing labels',false),
    (p_tenant,'DOC','Documentation','Missing or incorrect certificate, record or traceability data',false),
    (p_tenant,'OTHER','Other','Any other nonconformance',false)
  ON CONFLICT DO NOTHING;
END; $$;
CREATE FUNCTION seed_tenant_defect_codes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM seed_default_defect_codes(NEW.id);
  RETURN NULL;
END; $$;
CREATE TRIGGER tenant_default_defect_codes AFTER INSERT ON tenants FOR EACH ROW EXECUTE FUNCTION seed_tenant_defect_codes();
SELECT seed_default_defect_codes(id) FROM tenants;

-- ------------------------------------------------------------------ inspection plans
CREATE TABLE inspection_plans (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    item_id text,
    category_id text,
    inspection_type text NOT NULL CHECK (inspection_type IN ('INCOMING', 'FINAL', 'RETURN', 'REWORK')),
    active boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    notes text NOT NULL DEFAULT '',
    created_by text NOT NULL REFERENCES users(id),
    updated_by text REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((item_id IS NULL) <> (category_id IS NULL)),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id),
    FOREIGN KEY (tenant_id, category_id) REFERENCES item_categories(tenant_id, id));
-- One active plan per item (or category) and inspection type.
CREATE UNIQUE INDEX inspection_plans_item_type ON inspection_plans(tenant_id, item_id, inspection_type)
    WHERE active AND item_id IS NOT NULL;
CREATE UNIQUE INDEX inspection_plans_category_type ON inspection_plans(tenant_id, category_id, inspection_type)
    WHERE active AND category_id IS NOT NULL;
CREATE INDEX inspection_plans_tenant_created ON inspection_plans(tenant_id, created_at DESC, id DESC);

CREATE TABLE inspection_characteristics (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    plan_id text NOT NULL,
    seq integer NOT NULL CHECK (seq >= 1),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    kind text NOT NULL CHECK (kind IN ('VARIABLE', 'ATTRIBUTE')),
    unit text CHECK (unit IS NULL OR length(unit) BETWEEN 1 AND 20),
    nominal numeric(20,6),
    lower_limit numeric(20,6),
    upper_limit numeric(20,6),
    sample_size integer NOT NULL CHECK (sample_size BETWEEN 1 AND 100000),
    critical boolean NOT NULL DEFAULT false,
    defect_code text,
    method text NOT NULL DEFAULT '',
    -- Editing a plan retires its characteristics (active = false) and adds the new set; measurements keep pointing at
    -- the characteristic they were taken against (and carry a snapshot of its specification).
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (kind = 'VARIABLE' OR (unit IS NULL AND nominal IS NULL AND lower_limit IS NULL AND upper_limit IS NULL)),
    CHECK (kind = 'ATTRIBUTE' OR ((lower_limit IS NOT NULL OR upper_limit IS NOT NULL) AND sample_size <= 20
                                  AND (lower_limit IS NULL OR upper_limit IS NULL OR lower_limit <= upper_limit))),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, plan_id) REFERENCES inspection_plans(tenant_id, id),
    FOREIGN KEY (tenant_id, defect_code) REFERENCES defect_codes(tenant_id, code));
CREATE INDEX inspection_characteristics_plan ON inspection_characteristics(tenant_id, plan_id, active, seq);

-- The plan that governs an inspection of p_item: an active plan of exactly that inspection type (RETURN and REWORK
-- inspections fall back to the FINAL plan), the item's own plan before a category plan, the nearest category first.
CREATE FUNCTION applicable_inspection_plan(p_tenant text, p_item text, p_type text) RETURNS text
LANGUAGE sql STABLE AS $$
  WITH RECURSIVE kinds(t, rank) AS (
      SELECT p_type, 0
      UNION ALL
      SELECT 'FINAL', 1 WHERE p_type IN ('RETURN', 'REWORK')),
  cats(id, depth) AS (
      SELECT category_id, 1 FROM items WHERE tenant_id = p_tenant AND id = p_item AND category_id IS NOT NULL
      UNION ALL
      SELECT c.parent_id, cats.depth + 1 FROM cats
      JOIN item_categories c ON c.tenant_id = p_tenant AND c.id = cats.id
      WHERE c.parent_id IS NOT NULL AND cats.depth < 20)
  SELECT p.id FROM inspection_plans p
  JOIN kinds ON kinds.t = p.inspection_type
  LEFT JOIN cats ON cats.id = p.category_id
  WHERE p.tenant_id = p_tenant AND p.active AND (p.item_id = p_item OR cats.id IS NOT NULL)
  ORDER BY kinds.rank, (p.item_id IS NULL), cats.depth NULLS FIRST, p.created_at, p.id
  LIMIT 1
$$;

-- Measurements are written once, together with the inspection decision (posted history).
CREATE TABLE inspection_measurements (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    inspection_id text NOT NULL,
    plan_id text NOT NULL,
    plan_version integer NOT NULL,
    characteristic_id text NOT NULL,
    seq integer NOT NULL,
    -- Specification snapshot at measurement time.
    name text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('VARIABLE', 'ATTRIBUTE')),
    unit text,
    nominal numeric(20,6),
    lower_limit numeric(20,6),
    upper_limit numeric(20,6),
    sample_size integer NOT NULL,
    critical boolean NOT NULL,
    -- Results: readings summary (variables) or nonconforming units in the sample (attributes).
    min_value numeric(20,6),
    max_value numeric(20,6),
    mean_value numeric(20,6),
    nonconforming integer CHECK (nonconforming IS NULL OR nonconforming >= 0),
    conforming boolean NOT NULL,
    note text NOT NULL DEFAULT '',
    recorded_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, inspection_id, characteristic_id),
    FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections(tenant_id, id),
    FOREIGN KEY (tenant_id, plan_id) REFERENCES inspection_plans(tenant_id, id),
    FOREIGN KEY (tenant_id, characteristic_id) REFERENCES inspection_characteristics(tenant_id, id));
CREATE INDEX inspection_measurements_characteristic ON inspection_measurements(tenant_id, characteristic_id, created_at);
CREATE TABLE inspection_readings (
    tenant_id text NOT NULL REFERENCES tenants(id),
    measurement_id text NOT NULL,
    sample_no integer NOT NULL CHECK (sample_no >= 1),
    value numeric(20,6) NOT NULL,
    PRIMARY KEY (tenant_id, measurement_id, sample_no),
    FOREIGN KEY (tenant_id, measurement_id) REFERENCES inspection_measurements(tenant_id, id));
CREATE TRIGGER inspection_measurements_immutable BEFORE UPDATE OR DELETE ON inspection_measurements
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER inspection_readings_immutable BEFORE UPDATE OR DELETE ON inspection_readings
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- ------------------------------------------------------------------ nonconformance reports
CREATE TABLE ncrs (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DISPOSITIONED', 'CLOSED', 'CANCELLED')),
    source text NOT NULL CHECK (source IN ('INSPECTION', 'PRODUCTION', 'SUPPLIER', 'CUSTOMER', 'INTERNAL', 'AUDIT', 'RECALL')),
    severity text NOT NULL CHECK (severity IN ('MINOR', 'MAJOR', 'CRITICAL')),
    defect_code text NOT NULL,
    title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 160),
    description text NOT NULL CHECK (length(btrim(description)) > 0),
    containment text NOT NULL DEFAULT '',
    item_id text,
    lot_id text,
    inspection_id text,
    manufacturing_order_id text,
    supplier_id text,
    customer_return_id text,
    -- The rework order that produced the affected lot (a reworked lot failing again), when applicable.
    rework_order_id text,
    quantity numeric(20,6) NOT NULL CHECK (quantity >= 0),
    uom text,
    -- True when raising the NCR put a released lot on hold (QUARANTINE); the hold lifts when the NCR is dispositioned.
    hold_applied boolean NOT NULL DEFAULT false,
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    dispositioned_at timestamptz,
    closed_by text REFERENCES users(id),
    closed_at timestamptz,
    close_note text NOT NULL DEFAULT '',
    cancelled_by text REFERENCES users(id),
    cancelled_at timestamptz,
    cancel_reason text NOT NULL DEFAULT '',
    CHECK (lot_id IS NULL OR item_id IS NOT NULL),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    FOREIGN KEY (tenant_id, defect_code) REFERENCES defect_codes(tenant_id, code),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections(tenant_id, id),
    FOREIGN KEY (tenant_id, manufacturing_order_id) REFERENCES manufacturing_orders(tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES partners(tenant_id, id),
    FOREIGN KEY (tenant_id, customer_return_id) REFERENCES customer_returns(tenant_id, id));
CREATE INDEX ncrs_tenant_created ON ncrs(tenant_id, created_at DESC, id DESC);
CREATE INDEX ncrs_tenant_status ON ncrs(tenant_id, status, severity);
CREATE INDEX ncrs_tenant_item ON ncrs(tenant_id, item_id);
CREATE INDEX ncrs_tenant_lot ON ncrs(tenant_id, lot_id);
CREATE INDEX ncrs_tenant_defect ON ncrs(tenant_id, defect_code, created_at);
-- A failed inspection raises exactly one NCR; a lot has at most one open NCR at a time.
CREATE UNIQUE INDEX ncrs_failed_inspection ON ncrs(tenant_id, inspection_id) WHERE source = 'INSPECTION';
CREATE UNIQUE INDEX ncrs_open_lot ON ncrs(tenant_id, lot_id) WHERE status = 'OPEN' AND lot_id IS NOT NULL;

CREATE TABLE ncr_dispositions (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    ncr_id text NOT NULL,
    line_no integer NOT NULL CHECK (line_no >= 1),
    type text NOT NULL CHECK (type IN ('USE_AS_IS', 'SCRAP', 'RETURN_TO_SUPPLIER', 'REWORK')),
    status text NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'AWAITING_RETURN', 'POSTED', 'REJECTED')),
    lot_id text,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    -- Value that left the held lot (scrap, rework input, supplier return); 0 for a concession.
    value_amount numeric(20,6) NOT NULL DEFAULT 0 CHECK (value_amount >= 0),
    note text NOT NULL CHECK (length(btrim(note)) > 0),
    -- Ledger/journal reference of the posting: the NCR number (scrap), RW-… (rework), SR-… (supplier return).
    reference text,
    receipt_id text,
    rework_order_id text,
    supplier_return_id text,
    automatic boolean NOT NULL DEFAULT false,
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    decided_by text REFERENCES users(id),
    decided_at timestamptz,
    decision_note text NOT NULL DEFAULT '',
    posted_at timestamptz,
    CHECK (status <> 'PENDING_APPROVAL' OR type = 'USE_AS_IS'),
    CHECK (status <> 'AWAITING_RETURN' OR type = 'RETURN_TO_SUPPLIER'),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, ncr_id, line_no),
    FOREIGN KEY (tenant_id, ncr_id) REFERENCES ncrs(tenant_id, id),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, receipt_id) REFERENCES receipts(tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_return_id) REFERENCES supplier_returns(tenant_id, id));
CREATE INDEX ncr_dispositions_tenant_status ON ncr_dispositions(tenant_id, status, type);
CREATE INDEX ncr_dispositions_tenant_posted ON ncr_dispositions(tenant_id, type, posted_at);

-- ------------------------------------------------------------------ rework
CREATE TABLE rework_orders (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    ncr_id text NOT NULL,
    disposition_id text NOT NULL,
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'COMPLETED')),
    item_id text NOT NULL,
    input_lot_id text NOT NULL,
    input_quantity numeric(20,6) NOT NULL CHECK (input_quantity > 0),
    input_value numeric(20,6) NOT NULL CHECK (input_value >= 0),
    material_cost numeric(20,6) NOT NULL DEFAULT 0 CHECK (material_cost >= 0),
    labour_cost numeric(20,6) NOT NULL DEFAULT 0 CHECK (labour_cost >= 0),
    output_lot_id text,
    output_quantity numeric(20,6) CHECK (output_quantity > 0),
    output_value numeric(20,6) CHECK (output_value >= 0),
    instructions text NOT NULL DEFAULT '',
    completion_note text NOT NULL DEFAULT '',
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_by text REFERENCES users(id),
    completed_at timestamptz,
    -- Value conservation: the output lot carries the input value plus every rework cost.
    CHECK ((status = 'OPEN' AND output_lot_id IS NULL AND output_quantity IS NULL AND output_value IS NULL)
        OR (status = 'COMPLETED' AND output_lot_id IS NOT NULL AND output_quantity IS NOT NULL
            AND output_value = input_value + material_cost + labour_cost)),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    FOREIGN KEY (tenant_id, ncr_id) REFERENCES ncrs(tenant_id, id),
    FOREIGN KEY (tenant_id, disposition_id) REFERENCES ncr_dispositions(tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id),
    FOREIGN KEY (tenant_id, input_lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, output_lot_id) REFERENCES lots(tenant_id, id));
CREATE INDEX rework_orders_tenant_created ON rework_orders(tenant_id, created_at DESC, id DESC);
CREATE INDEX rework_orders_tenant_status ON rework_orders(tenant_id, status);
ALTER TABLE ncr_dispositions
    ADD FOREIGN KEY (tenant_id, rework_order_id) REFERENCES rework_orders(tenant_id, id);
ALTER TABLE ncrs
    ADD FOREIGN KEY (tenant_id, rework_order_id) REFERENCES rework_orders(tenant_id, id);

-- Extra material issued from released lots into a rework order (posted history).
CREATE TABLE rework_materials (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    rework_order_id text NOT NULL,
    lot_id text NOT NULL,
    item_id text NOT NULL,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    value_amount numeric(20,6) NOT NULL CHECK (value_amount >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, rework_order_id) REFERENCES rework_orders(tenant_id, id),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id));
CREATE INDEX rework_materials_order ON rework_materials(tenant_id, rework_order_id);

-- Genealogy: every lot consumed by a rework order → the lot it produced. role REWORKED = the held nonconforming
-- input, MATERIAL = extra material issued from a released lot. Written when the rework order completes; immutable.
CREATE TABLE rework_lot_links (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    rework_order_id text NOT NULL,
    input_lot_id text NOT NULL,
    output_lot_id text NOT NULL,
    role text NOT NULL CHECK (role IN ('REWORKED', 'MATERIAL')),
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    value_amount numeric(20,6) NOT NULL CHECK (value_amount >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (input_lot_id <> output_lot_id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, rework_order_id) REFERENCES rework_orders(tenant_id, id),
    FOREIGN KEY (tenant_id, input_lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, output_lot_id) REFERENCES lots(tenant_id, id));
CREATE INDEX rework_lot_links_input ON rework_lot_links(tenant_id, input_lot_id);
CREATE INDEX rework_lot_links_output ON rework_lot_links(tenant_id, output_lot_id);
CREATE TRIGGER rework_materials_immutable BEFORE UPDATE OR DELETE ON rework_materials
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER rework_lot_links_immutable BEFORE UPDATE OR DELETE ON rework_lot_links
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- ------------------------------------------------------------------ CAPA
CREATE TABLE capas (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 160),
    problem text NOT NULL CHECK (length(btrim(problem)) > 0),
    source text NOT NULL CHECK (source IN ('NCR', 'AUDIT', 'CUSTOMER', 'SUPPLIER', 'INTERNAL')),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'VERIFICATION', 'CLOSED', 'CANCELLED')),
    owner_id text NOT NULL REFERENCES users(id),
    due_date date,
    root_cause_category text
        CHECK (root_cause_category IN ('MAN', 'MACHINE', 'METHOD', 'MATERIAL', 'MEASUREMENT', 'ENVIRONMENT')),
    why_1 text NOT NULL DEFAULT '',
    why_2 text NOT NULL DEFAULT '',
    why_3 text NOT NULL DEFAULT '',
    why_4 text NOT NULL DEFAULT '',
    why_5 text NOT NULL DEFAULT '',
    root_cause text NOT NULL DEFAULT '',
    effective boolean,
    verification_note text NOT NULL DEFAULT '',
    verified_by text REFERENCES users(id),
    verified_at timestamptz,
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz,
    cancelled_by text REFERENCES users(id),
    cancelled_at timestamptz,
    cancel_reason text NOT NULL DEFAULT '',
    CHECK (status <> 'CLOSED' OR (verified_by IS NOT NULL AND verified_by <> owner_id AND effective)),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number));
CREATE INDEX capas_tenant_created ON capas(tenant_id, created_at DESC, id DESC);
CREATE INDEX capas_tenant_status ON capas(tenant_id, status);

CREATE TABLE capa_ncrs (
    tenant_id text NOT NULL REFERENCES tenants(id),
    capa_id text NOT NULL,
    ncr_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, capa_id, ncr_id),
    FOREIGN KEY (tenant_id, capa_id) REFERENCES capas(tenant_id, id),
    FOREIGN KEY (tenant_id, ncr_id) REFERENCES ncrs(tenant_id, id));
CREATE INDEX capa_ncrs_ncr ON capa_ncrs(tenant_id, ncr_id);

CREATE TABLE capa_actions (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    capa_id text NOT NULL,
    seq integer NOT NULL CHECK (seq >= 1),
    type text NOT NULL CHECK (type IN ('CORRECTIVE', 'PREVENTIVE')),
    description text NOT NULL CHECK (length(btrim(description)) BETWEEN 1 AND 1000),
    owner_id text NOT NULL REFERENCES users(id),
    due_date date NOT NULL,
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE', 'CANCELLED')),
    completion_note text NOT NULL DEFAULT '',
    completed_by text REFERENCES users(id),
    completed_at timestamptz,
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (status <> 'DONE' OR (completed_by IS NOT NULL AND completed_at IS NOT NULL)),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, capa_id, seq),
    FOREIGN KEY (tenant_id, capa_id) REFERENCES capas(tenant_id, id));
CREATE INDEX capa_actions_tenant_open ON capa_actions(tenant_id, status, due_date);
CREATE INDEX capa_actions_tenant_created ON capa_actions(tenant_id, created_at DESC, id DESC);
