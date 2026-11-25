-- V13: Phase 2 finite-capacity scheduling. A planner runs the forward finite-capacity scheduler (scheduling package)
-- into a DRAFT schedule run, compares it with the published run and publishes it: one PUBLISHED run per tenant, the
-- previous one becomes SUPERSEDED. A run is a frozen result: its order, operation and blocked-time rows are inserted
-- with the run and never change; only the run header moves DRAFT -> PUBLISHED -> SUPERSEDED (or DRAFT -> DISCARDED).
-- Pins (an operation fixed to a work center and start) and duration estimates for orders without a routing are the
-- planner's inputs to later runs. Reads V10 (mo_operations, time logs, routing alternates, shift calendars through
-- routing.CalendarService) and V11 (maintenance windows, the machine_downtime view); changes no existing table.

-- ------------------------------------------------------------------ schedule runs
CREATE TABLE schedule_runs (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'DISCARDED')),
    -- The scheduler's "now" (start of the horizon, whole minute) and the end of the horizon.
    horizon_start timestamptz NOT NULL,
    horizon_end timestamptz NOT NULL,
    horizon_days integer NOT NULL CHECK (horizon_days BETWEEN 1 AND 90),
    use_alternates boolean NOT NULL,
    -- How long an open downtime without a later maintenance window end is assumed to block its work center.
    breakdown_hours integer NOT NULL CHECK (breakdown_hours BETWEEN 1 AND 168),
    include_drafts boolean NOT NULL,
    notes text NOT NULL DEFAULT '' CHECK (length(notes) <= 400),
    -- The run that was published when this one was created (default comparison base).
    base_run_id text,
    orders_count integer NOT NULL DEFAULT 0 CHECK (orders_count >= 0),
    operations_count integer NOT NULL DEFAULT 0 CHECK (operations_count >= 0),
    late_orders integer NOT NULL DEFAULT 0 CHECK (late_orders >= 0),
    unscheduled_orders integer NOT NULL DEFAULT 0 CHECK (unscheduled_orders >= 0),
    pins_honoured integer NOT NULL DEFAULT 0 CHECK (pins_honoured >= 0),
    pin_conflicts integer NOT NULL DEFAULT 0 CHECK (pin_conflicts >= 0),
    makespan_end timestamptz,
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    published_by text REFERENCES users(id),
    published_at timestamptz,
    superseded_by_run_id text,
    superseded_at timestamptz,
    discarded_by text REFERENCES users(id),
    discarded_at timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    FOREIGN KEY (tenant_id, base_run_id) REFERENCES schedule_runs(tenant_id, id),
    FOREIGN KEY (tenant_id, superseded_by_run_id) REFERENCES schedule_runs(tenant_id, id),
    CHECK (horizon_end > horizon_start),
    CHECK ((status IN ('PUBLISHED', 'SUPERSEDED')) = (published_at IS NOT NULL AND published_by IS NOT NULL)),
    CHECK ((status = 'SUPERSEDED') = (superseded_at IS NOT NULL)),
    CHECK ((status = 'DISCARDED') = (discarded_at IS NOT NULL AND discarded_by IS NOT NULL))
);
-- One published schedule per tenant.
CREATE UNIQUE INDEX schedule_runs_one_published ON schedule_runs(tenant_id) WHERE status = 'PUBLISHED';
CREATE INDEX schedule_runs_tenant_created ON schedule_runs(tenant_id, created_at DESC, id DESC);
CREATE INDEX schedule_runs_tenant_status ON schedule_runs(tenant_id, status, created_at DESC);

-- The run's result is frozen: only the lifecycle columns change, along DRAFT -> PUBLISHED -> SUPERSEDED or
-- DRAFT -> DISCARDED. Superseded and discarded runs are immutable; runs are never deleted.
CREATE FUNCTION protect_schedule_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Schedule runs cannot be deleted'; END IF;
  IF OLD.status IN ('SUPERSEDED', 'DISCARDED') THEN
    RAISE EXCEPTION 'Superseded and discarded schedule runs are immutable';
  END IF;
  IF (NEW.tenant_id, NEW.number, NEW.horizon_start, NEW.horizon_end, NEW.horizon_days, NEW.use_alternates,
      NEW.breakdown_hours, NEW.include_drafts, NEW.notes, NEW.base_run_id, NEW.orders_count, NEW.operations_count,
      NEW.late_orders, NEW.unscheduled_orders, NEW.pins_honoured, NEW.pin_conflicts, NEW.makespan_end,
      NEW.created_by, NEW.created_at)
     IS DISTINCT FROM
     (OLD.tenant_id, OLD.number, OLD.horizon_start, OLD.horizon_end, OLD.horizon_days, OLD.use_alternates,
      OLD.breakdown_hours, OLD.include_drafts, OLD.notes, OLD.base_run_id, OLD.orders_count, OLD.operations_count,
      OLD.late_orders, OLD.unscheduled_orders, OLD.pins_honoured, OLD.pin_conflicts, OLD.makespan_end,
      OLD.created_by, OLD.created_at) THEN
    RAISE EXCEPTION 'A schedule run is a frozen result; run the scheduler again';
  END IF;
  IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'PUBLISHED', 'DISCARDED') THEN
    RAISE EXCEPTION 'A draft schedule can only be published or discarded';
  END IF;
  IF OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED', 'SUPERSEDED') THEN
    RAISE EXCEPTION 'A published schedule can only be superseded by a newer one';
  END IF;
  IF OLD.status = 'PUBLISHED' AND (NEW.published_at IS DISTINCT FROM OLD.published_at
      OR NEW.published_by IS DISTINCT FROM OLD.published_by) THEN
    RAISE EXCEPTION 'A schedule run is a frozen result; run the scheduler again';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER schedule_run_guard BEFORE UPDATE OR DELETE ON schedule_runs
    FOR EACH ROW EXECUTE FUNCTION protect_schedule_run();

-- ------------------------------------------------------------------ run results
-- One row per manufacturing order the run considered. status: SCHEDULED (every open operation placed), NO_ROUTING
-- (order without a routing and without a duration estimate: listed, never scheduled), BEYOND_HORIZON (an operation
-- did not fit before the horizon end; later operations are not placed), NO_OPEN_WORK (every operation done, output
-- still to post). projected_end = end of the last placed operation; due_end = end of the due date (Asia/Colombo).
CREATE TABLE schedule_run_orders (
    tenant_id text NOT NULL REFERENCES tenants(id),
    run_id text NOT NULL,
    order_id text NOT NULL,
    sequence integer NOT NULL CHECK (sequence >= 1),
    order_status text NOT NULL,
    priority text NOT NULL CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT')),
    due_date date NOT NULL,
    due_end timestamptz NOT NULL,
    parent_order_id text,
    routed boolean NOT NULL,
    estimate_minutes numeric(20,6),
    status text NOT NULL CHECK (status IN ('SCHEDULED', 'NO_ROUTING', 'BEYOND_HORIZON', 'NO_OPEN_WORK')),
    projected_start timestamptz,
    projected_end timestamptz,
    late boolean NOT NULL,
    lateness_minutes integer NOT NULL DEFAULT 0 CHECK (lateness_minutes >= 0),
    remaining_minutes integer NOT NULL DEFAULT 0 CHECK (remaining_minutes >= 0),
    warnings text NOT NULL DEFAULT '',
    PRIMARY KEY (tenant_id, run_id, order_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES schedule_runs(tenant_id, id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES manufacturing_orders(tenant_id, id),
    CHECK (projected_end IS NULL OR projected_start IS NOT NULL)
);
CREATE INDEX schedule_run_orders_order ON schedule_run_orders(tenant_id, order_id);
CREATE INDEX schedule_run_orders_late ON schedule_run_orders(tenant_id, run_id, late, lateness_minutes DESC);

-- One row per placed operation. operation_id is NULL for the implicit single operation of an order without a
-- routing (scheduled from the planner's estimate). minutes = whole working minutes the operation occupies its work
-- center for (remaining minutes rounded up); segments = the working intervals it runs in
-- ([{"start":"…","end":"…"}], split across breaks, shift ends, days and blocked time). pin_status: HONOURED (placed at
-- the pinned work center and start) or CONFLICT (pin could not be honoured; placed by the normal rules).
CREATE TABLE schedule_run_operations (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    run_id text NOT NULL,
    order_id text NOT NULL,
    operation_id text,
    seq integer NOT NULL,
    name text NOT NULL,
    operation_status text NOT NULL,
    machine_id text NOT NULL,
    planned_machine_id text NOT NULL,
    alternate boolean NOT NULL,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    minutes integer NOT NULL CHECK (minutes >= 0),
    remaining_minutes numeric(20,6) NOT NULL CHECK (remaining_minutes >= 0),
    planned_minutes numeric(20,6) NOT NULL CHECK (planned_minutes >= 0),
    actual_minutes numeric(20,6) NOT NULL DEFAULT 0 CHECK (actual_minutes >= 0),
    segments jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(segments) = 'array'),
    late boolean NOT NULL,
    running boolean NOT NULL DEFAULT false,
    pin_id text,
    pin_status text CHECK (pin_status IN ('HONOURED', 'CONFLICT')),
    pin_note text NOT NULL DEFAULT '',
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, run_id, order_id) REFERENCES schedule_run_orders(tenant_id, run_id, order_id),
    FOREIGN KEY (tenant_id, order_id, operation_id) REFERENCES mo_operations(tenant_id, order_id, id),
    FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    FOREIGN KEY (tenant_id, planned_machine_id) REFERENCES machines(tenant_id, id),
    CHECK (end_at >= start_at),
    CHECK ((pin_id IS NULL) = (pin_status IS NULL))
);
CREATE UNIQUE INDEX schedule_run_operations_one_per_operation
    ON schedule_run_operations(tenant_id, run_id, order_id, COALESCE(operation_id, ''));
CREATE INDEX schedule_run_operations_machine ON schedule_run_operations(tenant_id, run_id, machine_id, start_at);
CREATE INDEX schedule_run_operations_operation ON schedule_run_operations(tenant_id, operation_id);

-- Blocked time the run avoided: maintenance windows of APPROVED/SCHEDULED/IN_PROGRESS work orders (MAINTENANCE) and
-- open or future downtime from the machine_downtime view (DOWNTIME). An open downtime blocks until the end of its
-- work order's maintenance window when that is later, otherwise for the run's breakdown_hours (assumed_end = true).
CREATE TABLE schedule_run_blocks (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    run_id text NOT NULL,
    machine_id text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('MAINTENANCE', 'DOWNTIME')),
    reference text NOT NULL,
    label text NOT NULL DEFAULT '',
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    assumed_end boolean NOT NULL DEFAULT false,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES schedule_runs(tenant_id, id),
    FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    CHECK (end_at > start_at)
);
CREATE INDEX schedule_run_blocks_run ON schedule_run_blocks(tenant_id, run_id, machine_id, start_at);

-- Run results are written once, with the run.
CREATE TRIGGER schedule_run_orders_immutable BEFORE UPDATE OR DELETE ON schedule_run_orders
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER schedule_run_operations_immutable BEFORE UPDATE OR DELETE ON schedule_run_operations
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER schedule_run_blocks_immutable BEFORE UPDATE OR DELETE ON schedule_run_blocks
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- ------------------------------------------------------------------ planner inputs
-- A pin fixes an open operation (or, operation_id NULL, the implicit operation of an order without a routing) to a
-- work center and start time; later runs honour it while it stays feasible. At most one active pin per operation;
-- removed pins are kept (removed_at) for the audit trail.
CREATE TABLE schedule_pins (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    order_id text NOT NULL,
    operation_id text,
    machine_id text NOT NULL,
    start_at timestamptz NOT NULL,
    reason text NOT NULL DEFAULT '' CHECK (length(reason) <= 400),
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    removed_by text REFERENCES users(id),
    removed_at timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES manufacturing_orders(tenant_id, id),
    FOREIGN KEY (tenant_id, order_id, operation_id) REFERENCES mo_operations(tenant_id, order_id, id),
    FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    CHECK ((removed_at IS NULL) = (removed_by IS NULL))
);
CREATE UNIQUE INDEX schedule_pins_one_active
    ON schedule_pins(tenant_id, order_id, COALESCE(operation_id, '')) WHERE removed_at IS NULL;
CREATE INDEX schedule_pins_tenant_created ON schedule_pins(tenant_id, created_at DESC, id DESC);

ALTER TABLE schedule_run_operations
    ADD CONSTRAINT schedule_run_operations_pin_fkey FOREIGN KEY (tenant_id, pin_id) REFERENCES schedule_pins(tenant_id, id);

-- Only removal changes a pin; removed pins are immutable; pins are never deleted.
CREATE FUNCTION protect_schedule_pin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Schedule pins cannot be deleted; remove the pin'; END IF;
  IF OLD.removed_at IS NOT NULL THEN RAISE EXCEPTION 'Removed schedule pins are immutable'; END IF;
  IF (NEW.tenant_id, NEW.order_id, NEW.operation_id, NEW.machine_id, NEW.start_at, NEW.reason, NEW.created_by,
      NEW.created_at) IS DISTINCT FROM
     (OLD.tenant_id, OLD.order_id, OLD.operation_id, OLD.machine_id, OLD.start_at, OLD.reason, OLD.created_by,
      OLD.created_at) THEN
    RAISE EXCEPTION 'A schedule pin cannot be changed; remove it and pin again';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER schedule_pin_guard BEFORE UPDATE OR DELETE ON schedule_pins
    FOR EACH ROW EXECUTE FUNCTION protect_schedule_pin();

-- The planner's remaining-duration estimate (working minutes) for a manufacturing order without a routing: such an
-- order is scheduled as one operation on its own work center for this many minutes; without an estimate it is
-- listed as "unscheduled - no routing". Never derived from rates the system does not have.
CREATE TABLE schedule_estimates (
    tenant_id text NOT NULL REFERENCES tenants(id),
    order_id text NOT NULL,
    minutes numeric(20,6) NOT NULL CHECK (minutes > 0 AND minutes <= 525600),
    notes text NOT NULL DEFAULT '' CHECK (length(notes) <= 400),
    updated_by text NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, order_id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES manufacturing_orders(tenant_id, id)
);
