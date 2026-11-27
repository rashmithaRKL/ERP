-- V25: taxes. Tax codes per tenant with stacked components and effective-dated rate versions, country templates
-- (applied through the API, never seeded here), tenant tax settings, partner tax registration/exemption and
-- partner/item default codes, and the tax register (tax_lines) written by later receivables/payables features.
-- Account codes (OUTPUT_TAX / INPUT_TAX) are plain text: the general ledger's chart is linked by a later migration.
-- Nothing posts tax yet and no existing row changes meaning: every new partner/item column is nullable or defaulted.

-- ------------------------------------------------------------------ tax codes
CREATE TABLE tax_codes (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    code text NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    applies_to text NOT NULL CHECK (applies_to IN ('SALES', 'PURCHASES', 'BOTH')),
    -- STANDARD: taxed by its components; ZERO_RATED: taxable at 0 % (the base is reported as zero-rated);
    -- EXEMPT: outside the tax (the base is reported separately as exempt). Only STANDARD codes have components.
    kind text NOT NULL CHECK (kind IN ('STANDARD', 'ZERO_RATED', 'EXEMPT')),
    output_account_code text NOT NULL DEFAULT 'OUTPUT_TAX' CHECK (output_account_code ~ '^[A-Z0-9_]{1,40}$'),
    input_account_code text NOT NULL DEFAULT 'INPUT_TAX' CHECK (input_account_code ~ '^[A-Z0-9_]{1,40}$'),
    active boolean NOT NULL DEFAULT true,
    -- Set when the code came from a country template (regime + template code); edited_at once an administrator
    -- changed it, so re-applying the template never overwrites it.
    template_regime text,
    template_code text,
    edited_at timestamptz,
    created_by text REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code));
CREATE INDEX tax_codes_tenant_active ON tax_codes(tenant_id, active, code);

CREATE TABLE tax_components (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    tax_code_id text NOT NULL,
    seq integer NOT NULL CHECK (seq BETWEEN 1 AND 9),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 80),
    -- A compound component is calculated on the base plus the tax of every earlier component of the code.
    compound boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, tax_code_id, id),
    UNIQUE (tenant_id, tax_code_id, seq),
    FOREIGN KEY (tenant_id, tax_code_id) REFERENCES tax_codes(tenant_id, id),
    CHECK (seq > 1 OR NOT compound));

-- A version applies from effective_from until the day before the next version of the same component (effective_to
-- is derived, see tax_rate_periods). Rates are percentages with up to four decimals, e.g. 18.0000.
CREATE TABLE tax_rate_versions (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    component_id text NOT NULL,
    effective_from date NOT NULL,
    rate numeric(9,4) NOT NULL CHECK (rate >= 0 AND rate <= 100),
    note text,
    created_by text REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, component_id, effective_from),
    FOREIGN KEY (tenant_id, component_id) REFERENCES tax_components(tenant_id, id));

CREATE VIEW tax_rate_periods AS
SELECT v.*,
       (lead(v.effective_from) OVER (PARTITION BY v.tenant_id, v.component_id ORDER BY v.effective_from)
           - 1) AS effective_to
FROM tax_rate_versions v;

-- ------------------------------------------------------------------ tenant settings
CREATE TABLE tax_settings (
    tenant_id text PRIMARY KEY REFERENCES tenants(id),
    prices_include_tax boolean NOT NULL DEFAULT false,
    rounding text NOT NULL DEFAULT 'PER_LINE' CHECK (rounding IN ('PER_LINE', 'PER_DOCUMENT')),
    regime text,
    default_sales_code_id text,
    default_purchase_code_id text,
    exempt_code_id text,
    updated_by text REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, default_sales_code_id) REFERENCES tax_codes(tenant_id, id),
    FOREIGN KEY (tenant_id, default_purchase_code_id) REFERENCES tax_codes(tenant_id, id),
    FOREIGN KEY (tenant_id, exempt_code_id) REFERENCES tax_codes(tenant_id, id));

-- ------------------------------------------------------------------ partner and item defaults (additive)
ALTER TABLE partners
    ADD COLUMN tax_registered boolean NOT NULL DEFAULT false,
    ADD COLUMN tax_exemption_reference text,
    ADD COLUMN tax_exemption_valid_until date,
    ADD COLUMN sales_tax_code_id text,
    ADD COLUMN purchase_tax_code_id text,
    ADD CONSTRAINT partners_tax_exemption_check
        CHECK ((tax_exemption_reference IS NULL) = (tax_exemption_valid_until IS NULL)),
    ADD CONSTRAINT partners_sales_tax_code_fk
        FOREIGN KEY (tenant_id, sales_tax_code_id) REFERENCES tax_codes(tenant_id, id),
    ADD CONSTRAINT partners_purchase_tax_code_fk
        FOREIGN KEY (tenant_id, purchase_tax_code_id) REFERENCES tax_codes(tenant_id, id);

ALTER TABLE items
    ADD COLUMN sales_tax_code_id text,
    ADD COLUMN purchase_tax_code_id text,
    ADD CONSTRAINT items_sales_tax_code_fk
        FOREIGN KEY (tenant_id, sales_tax_code_id) REFERENCES tax_codes(tenant_id, id),
    ADD CONSTRAINT items_purchase_tax_code_fk
        FOREIGN KEY (tenant_id, purchase_tax_code_id) REFERENCES tax_codes(tenant_id, id);

-- ------------------------------------------------------------------ tax register
-- One row per document line and tax component (one row without a component for zero-rated and exempt lines).
-- Written only through tax.TaxService#record inside the posting document's transaction (V26/V27); posted history.
-- base/tax are signed: a credit note records negative amounts.
CREATE TABLE tax_lines (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    source_type text NOT NULL CHECK (source_type ~ '^[A-Z][A-Z_]{1,39}$'),
    source_id text NOT NULL,
    source_number text,
    line_no integer NOT NULL CHECK (line_no > 0),
    tax_code_id text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('STANDARD', 'ZERO_RATED', 'EXEMPT')),
    component_id text,
    component_seq integer NOT NULL DEFAULT 0,
    rate_version_id text,
    rate numeric(9,4) NOT NULL DEFAULT 0 CHECK (rate >= 0 AND rate <= 100),
    account_code text NOT NULL,
    base numeric(20,6) NOT NULL,
    tax numeric(20,6) NOT NULL,
    direction text NOT NULL CHECK (direction IN ('OUTPUT', 'INPUT')),
    posting_date date NOT NULL,
    document_date date NOT NULL,
    partner_id text,
    created_by text REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, source_type, source_id, line_no, component_seq),
    FOREIGN KEY (tenant_id, tax_code_id) REFERENCES tax_codes(tenant_id, id),
    FOREIGN KEY (tenant_id, component_id) REFERENCES tax_components(tenant_id, id),
    FOREIGN KEY (tenant_id, rate_version_id) REFERENCES tax_rate_versions(tenant_id, id),
    FOREIGN KEY (tenant_id, partner_id) REFERENCES partners(tenant_id, id),
    CHECK ((kind = 'STANDARD') = (component_id IS NOT NULL)),
    CHECK ((component_id IS NULL) = (rate_version_id IS NULL)),
    CHECK (kind = 'STANDARD' OR tax = 0));
CREATE INDEX tax_lines_tenant_period ON tax_lines(tenant_id, posting_date, id);
CREATE INDEX tax_lines_tenant_code ON tax_lines(tenant_id, tax_code_id, posting_date);
CREATE INDEX tax_lines_tenant_version ON tax_lines(tenant_id, rate_version_id);
CREATE INDEX tax_lines_tenant_component ON tax_lines(tenant_id, component_id, posting_date);
CREATE TRIGGER tax_lines_immutable BEFORE UPDATE OR DELETE ON tax_lines
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- ------------------------------------------------------------------ guards for used rates and codes
-- A rate version used by a tax line is frozen, and no version may be added or moved on or before the latest posting
-- date already taxed on its component (that would change which rate applied to recorded tax).
CREATE FUNCTION protect_tax_rate_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE last_taxed date;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE')
     AND EXISTS (SELECT 1 FROM tax_lines WHERE tenant_id = OLD.tenant_id AND rate_version_id = OLD.id) THEN
    RAISE EXCEPTION 'This rate version is used by recorded tax lines and cannot be changed';
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    SELECT max(t.document_date) INTO last_taxed FROM tax_lines t
     WHERE t.tenant_id = NEW.tenant_id AND t.component_id = NEW.component_id;
    IF last_taxed IS NOT NULL AND NEW.effective_from <= last_taxed THEN
      RAISE EXCEPTION 'Tax is already recorded on this component up to %; a new rate must start after that date',
        to_char(last_taxed, 'YYYY-MM-DD');
    END IF;
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER tax_rate_version_guard BEFORE INSERT OR UPDATE OR DELETE ON tax_rate_versions
    FOR EACH ROW EXECUTE FUNCTION protect_tax_rate_version();

-- A component with recorded tax keeps its order and compound flag; components are never deleted once used.
CREATE FUNCTION protect_tax_component() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM tax_lines WHERE tenant_id = OLD.tenant_id AND component_id = OLD.id)
     AND (TG_OP = 'DELETE' OR NEW.seq <> OLD.seq OR NEW.compound <> OLD.compound
          OR NEW.tax_code_id <> OLD.tax_code_id) THEN
    RAISE EXCEPTION 'This tax component is used by recorded tax lines; its order and calculation cannot change';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER tax_component_guard BEFORE UPDATE OR DELETE ON tax_components
    FOR EACH ROW EXECUTE FUNCTION protect_tax_component();

-- A code with recorded tax keeps its code and kind (the register and returns group by them); codes are never deleted.
CREATE FUNCTION protect_tax_code() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'Tax codes are never deleted; deactivate the code instead';
  END IF;
  IF (NEW.kind <> OLD.kind OR NEW.code <> OLD.code)
     AND EXISTS (SELECT 1 FROM tax_lines WHERE tenant_id = OLD.tenant_id AND tax_code_id = OLD.id) THEN
    RAISE EXCEPTION 'This tax code is used by recorded tax lines; its code and kind cannot change';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER tax_code_guard BEFORE UPDATE OR DELETE ON tax_codes
    FOR EACH ROW EXECUTE FUNCTION protect_tax_code();
