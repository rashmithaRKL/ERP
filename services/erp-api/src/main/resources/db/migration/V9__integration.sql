-- V9: integration. Sales-order lines in sales units (mirrors V6 purchase-order lines), cycle counts scoped to an item
-- category, and indexes for the keyset-paged manufacturing, quality, cost-register and journal lists. Approval
-- policies for manufacturing and purchase orders (V8 table) need no schema change. No posted stock/journal/audit row
-- is touched.

-- ------------------------------------------------------------------ sales units
-- quantity/unit_price are in the entered sales unit (the product's base unit or a conversion flagged for sales);
-- base_quantity = quantity x factor (exact at the base unit's precision) and base_unit_price = unit_price / factor are
-- what dispatch, delivered/returned quantities, manufacturing demand and planning use. line_total stays
-- quantity x unit_price less the discount. Existing lines were entered in the base unit: factor 1.
ALTER TABLE sales_order_lines
    ADD COLUMN unit text,
    ADD COLUMN factor numeric(24,12),
    ADD COLUMN base_quantity numeric(20,6),
    ADD COLUMN base_unit_price numeric(20,6);

UPDATE sales_order_lines l
SET unit = i.uom,
    factor = 1,
    base_quantity = l.quantity,
    base_unit_price = l.unit_price
FROM items i
WHERE i.tenant_id = l.tenant_id AND i.id = l.product_id;

ALTER TABLE sales_order_lines
    ALTER COLUMN unit SET NOT NULL,
    ALTER COLUMN factor SET NOT NULL,
    ALTER COLUMN base_quantity SET NOT NULL,
    ALTER COLUMN base_unit_price SET NOT NULL,
    ADD CONSTRAINT sales_order_lines_factor_check CHECK (factor > 0),
    ADD CONSTRAINT sales_order_lines_base_quantity_check CHECK (base_quantity > 0),
    ADD CONSTRAINT sales_order_lines_base_unit_price_check CHECK (base_unit_price >= 0),
    ADD CONSTRAINT sales_order_lines_unit_fkey FOREIGN KEY (tenant_id, unit) REFERENCES units(tenant_id, code);

-- Delivered quantities are base quantities: bound them by base_quantity instead of the entered quantity. V3 declared
-- the bound inline (generated constraint name), so it is found by its definition.
DO $$
DECLARE bound text;
BEGIN
  SELECT conname INTO STRICT bound FROM pg_constraint
  WHERE conrelid = 'sales_order_lines'::regclass AND contype = 'c'
    AND pg_get_constraintdef(oid) LIKE '%delivered <= quantity%';
  EXECUTE format('ALTER TABLE sales_order_lines DROP CONSTRAINT %I', bound);
END $$;
ALTER TABLE sales_order_lines
    ADD CONSTRAINT sales_order_lines_delivered_check CHECK (delivered >= 0 AND delivered <= base_quantity);

-- ------------------------------------------------------------------ keyset-paged lists (created_at DESC, id DESC)
-- GET /api/manufacturing/orders and /api/reports/costs, /api/quality/inspections, /api/reports/journal.
CREATE INDEX mo_tenant_created_id ON manufacturing_orders(tenant_id, created_at DESC, id DESC);
CREATE INDEX inspections_tenant_created_id ON inspections(tenant_id, created_at DESC, id DESC);
CREATE INDEX journal_entries_tenant_created_id ON journal_entries(tenant_id, created_at DESC, id DESC);
CREATE INDEX journal_lines_tenant_entry ON journal_lines(tenant_id, entry_id);
CREATE INDEX journal_lines_tenant_account ON journal_lines(tenant_id, account);
