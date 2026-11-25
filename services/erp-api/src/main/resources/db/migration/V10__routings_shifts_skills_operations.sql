-- V10: Phase 2 routings. Versioned routings with operations (approved revisions immutable, like V2 BOMs), the current
-- routing per product, shift calendars (patterns, calendars, exceptions, work-center assignment), a skill catalogue
-- with user skills, and manufacturing-order operations snapshotted from the current routing with their execution
-- records (time logs, quantity reports). No existing row is changed; orders without a routing keep working unchanged.
-- Designed for V13 scheduling, V14 genealogy and V15 OEE: explicit timestamps, machine ids, planned setup/run minutes
-- per operation and closed status sets.

-- ------------------------------------------------------------------ users of one tenant
-- Lets skill and operation rows reference a user of the same tenant through a composite key. IF NOT EXISTS: another
-- Phase 2 migration may add an equivalent index.
CREATE UNIQUE INDEX IF NOT EXISTS users_tenant_id_unique ON users(tenant_id, id);

-- ------------------------------------------------------------------ skills
CREATE TABLE skills (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    code text NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    description text NOT NULL DEFAULT '',
    active boolean NOT NULL DEFAULT true,
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

-- A user holds a skill at a level (1 = trainee ... 5 = expert). certified_until is the last valid day (inclusive, in
-- Asia/Colombo); NULL = no expiry.
CREATE TABLE user_skills (
    tenant_id text NOT NULL REFERENCES tenants(id),
    user_id text NOT NULL,
    skill_id text NOT NULL,
    level integer NOT NULL CHECK (level BETWEEN 1 AND 5),
    certified_until date,
    notes text NOT NULL DEFAULT '',
    updated_by text NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, skill_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id),
    FOREIGN KEY (tenant_id, skill_id) REFERENCES skills(tenant_id, id)
);
CREATE INDEX user_skills_skill ON user_skills(tenant_id, skill_id);

-- ------------------------------------------------------------------ routings
-- One routing revision per product (FINISHED_GOOD or SEMI_FINISHED) and revision text; DRAFT -> APPROVED by a person
-- other than its creator and last editor. Approved revisions and their operations are immutable (triggers below).
CREATE TABLE routings (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    product_id text NOT NULL,
    revision text NOT NULL CHECK (length(btrim(revision)) BETWEEN 1 AND 30),
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED')),
    notes text NOT NULL DEFAULT '',
    source_routing_id text,
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by text NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    approved_by text REFERENCES users(id),
    approved_at timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    UNIQUE (tenant_id, product_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES items(tenant_id, id),
    FOREIGN KEY (tenant_id, source_routing_id) REFERENCES routings(tenant_id, id),
    CHECK ((status = 'APPROVED') = (approved_at IS NOT NULL AND approved_by IS NOT NULL))
);
CREATE UNIQUE INDEX routings_product_revision ON routings(tenant_id, product_id, lower(revision));
CREATE INDEX routings_tenant_created ON routings(tenant_id, created_at DESC, id DESC);
CREATE INDEX routings_tenant_status ON routings(tenant_id, status, created_at DESC);

-- Operations: sequence number (unique per routing), work center (machine), planned setup minutes per run and run
-- minutes per base unit of the product (numeric(24,12): 1,200 pcs/h = 0.05), crew size (people tending the operation;
-- labour minutes = machine minutes x crew), optional required skill and minimum level, inspection-point flag and
-- instructions.
CREATE TABLE routing_operations (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    routing_id text NOT NULL,
    seq integer NOT NULL CHECK (seq BETWEEN 1 AND 9999),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    machine_id text NOT NULL,
    setup_minutes numeric(20,6) NOT NULL DEFAULT 0 CHECK (setup_minutes >= 0),
    run_minutes_per_unit numeric(24,12) NOT NULL DEFAULT 0 CHECK (run_minutes_per_unit >= 0),
    crew_size numeric(6,2) NOT NULL DEFAULT 1 CHECK (crew_size > 0),
    skill_id text,
    skill_level integer NOT NULL DEFAULT 1 CHECK (skill_level BETWEEN 1 AND 5),
    inspection_point boolean NOT NULL DEFAULT false,
    instructions text NOT NULL DEFAULT '',
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, routing_id, seq),
    FOREIGN KEY (tenant_id, routing_id) REFERENCES routings(tenant_id, id),
    FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    FOREIGN KEY (tenant_id, skill_id) REFERENCES skills(tenant_id, id)
);
CREATE INDEX routing_operations_machine ON routing_operations(tenant_id, machine_id);

-- Alternate work centers of an operation (same machine kind as the primary, checked by the service).
CREATE TABLE routing_operation_alternates (
    tenant_id text NOT NULL REFERENCES tenants(id),
    operation_id text NOT NULL,
    machine_id text NOT NULL,
    PRIMARY KEY (tenant_id, operation_id, machine_id),
    FOREIGN KEY (tenant_id, operation_id) REFERENCES routing_operations(tenant_id, id),
    FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id)
);
CREATE INDEX routing_operation_alternates_machine ON routing_operation_alternates(tenant_id, machine_id);

-- The current routing revision of a product (at most one): set to the newly approved revision on approval, or to an
-- older approved revision by "make current". New manufacturing orders snapshot this revision.
CREATE TABLE routing_defaults (
    tenant_id text NOT NULL REFERENCES tenants(id),
    product_id text NOT NULL,
    routing_id text NOT NULL,
    updated_by text NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, product_id),
    FOREIGN KEY (tenant_id, product_id, routing_id) REFERENCES routings(tenant_id, product_id, id)
);
CREATE INDEX routing_defaults_routing ON routing_defaults(tenant_id, routing_id);

CREATE FUNCTION protect_approved_routing() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'APPROVED' THEN
    RAISE EXCEPTION 'Approved routings are immutable; copy the revision into a new draft';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER approved_routing_immutable BEFORE UPDATE OR DELETE ON routings
    FOR EACH ROW EXECUTE FUNCTION protect_approved_routing();

CREATE FUNCTION protect_approved_routing_operation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_status text;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    SELECT status INTO owner_status FROM routings WHERE tenant_id = OLD.tenant_id AND id = OLD.routing_id;
    IF owner_status = 'APPROVED' THEN RAISE EXCEPTION 'Operations of an approved routing are immutable'; END IF;
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    SELECT status INTO owner_status FROM routings WHERE tenant_id = NEW.tenant_id AND id = NEW.routing_id;
    IF owner_status = 'APPROVED' THEN RAISE EXCEPTION 'Operations of an approved routing are immutable'; END IF;
    RETURN NEW;
  END IF;
  RETURN OLD;
END; $$;
CREATE TRIGGER approved_routing_operation_immutable BEFORE INSERT OR UPDATE OR DELETE ON routing_operations
    FOR EACH ROW EXECUTE FUNCTION protect_approved_routing_operation();

CREATE FUNCTION protect_approved_routing_alternate() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_status text;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    SELECT r.status INTO owner_status FROM routing_operations o
      JOIN routings r ON r.tenant_id = o.tenant_id AND r.id = o.routing_id
      WHERE o.tenant_id = OLD.tenant_id AND o.id = OLD.operation_id;
    IF owner_status = 'APPROVED' THEN RAISE EXCEPTION 'Operations of an approved routing are immutable'; END IF;
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    SELECT r.status INTO owner_status FROM routing_operations o
      JOIN routings r ON r.tenant_id = o.tenant_id AND r.id = o.routing_id
      WHERE o.tenant_id = NEW.tenant_id AND o.id = NEW.operation_id;
    IF owner_status = 'APPROVED' THEN RAISE EXCEPTION 'Operations of an approved routing are immutable'; END IF;
    RETURN NEW;
  END IF;
  RETURN OLD;
END; $$;
CREATE TRIGGER approved_routing_alternate_immutable BEFORE INSERT OR UPDATE OR DELETE ON routing_operation_alternates
    FOR EACH ROW EXECUTE FUNCTION protect_approved_routing_alternate();

CREATE FUNCTION routing_default_must_be_approved() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM routings WHERE tenant_id = NEW.tenant_id AND id = NEW.routing_id AND status = 'APPROVED') THEN
    RAISE EXCEPTION 'Only an approved routing can be the current revision of a product';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER routing_default_approved BEFORE INSERT OR UPDATE ON routing_defaults
    FOR EACH ROW EXECUTE FUNCTION routing_default_must_be_approved();

-- ------------------------------------------------------------------ shift calendars
-- A shift pattern is one shift on some ISO weekdays (bit 0 = Monday ... bit 6 = Sunday). end_time <= start_time means
-- the shift ends on the next day (overnight); a shift belongs to the date it starts on. One unpaid break per shift:
-- break_minutes starting at break_start (NULL = centred in the shift). Times are local Asia/Colombo times.
CREATE TABLE shift_patterns (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    code text NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    start_time time NOT NULL,
    end_time time NOT NULL,
    break_start time,
    break_minutes integer NOT NULL DEFAULT 0 CHECK (break_minutes >= 0 AND break_minutes < 1440),
    weekdays integer NOT NULL CHECK (weekdays BETWEEN 1 AND 127),
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

-- A calendar is a set of shift patterns plus dated exceptions. At most one calendar per tenant is the plant default,
-- used by every work center without an assigned calendar.
CREATE TABLE shift_calendars (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    code text NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    plant_default boolean NOT NULL DEFAULT false,
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);
CREATE UNIQUE INDEX shift_calendars_one_plant_default ON shift_calendars(tenant_id) WHERE plant_default;

CREATE TABLE shift_calendar_patterns (
    tenant_id text NOT NULL REFERENCES tenants(id),
    calendar_id text NOT NULL,
    pattern_id text NOT NULL,
    PRIMARY KEY (tenant_id, calendar_id, pattern_id),
    FOREIGN KEY (tenant_id, calendar_id) REFERENCES shift_calendars(tenant_id, id),
    FOREIGN KEY (tenant_id, pattern_id) REFERENCES shift_patterns(tenant_id, id)
);
CREATE INDEX shift_calendar_patterns_pattern ON shift_calendar_patterns(tenant_id, pattern_id);

-- CLOSED (holiday): no regular shift starts on that date. OVERTIME: extra working time starting on that date
-- (end_time <= start_time = ends the next day), added even on a closed date; overlaps with shifts count once.
CREATE TABLE shift_calendar_exceptions (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    calendar_id text NOT NULL,
    exception_date date NOT NULL,
    kind text NOT NULL CHECK (kind IN ('CLOSED', 'OVERTIME')),
    start_time time,
    end_time time,
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 200),
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, calendar_id) REFERENCES shift_calendars(tenant_id, id),
    CHECK ((kind = 'CLOSED' AND start_time IS NULL AND end_time IS NULL)
        OR (kind = 'OVERTIME' AND start_time IS NOT NULL AND end_time IS NOT NULL AND start_time <> end_time))
);
CREATE UNIQUE INDEX shift_calendar_exceptions_one_closed ON shift_calendar_exceptions(tenant_id, calendar_id, exception_date)
    WHERE kind = 'CLOSED';
CREATE INDEX shift_calendar_exceptions_date ON shift_calendar_exceptions(tenant_id, calendar_id, exception_date);

-- The calendar of a work center; no row = the plant default calendar (no plant default = available around the clock).
CREATE TABLE work_center_calendars (
    tenant_id text NOT NULL REFERENCES tenants(id),
    machine_id text NOT NULL,
    calendar_id text NOT NULL,
    updated_by text NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, machine_id),
    FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    FOREIGN KEY (tenant_id, calendar_id) REFERENCES shift_calendars(tenant_id, id)
);
CREATE INDEX work_center_calendars_calendar ON work_center_calendars(tenant_id, calendar_id);

-- ------------------------------------------------------------------ manufacturing-order operations
-- The routing revision an order snapshotted at creation (NULL = an order without a routing: single implicit operation).
ALTER TABLE manufacturing_orders
    ADD COLUMN routing_id text,
    ADD COLUMN routing_revision text,
    ADD CONSTRAINT manufacturing_orders_routing_fkey FOREIGN KEY (tenant_id, routing_id) REFERENCES routings(tenant_id, id);
CREATE INDEX mo_routing ON manufacturing_orders(tenant_id, routing_id) WHERE routing_id IS NOT NULL;

-- One row per routing operation, frozen when the order is created. PENDING (waiting for material release or the
-- previous operation) -> READY -> IN_PROGRESS <-> PAUSED -> DONE; CANCELLED with the order. machine_id is the work
-- center that runs (or ran) the operation: the planned one or an alternate chosen at start. Planned minutes are for
-- planned_quantity (the order quantity): planned_run_minutes = planned_quantity x run_minutes_per_unit (6 dp).
CREATE TABLE mo_operations (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    order_id text NOT NULL,
    routing_operation_id text NOT NULL,
    seq integer NOT NULL,
    name text NOT NULL,
    machine_id text NOT NULL,
    planned_machine_id text NOT NULL,
    planned_quantity numeric(20,6) NOT NULL CHECK (planned_quantity > 0),
    setup_minutes numeric(20,6) NOT NULL CHECK (setup_minutes >= 0),
    run_minutes_per_unit numeric(24,12) NOT NULL CHECK (run_minutes_per_unit >= 0),
    planned_run_minutes numeric(20,6) NOT NULL CHECK (planned_run_minutes >= 0),
    planned_minutes numeric(20,6) GENERATED ALWAYS AS (setup_minutes + planned_run_minutes) STORED,
    crew_size numeric(6,2) NOT NULL CHECK (crew_size > 0),
    skill_id text,
    skill_level integer NOT NULL DEFAULT 1 CHECK (skill_level BETWEEN 1 AND 5),
    inspection_point boolean NOT NULL DEFAULT false,
    instructions text NOT NULL DEFAULT '',
    status text NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'READY', 'IN_PROGRESS', 'PAUSED', 'DONE', 'CANCELLED')),
    activity text CHECK (activity IN ('SETUP', 'RUN')),
    good_qty numeric(20,6) NOT NULL DEFAULT 0 CHECK (good_qty >= 0),
    scrap_qty numeric(20,6) NOT NULL DEFAULT 0 CHECK (scrap_qty >= 0),
    operator_id text,
    ready_at timestamptz,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, order_id, id),
    UNIQUE (tenant_id, order_id, seq),
    FOREIGN KEY (tenant_id, order_id) REFERENCES manufacturing_orders(tenant_id, id),
    FOREIGN KEY (tenant_id, routing_operation_id) REFERENCES routing_operations(tenant_id, id),
    FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    FOREIGN KEY (tenant_id, planned_machine_id) REFERENCES machines(tenant_id, id),
    FOREIGN KEY (tenant_id, skill_id) REFERENCES skills(tenant_id, id),
    FOREIGN KEY (tenant_id, operator_id) REFERENCES users(tenant_id, id),
    CHECK ((status = 'IN_PROGRESS') = (activity IS NOT NULL)),
    CHECK (status NOT IN ('IN_PROGRESS', 'PAUSED', 'DONE') OR started_at IS NOT NULL),
    CHECK ((status = 'DONE') = (finished_at IS NOT NULL))
);
CREATE INDEX mo_operations_queue ON mo_operations(tenant_id, machine_id, status);
CREATE INDEX mo_operations_status ON mo_operations(tenant_id, status);

-- The plan is a snapshot: only execution columns change, finished/cancelled operations never change, nothing is
-- deleted.
CREATE FUNCTION protect_mo_operation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Manufacturing-order operations cannot be deleted'; END IF;
  IF OLD.status IN ('DONE', 'CANCELLED') THEN
    RAISE EXCEPTION 'Finished and cancelled operations are immutable';
  END IF;
  IF NEW.tenant_id <> OLD.tenant_id OR NEW.order_id <> OLD.order_id
     OR NEW.routing_operation_id <> OLD.routing_operation_id OR NEW.seq <> OLD.seq
     OR NEW.planned_machine_id <> OLD.planned_machine_id OR NEW.planned_quantity <> OLD.planned_quantity
     OR NEW.setup_minutes <> OLD.setup_minutes OR NEW.run_minutes_per_unit <> OLD.run_minutes_per_unit
     OR NEW.planned_run_minutes <> OLD.planned_run_minutes OR NEW.crew_size <> OLD.crew_size
     OR NEW.skill_id IS DISTINCT FROM OLD.skill_id OR NEW.skill_level <> OLD.skill_level THEN
    RAISE EXCEPTION 'The planned operation is a snapshot of the routing and cannot be changed';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER mo_operation_snapshot BEFORE UPDATE OR DELETE ON mo_operations
    FOR EACH ROW EXECUTE FUNCTION protect_mo_operation();

-- Actual machine time per operation: one row per uninterrupted SETUP or RUN interval on a work center, opened by
-- start/resume/setup-done and closed by pause/finish/setup-done/order pause. minutes = machine minutes;
-- labour_minutes = minutes x crew size. At most one open interval per operation and per work center. Closed rows are
-- immutable (V15 OEE computes run time from them).
CREATE TABLE mo_operation_time_logs (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    operation_id text NOT NULL,
    order_id text NOT NULL,
    machine_id text NOT NULL,
    user_id text NOT NULL,
    activity text NOT NULL CHECK (activity IN ('SETUP', 'RUN')),
    crew_size numeric(6,2) NOT NULL CHECK (crew_size > 0),
    started_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz,
    end_reason text CHECK (end_reason IN ('PAUSED', 'FINISHED', 'SWITCHED', 'ORDER_PAUSED')),
    minutes numeric(20,6) GENERATED ALWAYS AS
        (round((extract(epoch FROM (ended_at - started_at)) / 60)::numeric, 6)) STORED,
    labour_minutes numeric(20,6) GENERATED ALWAYS AS
        (round((extract(epoch FROM (ended_at - started_at)) / 60)::numeric * crew_size, 6)) STORED,
    skill_override_reason text,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, order_id, operation_id) REFERENCES mo_operations(tenant_id, order_id, id),
    FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id),
    CHECK (ended_at IS NULL OR ended_at >= started_at),
    CHECK ((ended_at IS NULL) = (end_reason IS NULL))
);
CREATE UNIQUE INDEX mo_time_logs_one_open_per_operation ON mo_operation_time_logs(tenant_id, operation_id)
    WHERE ended_at IS NULL;
CREATE UNIQUE INDEX mo_time_logs_one_open_per_machine ON mo_operation_time_logs(tenant_id, machine_id)
    WHERE ended_at IS NULL;
CREATE INDEX mo_time_logs_tenant_started ON mo_operation_time_logs(tenant_id, started_at DESC, id DESC);
CREATE INDEX mo_time_logs_machine_started ON mo_operation_time_logs(tenant_id, machine_id, started_at DESC, id DESC);
CREATE INDEX mo_time_logs_operation ON mo_operation_time_logs(tenant_id, operation_id, started_at);
CREATE INDEX mo_time_logs_order ON mo_operation_time_logs(tenant_id, order_id, started_at);

CREATE FUNCTION protect_closed_time_log() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' OR OLD.ended_at IS NOT NULL THEN
    RAISE EXCEPTION 'Closed operation time logs are immutable';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER mo_time_log_closed_immutable BEFORE UPDATE OR DELETE ON mo_operation_time_logs
    FOR EACH ROW EXECUTE FUNCTION protect_closed_time_log();

-- Good/scrap quantities reported on an operation (cumulative totals on mo_operations). Execution records only: stock
-- and cost still move through the order's output posting. Posted history.
CREATE TABLE mo_operation_reports (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    operation_id text NOT NULL,
    order_id text NOT NULL,
    machine_id text NOT NULL,
    good_qty numeric(20,6) NOT NULL CHECK (good_qty >= 0),
    scrap_qty numeric(20,6) NOT NULL CHECK (scrap_qty >= 0),
    notes text NOT NULL DEFAULT '',
    reported_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, order_id, operation_id) REFERENCES mo_operations(tenant_id, order_id, id),
    FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    FOREIGN KEY (tenant_id, reported_by) REFERENCES users(tenant_id, id),
    CHECK (good_qty + scrap_qty > 0)
);
CREATE INDEX mo_operation_reports_operation ON mo_operation_reports(tenant_id, operation_id, created_at);
CREATE INDEX mo_operation_reports_machine ON mo_operation_reports(tenant_id, machine_id, created_at DESC);
CREATE TRIGGER mo_operation_reports_immutable BEFORE UPDATE OR DELETE ON mo_operation_reports
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
