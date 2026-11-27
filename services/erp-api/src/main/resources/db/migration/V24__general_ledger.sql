-- V24 Phase 4 general ledger: a FINANCE (accountant) role, a chart of accounts per tenant (types, hierarchy, system
-- accounts for every code the posting code uses, reserved codes for later Phase 4 features), posting dates and sources
-- on journal entries, manual journals (JV-...) with independent approval through a MANUAL_JOURNAL approval policy, and
-- reversals of posted entries.
--
-- Money still moves only through balanced journal entries. Every journal line now names an account of the tenant's
-- chart (deferred composite foreign key), never an inactive or header account (trigger), and no entry is dated inside
-- a closed accounting period (trigger). Posted entries stay immutable (journal_history_immutable, journal_lines_immutable
-- cover the new columns as well); corrections are REVERSAL entries, at most one per entry.
--
-- Read by later Phase 4 features (V25 taxes, V26 receivables, V27 payables, V28 statements): gl_accounts (code, type,
-- normal_balance, parent_id, postable, active, system), journal_entries.posting_date / source / reverses_entry_id and
-- the reserved system codes OUTPUT_TAX, INPUT_TAX, BANK, CASH, RETAINED_EARNINGS, ROUNDING, SALES_RETURNS,
-- CUSTOMER_ADVANCES and SUPPLIER_ADVANCES.

-- ------------------------------------------------------------------ FINANCE role
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
  CHECK (role IN ('ADMIN','PLANNER','WAREHOUSE','OPERATOR','QUALITY','SALES','VIEWER','MAINTENANCE','FINANCE'));

-- ------------------------------------------------------------------ approval policy for manual journals
-- No stored row = the built-in default (approval required for every manual journal, approvers FINANCE and ADMIN).
ALTER TABLE approval_policies DROP CONSTRAINT approval_policies_document_type_check;
ALTER TABLE approval_policies ADD CONSTRAINT approval_policies_document_type_check
  CHECK (document_type IN ('MANUFACTURING_ORDER','PURCHASE_ORDER','STOCK_ADJUSTMENT','MANUAL_JOURNAL'));
ALTER TABLE approval_policies DROP CONSTRAINT approval_policies_approver_roles_check;
ALTER TABLE approval_policies ADD CONSTRAINT approval_policies_approver_roles_check
  CHECK (approver_roles ~ '^((PLANNER|WAREHOUSE|QUALITY|SALES|OPERATOR|FINANCE)(,(PLANNER|WAREHOUSE|QUALITY|SALES|OPERATOR|FINANCE))*)?$');

-- ------------------------------------------------------------------ chart of accounts
-- code is the posting key (journal_lines.account). normal_balance follows the type: ASSET and EXPENSE are debit
-- accounts, LIABILITY, EQUITY and INCOME credit accounts; a contra account (e.g. SALES_RETURNS) has the opposite side.
-- Header accounts (postable = false) group accounts of the same type for reporting and never receive postings.
-- system = a code the posting code uses: it stays active and postable and keeps its code and type (name editable).
-- needs_review = an account created from a code found in posted data that the standard chart does not know.
CREATE TABLE gl_accounts (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  code text NOT NULL,
  name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
  type text NOT NULL CHECK (type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
  contra boolean NOT NULL DEFAULT false,
  normal_balance text GENERATED ALWAYS AS
    (CASE WHEN (type IN ('ASSET','EXPENSE')) <> contra THEN 'DEBIT' ELSE 'CREDIT' END) STORED,
  parent_id text,
  postable boolean NOT NULL DEFAULT true,
  active boolean NOT NULL DEFAULT true,
  system boolean NOT NULL DEFAULT false,
  needs_review boolean NOT NULL DEFAULT false,
  description text NOT NULL DEFAULT '' CHECK (length(description) <= 400),
  created_by text REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_by text REFERENCES users(id),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, code),
  FOREIGN KEY (tenant_id, parent_id) REFERENCES gl_accounts(tenant_id, id),
  CHECK (parent_id IS NULL OR parent_id <> id),
  CHECK (NOT system OR (active AND postable))
);
CREATE INDEX gl_accounts_tenant_parent ON gl_accounts(tenant_id, parent_id);

-- Accounts are never deleted (deactivate instead); system accounts keep code and type; a parent is a header account of
-- the same type and the hierarchy has no cycles; an account with sub-accounts stays a header.
CREATE FUNCTION protect_gl_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_parent gl_accounts%ROWTYPE;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'Account % is part of the chart of accounts and cannot be deleted; deactivate it instead', OLD.code;
  END IF;
  IF TG_OP = 'UPDATE' THEN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id THEN
      RAISE EXCEPTION 'An account keeps its identity';
    END IF;
    IF OLD.system AND (NEW.code, NEW.type, NEW.contra, NEW.system) IS DISTINCT FROM (OLD.code, OLD.type, OLD.contra, OLD.system) THEN
      RAISE EXCEPTION 'Account % is used by system postings: its code and type cannot change', OLD.code;
    END IF;
    IF NEW.postable AND EXISTS (SELECT 1 FROM gl_accounts c WHERE c.tenant_id = NEW.tenant_id AND c.parent_id = NEW.id) THEN
      RAISE EXCEPTION 'Account % has sub-accounts, so it must stay a header account', NEW.code;
    END IF;
    IF NEW.type <> OLD.type AND EXISTS (SELECT 1 FROM gl_accounts c WHERE c.tenant_id = NEW.tenant_id AND c.parent_id = NEW.id) THEN
      RAISE EXCEPTION 'Account % has sub-accounts of type %; move them first', NEW.code, OLD.type;
    END IF;
  END IF;
  IF NEW.parent_id IS NOT NULL THEN
    SELECT * INTO v_parent FROM gl_accounts WHERE tenant_id = NEW.tenant_id AND id = NEW.parent_id;
    IF v_parent.postable THEN
      RAISE EXCEPTION 'The parent of % must be a header account', NEW.code;
    END IF;
    IF v_parent.type <> NEW.type THEN
      RAISE EXCEPTION 'The parent of % must be a % header account', NEW.code, NEW.type;
    END IF;
    IF TG_OP = 'UPDATE' AND EXISTS (
        WITH RECURSIVE up(id, parent_id) AS (
          SELECT id, parent_id FROM gl_accounts WHERE tenant_id = NEW.tenant_id AND id = NEW.parent_id
          UNION
          SELECT g.id, g.parent_id FROM gl_accounts g JOIN up ON g.tenant_id = NEW.tenant_id AND g.id = up.parent_id)
        SELECT 1 FROM up WHERE up.id = NEW.id) THEN
      RAISE EXCEPTION 'Account % cannot be placed under one of its own sub-accounts', NEW.code;
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER gl_account_guard BEFORE INSERT OR UPDATE OR DELETE ON gl_accounts
  FOR EACH ROW EXECUTE FUNCTION protect_gl_account();

-- The standard chart: header accounts, every code the posting code uses (system) and the codes reserved for later
-- Phase 4 features (system as well, so they can never be deactivated before those features post to them).
CREATE FUNCTION seed_chart_of_accounts(p_tenant text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO gl_accounts(id, tenant_id, code, name, type, contra, postable, system, description)
  SELECT gen_random_uuid()::text, p_tenant, c.code, c.name, c.type, c.contra, c.postable, c.system, c.description
  FROM (VALUES
    -- headers
    ('ASSETS', 'Assets', 'ASSET', false, false, false, ''),
    ('INVENTORIES', 'Inventories', 'ASSET', false, false, false, 'Stock valued through the stock ledger'),
    ('LIABILITIES', 'Liabilities', 'LIABILITY', false, false, false, ''),
    ('COST_CLEARING', 'Cost clearing', 'LIABILITY', false, false, false, 'Costs absorbed at standard rates, cleared when the actual cost is booked'),
    ('EQUITY', 'Equity', 'EQUITY', false, false, false, ''),
    ('INCOME', 'Income', 'INCOME', false, false, false, ''),
    ('EXPENSES', 'Expenses', 'EXPENSE', false, false, false, ''),
    -- accounts the posting code uses
    ('RAW_INVENTORY', 'Materials inventory', 'ASSET', false, true, true, 'Raw materials, packaging, semi-finished goods and spare parts'),
    ('WIP', 'Work in progress', 'ASSET', false, true, true, 'Materials and conversion cost of open manufacturing orders'),
    ('REWORK_WIP', 'Rework in progress', 'ASSET', false, true, true, 'Nonconforming stock, material and labour on open rework orders'),
    ('FG_INVENTORY', 'Finished goods inventory', 'ASSET', false, true, true, ''),
    ('ACCOUNTS_RECEIVABLE', 'Accounts receivable', 'ASSET', false, true, true, 'Recognized on dispatch'),
    ('ACCOUNTS_PAYABLE', 'Accounts payable', 'LIABILITY', false, true, true, 'Matched supplier invoices less debit notes'),
    ('GRNI', 'Goods received not invoiced', 'LIABILITY', false, true, true, 'Receipts awaiting the supplier invoice'),
    ('CONVERSION_CLEARING', 'Conversion cost clearing', 'LIABILITY', false, true, true, 'Labour and machine cost applied to production and rework'),
    ('MAINTENANCE_LABOUR_CLEARING', 'Maintenance labour clearing', 'LIABILITY', false, true, true, 'Maintenance labour charged at the labour rate'),
    ('OPENING_EQUITY', 'Opening balance equity', 'EQUITY', false, true, true, 'Opening stock and other opening balances'),
    ('SALES_REVENUE', 'Sales revenue', 'INCOME', false, true, true, 'Recognized on dispatch, reversed by customer returns'),
    ('COST_OF_GOODS_SOLD', 'Cost of goods sold', 'EXPENSE', false, true, true, ''),
    ('SCRAP_EXPENSE', 'Production scrap', 'EXPENSE', false, true, true, 'Scrap reported with manufacturing output'),
    ('QUALITY_LOSS', 'Quality losses', 'EXPENSE', false, true, true, 'Scrap dispositions of nonconforming stock'),
    ('INVENTORY_ADJUSTMENT', 'Inventory adjustments', 'EXPENSE', false, true, true, 'Stock adjustment and count losses (debit) and gains (credit)'),
    ('PURCHASE_PRICE_VARIANCE', 'Purchase price variance', 'EXPENSE', false, true, true, 'Supplier invoice price above (debit) or below (credit) the order price'),
    ('MAINTENANCE_EXPENSE', 'Maintenance expense', 'EXPENSE', false, true, true, 'Spare parts and labour on maintenance work orders'),
    -- reserved for later Phase 4 features
    ('BANK', 'Bank', 'ASSET', false, true, true, ''),
    ('CASH', 'Cash on hand', 'ASSET', false, true, true, ''),
    ('INPUT_TAX', 'Input tax recoverable', 'ASSET', false, true, true, ''),
    ('SUPPLIER_ADVANCES', 'Advances to suppliers', 'ASSET', false, true, true, ''),
    ('OUTPUT_TAX', 'Output tax payable', 'LIABILITY', false, true, true, ''),
    ('CUSTOMER_ADVANCES', 'Advances from customers', 'LIABILITY', false, true, true, ''),
    ('RETAINED_EARNINGS', 'Retained earnings', 'EQUITY', false, true, true, ''),
    ('SALES_RETURNS', 'Sales returns', 'INCOME', true, true, true, 'Contra income: reduces sales revenue'),
    ('ROUNDING', 'Rounding differences', 'EXPENSE', false, true, true, '')
  ) AS c(code, name, type, contra, postable, system, description)
  ON CONFLICT (tenant_id, code) DO NOTHING;

  UPDATE gl_accounts g SET parent_id = p.id
  FROM (VALUES
    ('INVENTORIES', 'ASSETS'), ('BANK', 'ASSETS'), ('CASH', 'ASSETS'), ('ACCOUNTS_RECEIVABLE', 'ASSETS'),
    ('SUPPLIER_ADVANCES', 'ASSETS'), ('INPUT_TAX', 'ASSETS'),
    ('RAW_INVENTORY', 'INVENTORIES'), ('WIP', 'INVENTORIES'), ('REWORK_WIP', 'INVENTORIES'), ('FG_INVENTORY', 'INVENTORIES'),
    ('ACCOUNTS_PAYABLE', 'LIABILITIES'), ('GRNI', 'LIABILITIES'), ('CUSTOMER_ADVANCES', 'LIABILITIES'),
    ('OUTPUT_TAX', 'LIABILITIES'), ('COST_CLEARING', 'LIABILITIES'),
    ('CONVERSION_CLEARING', 'COST_CLEARING'), ('MAINTENANCE_LABOUR_CLEARING', 'COST_CLEARING'),
    ('OPENING_EQUITY', 'EQUITY'), ('RETAINED_EARNINGS', 'EQUITY'),
    ('SALES_REVENUE', 'INCOME'), ('SALES_RETURNS', 'INCOME'),
    ('COST_OF_GOODS_SOLD', 'EXPENSES'), ('SCRAP_EXPENSE', 'EXPENSES'), ('QUALITY_LOSS', 'EXPENSES'),
    ('INVENTORY_ADJUSTMENT', 'EXPENSES'), ('PURCHASE_PRICE_VARIANCE', 'EXPENSES'), ('MAINTENANCE_EXPENSE', 'EXPENSES'),
    ('ROUNDING', 'EXPENSES')
  ) AS l(code, parent)
  JOIN gl_accounts p ON p.tenant_id = p_tenant AND p.code = l.parent AND NOT p.postable
  WHERE g.tenant_id = p_tenant AND g.code = l.code AND g.parent_id IS NULL AND g.type = p.type;
END $$;

-- Every tenant created from now on (bootstrap, demo, tests, future onboarding) starts with the standard chart.
CREATE FUNCTION seed_tenant_chart() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM seed_chart_of_accounts(NEW.id);
  RETURN NULL;
END $$;
CREATE TRIGGER tenant_chart_of_accounts AFTER INSERT ON tenants FOR EACH ROW EXECUTE FUNCTION seed_tenant_chart();

-- Existing tenants get the standard chart too.
SELECT seed_chart_of_accounts(id) FROM tenants;

-- Any other code found in existing journal lines becomes a postable account flagged for review. Its type is chosen
-- conservatively from its net balance: a debit balance becomes an EXPENSE (never an asset, which would overstate assets)
-- and a credit or zero balance a LIABILITY (never income, which would overstate profit). Review and retype them on the
-- Accounting page; nothing is reclassified automatically.
INSERT INTO gl_accounts(id, tenant_id, code, name, type, needs_review, description)
SELECT gen_random_uuid()::text, l.tenant_id, l.account,
       left(COALESCE(NULLIF(btrim(l.account), ''), '(blank code)'), 120),
       CASE WHEN SUM(l.debit - l.credit) > 0 THEN 'EXPENSE' ELSE 'LIABILITY' END,
       true,
       'Created from posted journal lines by the V24 migration; review its name and type'
FROM journal_lines l
WHERE NOT EXISTS (SELECT 1 FROM gl_accounts g WHERE g.tenant_id = l.tenant_id AND g.code = l.account)
GROUP BY l.tenant_id, l.account;

-- ------------------------------------------------------------------ journal entries: posting date, source, reversal
-- posting_date is the accounting date (Asia/Colombo). Existing entries are dated by their created_at in Asia/Colombo;
-- the immutability trigger is suspended for exactly this backfill and restored in the same transaction.
ALTER TABLE journal_entries ADD COLUMN posting_date date;
ALTER TABLE journal_entries ADD COLUMN source text NOT NULL DEFAULT 'SYSTEM';
ALTER TABLE journal_entries ADD COLUMN reverses_entry_id text;
ALTER TABLE journal_entries DISABLE TRIGGER journal_history_immutable;
UPDATE journal_entries SET posting_date = (created_at AT TIME ZONE 'Asia/Colombo')::date;
ALTER TABLE journal_entries ENABLE TRIGGER journal_history_immutable;
ALTER TABLE journal_entries ALTER COLUMN posting_date SET NOT NULL;
ALTER TABLE journal_entries ALTER COLUMN posting_date SET DEFAULT ((now() AT TIME ZONE 'Asia/Colombo')::date);
ALTER TABLE journal_entries ADD CONSTRAINT journal_entries_source_check CHECK (source IN ('SYSTEM','MANUAL','REVERSAL'));
ALTER TABLE journal_entries ADD CONSTRAINT journal_entries_reversal_link CHECK ((source = 'REVERSAL') = (reverses_entry_id IS NOT NULL));
ALTER TABLE journal_entries ADD CONSTRAINT journal_entries_reverses_fk
  FOREIGN KEY (tenant_id, reverses_entry_id) REFERENCES journal_entries(tenant_id, id);
-- An entry is reversed at most once.
CREATE UNIQUE INDEX journal_entries_reversed_once ON journal_entries(tenant_id, reverses_entry_id)
  WHERE reverses_entry_id IS NOT NULL;
CREATE INDEX journal_entries_tenant_posting ON journal_entries(tenant_id, posting_date, created_at, id);
CREATE INDEX journal_entries_tenant_source ON journal_entries(tenant_id, source, created_at DESC);

-- No entry is dated inside a closed period (periods close in date order, so the closed ones are a prefix).
CREATE FUNCTION journal_entry_open_period() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM financial_periods p
             WHERE p.tenant_id = NEW.tenant_id AND p.closed AND p.end_date >= NEW.posting_date) THEN
    RAISE EXCEPTION 'The financial period covering % is closed', NEW.posting_date;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER journal_entries_open_period BEFORE INSERT ON journal_entries
  FOR EACH ROW EXECUTE FUNCTION journal_entry_open_period();

-- ------------------------------------------------------------------ journal lines: chart accounts only
ALTER TABLE journal_lines ADD COLUMN memo text;

-- Deferred, like the balance check: an entry's lines are validated together at commit.
ALTER TABLE journal_lines ADD CONSTRAINT journal_lines_account_fk
  FOREIGN KEY (tenant_id, account) REFERENCES gl_accounts(tenant_id, code) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX journal_lines_tenant_account_entry ON journal_lines(tenant_id, account, entry_id);

-- Deferred triggers of one row fire in trigger-name order and the account foreign key's internal trigger is named
-- "RI_ConstraintTrigger_…": the V1 balance check is re-created under an upper-case name so that it still fires first
-- and an unbalanced entry is reported as unbalanced. Same function, same timing.
DROP TRIGGER journal_must_balance ON journal_lines;
CREATE CONSTRAINT TRIGGER "JOURNAL_MUST_BALANCE" AFTER INSERT ON journal_lines DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION validate_journal_balance();

-- Inactive and header accounts take no postings (an unknown code is refused by journal_lines_account_fk at commit).
CREATE FUNCTION journal_line_account_open() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_account gl_accounts%ROWTYPE;
BEGIN
  SELECT * INTO v_account FROM gl_accounts WHERE tenant_id = NEW.tenant_id AND code = NEW.account;
  IF FOUND THEN
    IF NOT v_account.postable THEN
      RAISE EXCEPTION 'Account % is a header account; post to one of its sub-accounts', NEW.account;
    END IF;
    IF NOT v_account.active THEN
      RAISE EXCEPTION 'Account % is inactive and takes no postings', NEW.account;
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER journal_lines_account_guard BEFORE INSERT ON journal_lines
  FOR EACH ROW EXECUTE FUNCTION journal_line_account_open();

-- ------------------------------------------------------------------ manual journals
-- DRAFT (editable) -> SUBMITTED (awaiting approval per the MANUAL_JOURNAL policy) -> POSTED (one journal entry, source
-- MANUAL) -> REVERSED (by a REVERSAL entry). SUBMITTED may return to DRAFT; DRAFT and SUBMITTED may be CANCELLED.
CREATE TABLE manual_journals (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  number text NOT NULL,
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SUBMITTED','POSTED','REVERSED','CANCELLED')),
  posting_date date NOT NULL,
  narration text NOT NULL CHECK (length(btrim(narration)) BETWEEN 1 AND 400),
  total_debit numeric(20,6) NOT NULL DEFAULT 0 CHECK (total_debit >= 0),
  total_credit numeric(20,6) NOT NULL DEFAULT 0 CHECK (total_credit >= 0),
  line_count integer NOT NULL DEFAULT 0 CHECK (line_count >= 0),
  created_by text NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_by text NOT NULL REFERENCES users(id),
  updated_at timestamptz NOT NULL DEFAULT now(),
  submitted_by text REFERENCES users(id),
  submitted_at timestamptz,
  approval_required boolean,
  approval_threshold numeric(20,6),
  approved_by text REFERENCES users(id),
  approved_at timestamptz,
  posted_at timestamptz,
  entry_id text,
  returned_by text REFERENCES users(id),
  returned_at timestamptz,
  return_reason text,
  cancelled_by text REFERENCES users(id),
  cancelled_at timestamptz,
  cancel_reason text,
  reversal_entry_id text,
  reversed_by text REFERENCES users(id),
  reversed_at timestamptz,
  reversal_reason text,
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, number),
  FOREIGN KEY (tenant_id, entry_id) REFERENCES journal_entries(tenant_id, id),
  FOREIGN KEY (tenant_id, reversal_entry_id) REFERENCES journal_entries(tenant_id, id),
  CHECK ((status IN ('POSTED','REVERSED')) = (entry_id IS NOT NULL)),
  CHECK ((status = 'REVERSED') = (reversal_entry_id IS NOT NULL)),
  CHECK (status NOT IN ('POSTED','REVERSED') OR total_debit = total_credit),
  -- Independent approval: never the creator, the submitter or the last editor (ADMIN included).
  CHECK (approved_by IS NULL OR (approved_by <> created_by AND approved_by <> updated_by
                                 AND approved_by IS DISTINCT FROM submitted_by))
);
CREATE INDEX manual_journals_tenant_created ON manual_journals(tenant_id, created_at DESC, id DESC);
CREATE INDEX manual_journals_tenant_status ON manual_journals(tenant_id, status, created_at DESC);

CREATE TABLE manual_journal_lines (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  journal_id text NOT NULL,
  line_no integer NOT NULL CHECK (line_no > 0),
  account text NOT NULL,
  debit numeric(20,6) NOT NULL DEFAULT 0 CHECK (debit >= 0),
  credit numeric(20,6) NOT NULL DEFAULT 0 CHECK (credit >= 0),
  memo text NOT NULL DEFAULT '' CHECK (length(memo) <= 200),
  CHECK ((debit = 0) <> (credit = 0)),
  UNIQUE (tenant_id, journal_id, line_no),
  FOREIGN KEY (tenant_id, journal_id) REFERENCES manual_journals(tenant_id, id),
  FOREIGN KEY (tenant_id, account) REFERENCES gl_accounts(tenant_id, code)
);
CREATE INDEX manual_journal_lines_tenant_account ON manual_journal_lines(tenant_id, account);

-- Manual journals are never deleted; a POSTED journal only becomes REVERSED (with its reversal), CANCELLED and
-- REVERSED journals are frozen. Lines change only while their journal is a DRAFT.
CREATE FUNCTION protect_manual_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'Manual journal % is a permanent record and cannot be deleted', OLD.number;
  END IF;
  IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.number <> OLD.number THEN
    RAISE EXCEPTION 'A manual journal keeps its identity';
  END IF;
  IF OLD.status IN ('CANCELLED', 'REVERSED') THEN
    RAISE EXCEPTION 'Manual journal % is %; its record is immutable', OLD.number, lower(OLD.status);
  END IF;
  IF OLD.status = 'POSTED' AND (
      NEW.status <> 'REVERSED'
      OR (NEW.posting_date, NEW.narration, NEW.total_debit, NEW.total_credit, NEW.line_count, NEW.created_by,
          NEW.created_at, NEW.updated_by, NEW.updated_at, NEW.submitted_by, NEW.submitted_at, NEW.approval_required,
          NEW.approval_threshold, NEW.approved_by, NEW.approved_at, NEW.posted_at, NEW.entry_id)
         IS DISTINCT FROM
         (OLD.posting_date, OLD.narration, OLD.total_debit, OLD.total_credit, OLD.line_count, OLD.created_by,
          OLD.created_at, OLD.updated_by, OLD.updated_at, OLD.submitted_by, OLD.submitted_at, OLD.approval_required,
          OLD.approval_threshold, OLD.approved_by, OLD.approved_at, OLD.posted_at, OLD.entry_id)) THEN
    RAISE EXCEPTION 'Manual journal % is posted; reverse it instead of changing it', OLD.number;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER manual_journal_guard BEFORE UPDATE OR DELETE ON manual_journals
  FOR EACH ROW EXECUTE FUNCTION protect_manual_journal();

CREATE FUNCTION protect_manual_journal_line() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_journal text := CASE WHEN TG_OP = 'DELETE' THEN OLD.journal_id ELSE NEW.journal_id END;
  v_tenant text := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
  v_status text;
  v_number text;
BEGIN
  IF TG_OP = 'UPDATE' AND (NEW.journal_id <> OLD.journal_id OR NEW.tenant_id <> OLD.tenant_id) THEN
    RAISE EXCEPTION 'A manual journal line cannot move to another journal';
  END IF;
  SELECT status, number INTO v_status, v_number FROM manual_journals WHERE tenant_id = v_tenant AND id = v_journal;
  IF v_status <> 'DRAFT' THEN
    RAISE EXCEPTION 'Manual journal % is %; only drafts can be edited', v_number, lower(v_status);
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER manual_journal_lines_guard BEFORE INSERT OR UPDATE OR DELETE ON manual_journal_lines
  FOR EACH ROW EXECUTE FUNCTION protect_manual_journal_line();
