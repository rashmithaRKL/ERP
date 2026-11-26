-- V16 Phase 2 integration.
--
-- Machine status history (V11 machine_status_changes) now also records the production-driven RUNNING/IDLE changes:
-- OPERATION = a routed operation started, resumed, paused or finished (or its order was put on hold); ORDER = an order
-- without a routing started, resumed, was put on hold or posted its final output. The table stays append-only
-- (machine_status_changes_immutable); existing rows and the to_status rule are unchanged.
ALTER TABLE machine_status_changes DROP CONSTRAINT machine_status_changes_source_check;
ALTER TABLE machine_status_changes ADD CONSTRAINT machine_status_changes_source_check
  CHECK (source IN ('MAINTENANCE_ORDER', 'MANUAL', 'OPERATION', 'ORDER'));
