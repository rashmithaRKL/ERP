-- V4 item master: tenant units of measure, item unit conversions, categories, barcodes, variant families,
-- SEMI_FINISHED items, editable item attributes and an item identity guard.
-- Units are keyed by (tenant_id, code) so items.uom and stock_ledger.uom keep storing the plain unit code; the V1
-- ledger trigger (ledger uom = item base unit) is unchanged.

-- ------------------------------------------------------------------ units of measure
CREATE TABLE units (
  tenant_id text NOT NULL REFERENCES tenants(id),
  code text NOT NULL CHECK (code ~ '^[A-Za-z0-9]{1,12}$'),
  name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 60),
  kind text NOT NULL CHECK (kind IN ('MASS','VOLUME','LENGTH','AREA','COUNT','PACKAGE')),
  decimals integer NOT NULL CHECK (decimals BETWEEN 0 AND 6),
  -- Size relative to the kind's reference unit (kg, l, m, m2, pcs). NULL: the size depends on the item (box, bag...).
  ratio numeric(24,12) CHECK (ratio IS NULL OR ratio > 0),
  system boolean NOT NULL DEFAULT false,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, code),
  CHECK (kind <> 'PACKAGE' OR ratio IS NULL)
);
CREATE UNIQUE INDEX im_units_tenant_code_ci ON units(tenant_id, lower(code));

CREATE FUNCTION seed_default_units(p_tenant text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO units(tenant_id,code,name,kind,decimals,ratio,system) VALUES
    (p_tenant,'kg','Kilogram','MASS',6,1,true),
    (p_tenant,'g','Gram','MASS',3,0.001,true),
    (p_tenant,'t','Tonne','MASS',6,1000,true),
    (p_tenant,'pcs','Pieces','COUNT',0,1,true),
    (p_tenant,'box','Box','PACKAGE',0,NULL,true),
    (p_tenant,'bag','Bag','PACKAGE',0,NULL,true),
    (p_tenant,'roll','Roll','PACKAGE',0,NULL,true),
    (p_tenant,'pallet','Pallet','PACKAGE',0,NULL,true),
    (p_tenant,'drum','Drum','PACKAGE',0,NULL,true),
    (p_tenant,'l','Litre','VOLUME',6,1,true),
    (p_tenant,'ml','Millilitre','VOLUME',3,0.001,true),
    (p_tenant,'m','Metre','LENGTH',6,1,true),
    (p_tenant,'cm','Centimetre','LENGTH',3,0.01,true),
    (p_tenant,'mm','Millimetre','LENGTH',3,0.001,true),
    (p_tenant,'m2','Square metre','AREA',6,1,true)
  ON CONFLICT DO NOTHING;
END; $$;

-- Every tenant (bootstrap, demo, tests, future onboarding) starts with the standard units.
CREATE FUNCTION seed_tenant_units() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM seed_default_units(NEW.id);
  RETURN NULL;
END; $$;
CREATE TRIGGER tenant_default_units AFTER INSERT ON tenants FOR EACH ROW EXECUTE FUNCTION seed_tenant_units();

-- Existing tenants (V1/V2 data): their items only use kg and pcs, which are part of the standard set.
SELECT seed_default_units(id) FROM tenants;

-- ------------------------------------------------------------------ categories
CREATE TABLE item_categories (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 80),
  description text NOT NULL DEFAULT '',
  parent_id text,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, parent_id) REFERENCES item_categories(tenant_id, id),
  CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE UNIQUE INDEX im_item_categories_name ON item_categories(tenant_id, lower(name));

-- ------------------------------------------------------------------ variant families (templates are not items)
CREATE TABLE item_templates (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  code text NOT NULL CHECK (length(btrim(code)) BETWEEN 1 AND 40),
  name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 160),
  description text NOT NULL DEFAULT '',
  type text NOT NULL CHECK (type IN ('RAW_MATERIAL','SEMI_FINISHED','FINISHED_GOOD','PACKAGING')),
  uom text NOT NULL,
  category_id text,
  -- [{"name":"Size","values":["500ml","1L"]}, ...]
  attributes jsonb NOT NULL CHECK (jsonb_typeof(attributes) = 'array'),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, uom) REFERENCES units(tenant_id, code),
  FOREIGN KEY (tenant_id, category_id) REFERENCES item_categories(tenant_id, id)
);
CREATE UNIQUE INDEX im_item_templates_code ON item_templates(tenant_id, lower(code));

-- ------------------------------------------------------------------ items
ALTER TABLE items DROP CONSTRAINT items_type_check;
ALTER TABLE items ADD CONSTRAINT items_type_check
  CHECK (type IN ('RAW_MATERIAL','SEMI_FINISHED','FINISHED_GOOD','PACKAGING'));
ALTER TABLE items DROP CONSTRAINT items_uom_check;
ALTER TABLE items
  ADD COLUMN description text NOT NULL DEFAULT '',
  ADD COLUMN category_id text,
  ADD COLUMN reorder_qty numeric(20,6) NOT NULL DEFAULT 0 CHECK (reorder_qty >= 0),
  ADD COLUMN lead_time_days integer NOT NULL DEFAULT 0 CHECK (lead_time_days BETWEEN 0 AND 365),
  ADD COLUMN template_id text,
  -- Canonical JSON of the variant's attribute values ({"Colour":"Blue","Size":"500ml"}); unique per family.
  ADD COLUMN variant_key text,
  ADD COLUMN variant_label text,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT items_unit_fk FOREIGN KEY (tenant_id, uom) REFERENCES units(tenant_id, code),
  ADD CONSTRAINT items_category_fk FOREIGN KEY (tenant_id, category_id) REFERENCES item_categories(tenant_id, id),
  ADD CONSTRAINT items_template_fk FOREIGN KEY (tenant_id, template_id) REFERENCES item_templates(tenant_id, id),
  ADD CONSTRAINT items_variant_shape CHECK ((template_id IS NULL) = (variant_key IS NULL)),
  ADD CONSTRAINT items_variant_unique UNIQUE (tenant_id, template_id, variant_key);

CREATE INDEX im_items_type ON items(tenant_id, type, active);
CREATE INDEX im_items_category ON items(tenant_id, category_id);
CREATE INDEX im_items_template ON items(tenant_id, template_id);
CREATE INDEX im_items_name ON items(tenant_id, lower(name));
CREATE INDEX im_bom_lines_item ON bom_lines(tenant_id, item_id);
CREATE INDEX im_stock_ledger_item ON stock_ledger(tenant_id, item_id, created_at DESC);

-- ------------------------------------------------------------------ item-specific unit conversions
-- 1 unit_code = factor x the item's base unit (1 bag = 25 kg). The ledger keeps storing base units.
CREATE TABLE item_unit_conversions (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  item_id text NOT NULL,
  unit_code text NOT NULL,
  factor numeric(24,12) NOT NULL CHECK (factor > 0),
  purchase boolean NOT NULL DEFAULT true,
  sales boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, item_id, unit_code),
  FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id),
  FOREIGN KEY (tenant_id, unit_code) REFERENCES units(tenant_id, code)
);

-- ------------------------------------------------------------------ barcodes
CREATE TABLE item_barcodes (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  item_id text NOT NULL,
  barcode text NOT NULL CHECK (barcode ~ '^[!-~]{3,64}$'),
  -- NULL: one scan is one base unit. Otherwise one scan is one of this unit (a carton barcode = 1 box = 50 pcs).
  unit_code text,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, barcode),
  FOREIGN KEY (tenant_id, item_id) REFERENCES items(tenant_id, id),
  FOREIGN KEY (tenant_id, unit_code) REFERENCES units(tenant_id, code)
);
CREATE INDEX im_item_barcodes_item ON item_barcodes(tenant_id, item_id);

-- ------------------------------------------------------------------ item identity guard
-- True when any table with a foreign key to items (stock ledger, lots, BOMs, orders, documents - including tables
-- added by later migrations) references the item. Item master tables (conversions, barcodes) do not count.
CREATE FUNCTION item_is_referenced(p_tenant text, p_item text) RETURNS boolean LANGUAGE plpgsql STABLE AS $$
DECLARE r record; hit boolean;
BEGIN
  FOR r IN
    SELECT c.conrelid::regclass::text AS rel, att.attname AS col,
           EXISTS (SELECT 1 FROM pg_attribute t
                   WHERE t.attrelid = c.conrelid AND t.attname = 'tenant_id' AND NOT t.attisdropped) AS scoped
    FROM pg_constraint c
    CROSS JOIN LATERAL unnest(c.conkey, c.confkey) AS k(src, dst)
    JOIN pg_attribute att ON att.attrelid = c.conrelid AND att.attnum = k.src
    JOIN pg_attribute ref ON ref.attrelid = c.confrelid AND ref.attnum = k.dst
    WHERE c.contype = 'f' AND c.confrelid = 'items'::regclass AND ref.attname = 'id'
      AND c.conrelid NOT IN ('items'::regclass, 'item_unit_conversions'::regclass, 'item_barcodes'::regclass)
  LOOP
    IF r.scoped THEN
      EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s WHERE tenant_id = $1 AND %I = $2)', r.rel, r.col)
        INTO hit USING p_tenant, p_item;
    ELSE
      EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s WHERE %I = $1)', r.rel, r.col) INTO hit USING p_item;
    END IF;
    IF hit THEN RETURN true; END IF;
  END LOOP;
  RETURN false;
END; $$;

-- Last line of defence behind the item commands: a used item keeps its SKU, base unit and type, and the base unit
-- never changes once stock has moved (the ledger stores base units and is immutable).
CREATE FUNCTION protect_item_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id <> OLD.tenant_id OR NEW.id <> OLD.id THEN
    RAISE EXCEPTION 'Item identity is immutable';
  END IF;
  IF NEW.uom <> OLD.uom
     AND EXISTS (SELECT 1 FROM stock_ledger WHERE tenant_id = OLD.tenant_id AND item_id = OLD.id) THEN
    RAISE EXCEPTION 'An item''s base unit cannot change after stock movements';
  END IF;
  IF (NEW.sku <> OLD.sku OR NEW.uom <> OLD.uom OR NEW.type <> OLD.type)
     AND item_is_referenced(OLD.tenant_id, OLD.id) THEN
    RAISE EXCEPTION 'SKU, base unit and type are fixed once an item has stock or document references';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER item_identity_guard BEFORE UPDATE ON items FOR EACH ROW EXECUTE FUNCTION protect_item_identity();
