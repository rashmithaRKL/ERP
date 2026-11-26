-- V15: OEE and utilization. OEE is computed on the fly from the execution records (V10 operation time logs and
-- quantity reports, V11 effective downtime, V12 scrap dispositions and rework, Phase 1 production actuals) and the
-- shift calendar (routing.CalendarService). Calendars keep no history, so a completed business day (Asia/Colombo) of
-- one work center is frozen by closing it: the snapshot stores the working time it was computed with and every figure.
-- Re-closing is an explicit, reasoned recalculation that keeps the earlier version (SUPERSEDED). No existing table or
-- row is changed.

CREATE TABLE oee_day_snapshots (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    machine_id text NOT NULL,
    day date NOT NULL,
    version integer NOT NULL CHECK (version >= 1),
    status text NOT NULL DEFAULT 'CURRENT' CHECK (status IN ('CURRENT', 'SUPERSEDED')),
    -- Version of the calculation method (performance.OeeCalculator.METHOD) the figures were computed with.
    method integer NOT NULL CHECK (method >= 1),
    -- MEASURED, NOT_SCHEDULED (no planned production time: holiday, no shift, whole day planned stop), NO_DATA
    -- (planned time but nothing recorded) or UNTIMED (only output of orders without a routing: no time records).
    oee_status text NOT NULL CHECK (oee_status IN ('MEASURED', 'NOT_SCHEDULED', 'NO_DATA', 'UNTIMED')),
    calendar_source text NOT NULL CHECK (calendar_source IN ('ASSIGNED', 'PLANT_DEFAULT', 'NONE')),
    calendar_code text,
    -- Minutes (four decimals): calendar day, shift working time, planned stops, planned production time, run time,
    -- setup + changeover, breakdowns, other unplanned stops, unrecorded idle, net run and fully productive time.
    calendar_minutes numeric(12,4) NOT NULL CHECK (calendar_minutes >= 0),
    scheduled_minutes numeric(12,4) NOT NULL CHECK (scheduled_minutes >= 0),
    planned_stop_minutes numeric(12,4) NOT NULL CHECK (planned_stop_minutes >= 0),
    planned_minutes numeric(12,4) NOT NULL CHECK (planned_minutes >= 0),
    run_minutes numeric(12,4) NOT NULL CHECK (run_minutes >= 0),
    setup_minutes numeric(12,4) NOT NULL CHECK (setup_minutes >= 0),
    breakdown_minutes numeric(12,4) NOT NULL CHECK (breakdown_minutes >= 0),
    other_stop_minutes numeric(12,4) NOT NULL CHECK (other_stop_minutes >= 0),
    unrecorded_minutes numeric(12,4) NOT NULL CHECK (unrecorded_minutes >= 0),
    net_run_minutes numeric(12,4),
    fully_productive_minutes numeric(12,4),
    good_count numeric(20,6) NOT NULL CHECK (good_count >= 0),
    total_count numeric(20,6) NOT NULL CHECK (total_count >= good_count),
    -- Ratios 0..1 (performance is capped at 1; the raw value is kept in figures); NULL = not measurable.
    availability numeric(9,6) CHECK (availability BETWEEN 0 AND 1),
    performance numeric(9,6) CHECK (performance BETWEEN 0 AND 1),
    quality numeric(9,6) CHECK (quality BETWEEN 0 AND 1),
    oee numeric(9,6) CHECK (oee BETWEEN 0 AND 1),
    -- The complete figures in the read-model shape (working intervals, stops, operations, losses, warnings).
    figures jsonb NOT NULL CHECK (jsonb_typeof(figures) = 'object'),
    -- Why a day was recalculated (versions after the first).
    reason text NOT NULL DEFAULT '',
    supersedes_id text,
    closed_by text NOT NULL REFERENCES users(id),
    closed_at timestamptz NOT NULL DEFAULT now(),
    superseded_at timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, machine_id, day, version),
    FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    FOREIGN KEY (tenant_id, supersedes_id) REFERENCES oee_day_snapshots(tenant_id, id),
    CHECK ((version = 1) = (supersedes_id IS NULL)),
    CHECK (version = 1 OR length(btrim(reason)) >= 5),
    CHECK ((status = 'SUPERSEDED') = (superseded_at IS NOT NULL)),
    CHECK ((oee_status = 'MEASURED') OR oee IS NULL)
);
-- At most one current version per work center and day.
CREATE UNIQUE INDEX oee_day_snapshots_current ON oee_day_snapshots(tenant_id, machine_id, day) WHERE status = 'CURRENT';
CREATE INDEX oee_day_snapshots_tenant_day ON oee_day_snapshots(tenant_id, day, machine_id) WHERE status = 'CURRENT';
CREATE INDEX oee_day_snapshots_history ON oee_day_snapshots(tenant_id, machine_id, day, version DESC);

-- Snapshots are history: never deleted; a CURRENT row may only become SUPERSEDED (with superseded_at, every other
-- column unchanged); SUPERSEDED rows are frozen.
CREATE FUNCTION protect_oee_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'Closed OEE days cannot be deleted; recalculate the day instead';
  END IF;
  IF OLD.status = 'SUPERSEDED' THEN
    RAISE EXCEPTION 'Superseded OEE snapshots are immutable';
  END IF;
  IF NEW.status <> 'SUPERSEDED' OR NEW.superseded_at IS NULL
     OR (to_jsonb(NEW) - 'status' - 'superseded_at') IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'superseded_at') THEN
    RAISE EXCEPTION 'A closed OEE day is immutable; recalculate it with a reason to record a new version';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER oee_snapshot_guard BEFORE UPDATE OR DELETE ON oee_day_snapshots
    FOR EACH ROW EXECUTE FUNCTION protect_oee_snapshot();

-- Read-model support: production postings and operation reports per work center and day are looked up by time.
CREATE INDEX IF NOT EXISTS production_actuals_tenant_created ON production_actuals(tenant_id, created_at);
