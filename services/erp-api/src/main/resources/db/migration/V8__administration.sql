-- Administration: user lifecycle (deactivation, session revocation, temporary passwords, last sign-in), approval
-- policies per document type (stock adjustments migrated from V5 inventory_settings), CSV import batches, accounting
-- period administration and audit-trail paging indexes.

-- ---------------------------------------------------------------- users
ALTER TABLE users ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE users ADD COLUMN updated_at timestamptz;
ALTER TABLE users ADD COLUMN last_login_at timestamptz;
ALTER TABLE users ADD COLUMN password_changed_at timestamptz;
ALTER TABLE users ADD COLUMN deactivated_at timestamptz;
-- Set by an administrator reset (and for new accounts): every request except sign-out, identity and the password
-- change itself is refused until the user chooses a new password.
ALTER TABLE users ADD COLUMN must_change_password boolean NOT NULL DEFAULT false;
-- Copied into the session principal at sign-in; incremented on deactivation, password reset and password change. A
-- session whose principal carries an older version is invalidated on its next request (platform.SessionGuard).
ALTER TABLE users ADD COLUMN session_version integer NOT NULL DEFAULT 0 CHECK (session_version >= 0);
CREATE INDEX users_tenant_name ON users(tenant_id, lower(name), id);

-- Every tenant keeps at least one active administrator. The services check this first with a readable message; the
-- trigger is the last line of defence.
CREATE FUNCTION keep_an_active_admin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.role = 'ADMIN' AND OLD.active AND (NEW.role <> 'ADMIN' OR NOT NEW.active)
     AND NOT EXISTS (SELECT 1 FROM users WHERE tenant_id = OLD.tenant_id AND id <> OLD.id AND role = 'ADMIN' AND active) THEN
    RAISE EXCEPTION 'A workspace needs at least one active administrator';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER users_keep_an_active_admin BEFORE UPDATE OF role, active ON users
  FOR EACH ROW EXECUTE FUNCTION keep_an_active_admin();

-- ---------------------------------------------------------------- approval policies
-- One row per tenant and document type; a missing row means the built-in default (admin.ApprovalPolicyService):
-- manufacturing and purchase orders always need a second PLANNER/ADMIN, stock adjustments above Rs. 50,000 need a
-- second PLANNER/WAREHOUSE/ADMIN. amount_threshold NULL = every document. ADMIN may always approve and nobody may
-- approve a document they created (not configurable).
CREATE TABLE approval_policies (
    tenant_id text NOT NULL REFERENCES tenants(id),
    document_type text NOT NULL CHECK (document_type IN ('MANUFACTURING_ORDER', 'PURCHASE_ORDER', 'STOCK_ADJUSTMENT')),
    approval_required boolean NOT NULL,
    amount_threshold numeric(20,6) CHECK (amount_threshold >= 0),
    approver_roles text NOT NULL
        CHECK (approver_roles ~ '^((PLANNER|WAREHOUSE|QUALITY|SALES|OPERATOR)(,(PLANNER|WAREHOUSE|QUALITY|SALES|OPERATOR))*)?$'),
    updated_by text REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, document_type));

-- V5 kept the adjustment threshold in inventory_settings; carry every tenant's value (and who set it) over.
INSERT INTO approval_policies(tenant_id, document_type, approval_required, amount_threshold, approver_roles, updated_by, updated_at)
SELECT tenant_id, 'STOCK_ADJUSTMENT', true, approval_threshold, 'PLANNER,WAREHOUSE', updated_by, updated_at
FROM inventory_settings;
DROP TABLE inventory_settings;

-- ---------------------------------------------------------------- CSV imports
-- One row per committed file. (tenant, kind, content hash) is unique: the same file is never imported twice.
CREATE TABLE import_batches (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('ITEMS', 'PARTNERS', 'OPENING_STOCK')),
    file_name text NOT NULL,
    content_hash text NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    row_count integer NOT NULL CHECK (row_count > 0),
    total_value numeric(20,6) NOT NULL DEFAULT 0 CHECK (total_value >= 0),
    summary text NOT NULL DEFAULT '',
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    UNIQUE (tenant_id, kind, content_hash));
CREATE INDEX import_batches_tenant_created ON import_batches(tenant_id, created_at DESC, id DESC);
CREATE TRIGGER import_batches_immutable BEFORE UPDATE OR DELETE ON import_batches
  FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- ---------------------------------------------------------------- accounting periods
-- A period ends on end_date and starts the day after the previous row's end_date. Store.openPeriod (unchanged) blocks
-- postings while a closed period covers the current date.
ALTER TABLE financial_periods ADD COLUMN closed_by text REFERENCES users(id);
ALTER TABLE financial_periods ADD COLUMN closed_at timestamptz;
ALTER TABLE financial_periods ADD COLUMN reopened_by text REFERENCES users(id);
ALTER TABLE financial_periods ADD COLUMN reopened_at timestamptz;
ALTER TABLE financial_periods ADD COLUMN reopen_reason text;
ALTER TABLE financial_periods ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();

-- ---------------------------------------------------------------- audit trail paging (keyset on created_at, id)
DROP INDEX audit_tenant_created;
CREATE INDEX audit_tenant_created_id ON audit_events(tenant_id, created_at DESC, id DESC);
CREATE INDEX audit_tenant_action ON audit_events(tenant_id, action, created_at DESC);
CREATE INDEX audit_tenant_actor ON audit_events(tenant_id, actor, created_at DESC);
