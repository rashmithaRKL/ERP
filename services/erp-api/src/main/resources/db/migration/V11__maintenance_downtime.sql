-- V11 maintenance and downtime: a MAINTENANCE user role, SPARE_PART items, machine downtime events (append-only
-- corrections), maintenance work orders (corrective and preventive) with checklists, labour and spare-part issues,
-- preventive maintenance plans, machine run-hour meter readings and the machine status history written by maintenance.
--
-- Read by later Phase 2 features:
--   * finite-capacity scheduling (V13): maintenance_work_orders.scheduled_start/scheduled_end of APPROVED, SCHEDULED
--     and IN_PROGRESS orders, and open machine_downtime rows (ended_at IS NULL);
--   * OEE/utilization (V15): machine_downtime (effective events only) with machine_id, started_at, ended_at, reason
--     and planned.
-- Stock still changes only through stock_ledger (reasons MAINTENANCE_ISSUE / MAINTENANCE_RETURN) and money only
-- through balanced journals (MAINTENANCE_EXPENSE, MAINTENANCE_LABOUR_CLEARING).

-- ------------------------------------------------------------------ MAINTENANCE role
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
  CHECK (role IN ('ADMIN','PLANNER','WAREHOUSE','OPERATOR','QUALITY','SALES','VIEWER','MAINTENANCE'));

-- ------------------------------------------------------------------ SPARE_PART items
-- Spare parts are ordinary inventory items (lots, ledger, receipts, counts, valued in RAW_INVENTORY). They are never
-- BOM components (catalog rule), so material planning only replenishes them to their reorder level.
ALTER TABLE items DROP CONSTRAINT items_type_check;
ALTER TABLE items ADD CONSTRAINT items_type_check
  CHECK (type IN ('RAW_MATERIAL','SEMI_FINISHED','FINISHED_GOOD','PACKAGING','SPARE_PART'));
ALTER TABLE item_templates DROP CONSTRAINT item_templates_type_check;
ALTER TABLE item_templates ADD CONSTRAINT item_templates_type_check
  CHECK (type IN ('RAW_MATERIAL','SEMI_FINISHED','FINISHED_GOOD','PACKAGING','SPARE_PART'));

-- ------------------------------------------------------------------ settings
-- Maintenance labour rate (LKR per hour); a missing row means the built-in default (maintenance.MaintenanceRules).
CREATE TABLE maintenance_settings (
  tenant_id text PRIMARY KEY REFERENCES tenants(id),
  labour_rate numeric(20,6) NOT NULL CHECK (labour_rate >= 0),
  updated_by text REFERENCES users(id),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------------ preventive maintenance plans
CREATE TABLE maintenance_plans (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  number text NOT NULL,
  machine_id text NOT NULL,
  title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 160),
  description text NOT NULL DEFAULT '',
  -- ["Check hydraulic oil level", ...]
  checklist jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(checklist) = 'array'),
  -- Calendar interval (always set) and an optional run-hour interval: whichever comes first.
  interval_days integer NOT NULL CHECK (interval_days BETWEEN 1 AND 3650),
  interval_hours numeric(20,6) CHECK (interval_hours IS NULL OR interval_hours > 0),
  estimated_hours numeric(20,6) NOT NULL DEFAULT 2 CHECK (estimated_hours > 0 AND estimated_hours <= 720),
  priority text NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL','HIGH','URGENT')),
  next_due_date date NOT NULL,
  -- Run-hour meter reading at the last completed service (baseline of interval_hours).
  meter_at_last_service numeric(20,6) CHECK (meter_at_last_service IS NULL OR meter_at_last_service >= 0),
  last_completed_at timestamptz,
  active boolean NOT NULL DEFAULT true,
  created_by text NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, number),
  FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id)
);
CREATE INDEX maintenance_plans_tenant_due ON maintenance_plans(tenant_id, active, next_due_date, id);
CREATE INDEX maintenance_plans_tenant_machine ON maintenance_plans(tenant_id, machine_id);

-- ------------------------------------------------------------------ maintenance work orders
-- CORRECTIVE: requested (optionally with an open BREAKDOWN downtime event) → approved by a different PLANNER/ADMIN.
-- PREVENTIVE: generated from a plan (approved and scheduled by the generating planner).
-- REQUESTED → APPROVED → SCHEDULED → IN_PROGRESS → COMPLETED, or CANCELLED before work starts.
CREATE TABLE maintenance_work_orders (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  number text NOT NULL,
  type text NOT NULL CHECK (type IN ('CORRECTIVE','PREVENTIVE')),
  machine_id text NOT NULL,
  plan_id text,
  -- Preventive occurrence: the plan's due date this order performs (unique per plan).
  due_date date,
  title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 160),
  description text NOT NULL DEFAULT '',
  priority text NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL','HIGH','URGENT')),
  status text NOT NULL CHECK (status IN ('REQUESTED','APPROVED','SCHEDULED','IN_PROGRESS','COMPLETED','CANCELLED')),
  scheduled_start timestamptz,
  scheduled_end timestamptz,
  assignee_id text REFERENCES users(id),
  -- [{"task":"...","result":"PASS|FAIL|NA"|null,"note":"..."}] (snapshot of the plan's checklist)
  checklist jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(checklist) = 'array'),
  findings text NOT NULL DEFAULT '',
  actions text NOT NULL DEFAULT '',
  labour_hours numeric(20,6) NOT NULL DEFAULT 0 CHECK (labour_hours >= 0),
  labour_cost numeric(20,6) NOT NULL DEFAULT 0 CHECK (labour_cost >= 0),
  -- Net value of spare parts issued less returned.
  parts_cost numeric(20,6) NOT NULL DEFAULT 0 CHECK (parts_cost >= 0),
  meter_reading numeric(20,6) CHECK (meter_reading IS NULL OR meter_reading >= 0),
  requested_by text NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  approved_by text REFERENCES users(id),
  approved_at timestamptz,
  started_by text REFERENCES users(id),
  started_at timestamptz,
  completed_by text REFERENCES users(id),
  completed_at timestamptz,
  cancelled_by text REFERENCES users(id),
  cancelled_at timestamptz,
  cancel_reason text,
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, number),
  FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
  FOREIGN KEY (tenant_id, plan_id) REFERENCES maintenance_plans(tenant_id, id),
  CHECK ((scheduled_start IS NULL) = (scheduled_end IS NULL)),
  CHECK (scheduled_end IS NULL OR scheduled_end > scheduled_start),
  CHECK ((type = 'PREVENTIVE') = (plan_id IS NOT NULL)),
  CHECK ((plan_id IS NULL) = (due_date IS NULL)),
  CHECK (status <> 'SCHEDULED' OR scheduled_start IS NOT NULL),
  CHECK (status NOT IN ('IN_PROGRESS','COMPLETED') OR started_at IS NOT NULL),
  CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
);
-- "Generate due work orders" is idempotent per plan and due date.
CREATE UNIQUE INDEX maintenance_work_orders_plan_due ON maintenance_work_orders(tenant_id, plan_id, due_date)
  WHERE plan_id IS NOT NULL;
-- One order in progress per machine (it owns the machine's MAINTENANCE status).
CREATE UNIQUE INDEX maintenance_work_orders_one_in_progress ON maintenance_work_orders(tenant_id, machine_id)
  WHERE status = 'IN_PROGRESS';
CREATE INDEX maintenance_work_orders_tenant_created ON maintenance_work_orders(tenant_id, created_at DESC, id DESC);
CREATE INDEX maintenance_work_orders_tenant_status ON maintenance_work_orders(tenant_id, status, created_at DESC);
CREATE INDEX maintenance_work_orders_tenant_machine ON maintenance_work_orders(tenant_id, machine_id, created_at DESC);
CREATE INDEX maintenance_work_orders_tenant_window ON maintenance_work_orders(tenant_id, machine_id, scheduled_start)
  WHERE status IN ('APPROVED','SCHEDULED','IN_PROGRESS');

-- Completed and cancelled orders are final.
CREATE FUNCTION protect_closed_maintenance_order() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('COMPLETED','CANCELLED') THEN
    RAISE EXCEPTION 'Completed and cancelled maintenance work orders are immutable';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER maintenance_work_order_immutable BEFORE UPDATE OR DELETE ON maintenance_work_orders
  FOR EACH ROW EXECUTE FUNCTION protect_closed_maintenance_order();

-- ------------------------------------------------------------------ downtime events
-- One row per stop of a machine. OPEN events have no end. A CLOSED event is never edited: a correction marks it
-- SUPERSEDED and inserts the corrected event (CLOSED, or VOID to withdraw it) with corrects_id pointing back.
-- Effective events (status OPEN or CLOSED) of one machine never overlap (trigger below); read them through the
-- machine_downtime view.
CREATE TABLE downtime_events (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  number text NOT NULL,
  machine_id text NOT NULL,
  reason text NOT NULL CHECK (reason IN ('BREAKDOWN','PLANNED_MAINTENANCE','CHANGEOVER','MATERIAL_SHORTAGE',
                                         'NO_OPERATOR','QUALITY_HOLD','UTILITIES','OTHER')),
  planned boolean NOT NULL,
  started_at timestamptz NOT NULL,
  ended_at timestamptz,
  status text NOT NULL CHECK (status IN ('OPEN','CLOSED','SUPERSEDED','VOID')),
  manufacturing_order_id text,
  work_order_id text,
  notes text NOT NULL DEFAULT '',
  reported_by text NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  closed_by text REFERENCES users(id),
  closed_at timestamptz,
  corrects_id text,
  correction_reason text,
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, number),
  FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
  FOREIGN KEY (tenant_id, manufacturing_order_id) REFERENCES manufacturing_orders(tenant_id, id),
  FOREIGN KEY (tenant_id, work_order_id) REFERENCES maintenance_work_orders(tenant_id, id),
  FOREIGN KEY (tenant_id, corrects_id) REFERENCES downtime_events(tenant_id, id),
  CHECK (ended_at IS NULL OR ended_at > started_at),
  CHECK ((status = 'OPEN') = (ended_at IS NULL)),
  CHECK (reason <> 'BREAKDOWN' OR NOT planned),
  CHECK (reason <> 'PLANNED_MAINTENANCE' OR planned),
  CHECK ((corrects_id IS NULL) = (correction_reason IS NULL))
);
-- An event is corrected at most once (later corrections correct the correcting event).
CREATE UNIQUE INDEX downtime_events_corrects ON downtime_events(tenant_id, corrects_id) WHERE corrects_id IS NOT NULL;
CREATE INDEX downtime_events_tenant_started ON downtime_events(tenant_id, started_at DESC, id DESC);
CREATE INDEX downtime_events_tenant_machine ON downtime_events(tenant_id, machine_id, started_at DESC);
CREATE INDEX downtime_events_tenant_reason ON downtime_events(tenant_id, reason, started_at DESC);
CREATE INDEX downtime_events_tenant_open ON downtime_events(tenant_id, machine_id) WHERE status = 'OPEN';
CREATE INDEX downtime_events_tenant_work_order ON downtime_events(tenant_id, work_order_id);

CREATE FUNCTION protect_downtime_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'Downtime events cannot be deleted; record a correction';
  END IF;
  IF OLD.status IN ('SUPERSEDED','VOID') THEN
    RAISE EXCEPTION 'Corrected downtime events are immutable';
  END IF;
  IF OLD.status = 'CLOSED' AND (NEW.status <> 'SUPERSEDED'
      OR (NEW.machine_id, NEW.reason, NEW.planned, NEW.started_at, NEW.ended_at, NEW.manufacturing_order_id,
          NEW.work_order_id, NEW.notes, NEW.closed_by, NEW.closed_at)
         IS DISTINCT FROM
         (OLD.machine_id, OLD.reason, OLD.planned, OLD.started_at, OLD.ended_at, OLD.manufacturing_order_id,
          OLD.work_order_id, OLD.notes, OLD.closed_by, OLD.closed_at)) THEN
    RAISE EXCEPTION 'Closed downtime events are immutable; record a correction';
  END IF;
  IF OLD.status = 'OPEN' AND (NEW.machine_id <> OLD.machine_id OR NEW.started_at <> OLD.started_at
      OR NEW.status NOT IN ('OPEN','CLOSED')) THEN
    RAISE EXCEPTION 'An open downtime event can only be closed or linked to a work order';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER downtime_event_guard BEFORE UPDATE OR DELETE ON downtime_events
  FOR EACH ROW EXECUTE FUNCTION protect_downtime_event();

-- No overlapping effective downtime for the same machine ([start, end), open events run to infinity). Commands are
-- serialized per tenant (Store.once), so the check cannot race with another command of the same tenant.
CREATE FUNCTION reject_overlapping_downtime() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE clash text;
BEGIN
  IF NEW.status NOT IN ('OPEN','CLOSED') THEN RETURN NEW; END IF;
  SELECT number INTO clash FROM downtime_events d
  WHERE d.tenant_id = NEW.tenant_id AND d.machine_id = NEW.machine_id AND d.id <> NEW.id
    AND d.status IN ('OPEN','CLOSED')
    AND tstzrange(d.started_at, COALESCE(d.ended_at, 'infinity'::timestamptz), '[)')
        && tstzrange(NEW.started_at, COALESCE(NEW.ended_at, 'infinity'::timestamptz), '[)')
  LIMIT 1;
  IF clash IS NOT NULL THEN
    RAISE EXCEPTION 'Downtime overlaps % on the same machine', clash;
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER downtime_no_overlap BEFORE INSERT OR UPDATE ON downtime_events
  FOR EACH ROW EXECUTE FUNCTION reject_overlapping_downtime();

-- The effective downtime record (what scheduling and OEE read): open and closed events, never superseded or voided
-- rows.
CREATE VIEW machine_downtime AS
SELECT d.id, d.tenant_id, d.number, d.machine_id, d.reason, d.planned, d.started_at, d.ended_at, d.status,
       d.manufacturing_order_id, d.work_order_id, d.notes, d.reported_by, d.created_at, d.closed_by, d.closed_at,
       d.corrects_id
FROM downtime_events d
WHERE d.status IN ('OPEN','CLOSED');

-- ------------------------------------------------------------------ labour
CREATE TABLE maintenance_labour (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  work_order_id text NOT NULL,
  technician_id text NOT NULL REFERENCES users(id),
  -- Negative hours reverse an earlier entry (the order's total never goes below zero).
  hours numeric(20,6) NOT NULL CHECK (hours <> 0 AND hours BETWEEN -1000 AND 1000),
  rate numeric(20,6) NOT NULL CHECK (rate >= 0),
  cost numeric(20,6) NOT NULL,
  notes text NOT NULL DEFAULT '',
  created_by text NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, work_order_id) REFERENCES maintenance_work_orders(tenant_id, id)
);
CREATE INDEX maintenance_labour_tenant_order ON maintenance_labour(tenant_id, work_order_id, created_at);
CREATE TRIGGER maintenance_labour_immutable BEFORE UPDATE OR DELETE ON maintenance_labour
  FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- ------------------------------------------------------------------ spare-part issues and returns
-- ISSUE consumes released stock (ledger MAINTENANCE_ISSUE, Dr MAINTENANCE_EXPENSE / Cr inventory); RETURN puts unused
-- parts of one issue back into the same lot (ledger MAINTENANCE_RETURN at the issue's unit value, reversed journal).
CREATE TABLE maintenance_part_movements (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  work_order_id text NOT NULL,
  kind text NOT NULL CHECK (kind IN ('ISSUE','RETURN')),
  lot_id text NOT NULL,
  item_id text NOT NULL,
  quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
  value_amount numeric(20,6) NOT NULL CHECK (value_amount >= 0),
  returns_id text,
  notes text NOT NULL DEFAULT '',
  created_by text NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, work_order_id) REFERENCES maintenance_work_orders(tenant_id, id),
  FOREIGN KEY (tenant_id, lot_id) REFERENCES lots(tenant_id, id),
  FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id),
  FOREIGN KEY (tenant_id, returns_id) REFERENCES maintenance_part_movements(tenant_id, id),
  CHECK ((kind = 'RETURN') = (returns_id IS NOT NULL))
);
CREATE INDEX maintenance_part_movements_tenant_order ON maintenance_part_movements(tenant_id, work_order_id, created_at);
CREATE INDEX maintenance_part_movements_tenant_returns ON maintenance_part_movements(tenant_id, returns_id);
CREATE TRIGGER maintenance_part_movements_immutable BEFORE UPDATE OR DELETE ON maintenance_part_movements
  FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- ------------------------------------------------------------------ run-hour meter readings
CREATE TABLE machine_meter_readings (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  machine_id text NOT NULL,
  reading numeric(20,6) NOT NULL CHECK (reading >= 0),
  notes text NOT NULL DEFAULT '',
  recorded_by text NOT NULL REFERENCES users(id),
  recorded_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id)
);
CREATE INDEX machine_meter_readings_tenant_machine ON machine_meter_readings(tenant_id, machine_id, recorded_at DESC, id DESC);
CREATE TRIGGER machine_meter_readings_immutable BEFORE UPDATE OR DELETE ON machine_meter_readings
  FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- ------------------------------------------------------------------ machine status history (maintenance-owned changes)
-- source MAINTENANCE_ORDER: an order started (→ MAINTENANCE) or completed (→ IDLE); MANUAL: a planner's correction.
CREATE TABLE machine_status_changes (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  machine_id text NOT NULL,
  from_status text NOT NULL,
  to_status text NOT NULL CHECK (to_status IN ('RUNNING','IDLE','MAINTENANCE')),
  source text NOT NULL CHECK (source IN ('MAINTENANCE_ORDER','MANUAL')),
  reference text,
  reason text NOT NULL DEFAULT '',
  changed_by text NOT NULL REFERENCES users(id),
  changed_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id)
);
CREATE INDEX machine_status_changes_tenant_machine ON machine_status_changes(tenant_id, machine_id, changed_at DESC, id DESC);
CREATE TRIGGER machine_status_changes_immutable BEFORE UPDATE OR DELETE ON machine_status_changes
  FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
