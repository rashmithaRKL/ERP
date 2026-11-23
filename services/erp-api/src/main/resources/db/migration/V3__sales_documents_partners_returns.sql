-- V3: multi-line sales orders, partner (customer/supplier) management and customer returns.
-- Existing single-item sales orders become a header plus one line; deliveries and manufacturing orders keep their
-- sales_order_id and gain a backfilled sales_order_line_id. No posted stock/journal/audit row is touched.

-- ------------------------------------------------------------------ partners
ALTER TABLE partners
    ADD COLUMN phone text,
    ADD COLUMN billing_address text,
    ADD COLUMN shipping_address text,
    ADD COLUMN tax_id text,
    ADD COLUMN credit_limit numeric(20,6),
    ADD COLUMN active boolean NOT NULL DEFAULT true,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE partners
    ADD CONSTRAINT partners_credit_limit_check CHECK (credit_limit IS NULL OR (credit_limit >= 0 AND type = 'CUSTOMER'));
CREATE INDEX partners_tenant_type_name ON partners(tenant_id, type, active, name);

-- Inactive partners cannot be used on new documents (sales orders here, purchase orders for procurement).
-- SQLSTATE 23514 (check_violation) maps to HTTP 409 like every other constraint violation.
CREATE FUNCTION partner_must_be_active() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE partner text; is_active boolean;
BEGIN
  IF TG_TABLE_NAME = 'sales_orders' THEN partner := NEW.customer_id; ELSE partner := NEW.supplier_id; END IF;
  SELECT active INTO is_active FROM partners WHERE tenant_id = NEW.tenant_id AND id = partner;
  IF is_active IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Inactive partners cannot be used on new documents' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER purchase_partner_active BEFORE INSERT ON purchase_orders
    FOR EACH ROW EXECUTE FUNCTION partner_must_be_active();

-- ------------------------------------------------------------------ sales order header
ALTER TABLE sales_orders
    ADD COLUMN customer_reference text,
    ADD COLUMN notes text NOT NULL DEFAULT '',
    ADD COLUMN subtotal numeric(20,6) NOT NULL DEFAULT 0 CHECK (subtotal >= 0),
    ADD COLUMN discount_total numeric(20,6) NOT NULL DEFAULT 0 CHECK (discount_total >= 0),
    ADD COLUMN total numeric(20,6) NOT NULL DEFAULT 0 CHECK (total >= 0),
    ADD COLUMN status_reason text,
    ADD COLUMN created_by text REFERENCES users(id),
    ADD COLUMN confirmed_by text REFERENCES users(id),
    ADD COLUMN confirmed_at timestamptz,
    ADD COLUMN closed_by text REFERENCES users(id),
    ADD COLUMN closed_at timestamptz,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- ------------------------------------------------------------------ sales order lines
CREATE TABLE sales_order_lines (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    sales_order_id text NOT NULL,
    line_no integer NOT NULL CHECK (line_no > 0),
    product_id text NOT NULL,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    unit_price numeric(20,6) NOT NULL CHECK (unit_price >= 0),
    discount_pct numeric(9,6) NOT NULL DEFAULT 0 CHECK (discount_pct >= 0 AND discount_pct <= 100),
    line_total numeric(20,6) GENERATED ALWAYS AS (round(quantity * unit_price * (100 - discount_pct) / 100, 6)) STORED,
    delivered numeric(20,6) NOT NULL DEFAULT 0 CHECK (delivered >= 0 AND delivered <= quantity),
    returned numeric(20,6) NOT NULL DEFAULT 0 CHECK (returned >= 0 AND returned <= delivered),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, sales_order_id, id),
    UNIQUE (tenant_id, sales_order_id, line_no),
    FOREIGN KEY (tenant_id, sales_order_id) REFERENCES sales_orders(tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES items(tenant_id, id));
CREATE INDEX sales_order_lines_tenant_product ON sales_order_lines(tenant_id, product_id);

-- Every existing single-item order becomes line 1 with the same product, quantity, price and delivered quantity.
INSERT INTO sales_order_lines(id, tenant_id, sales_order_id, line_no, product_id, quantity, unit_price, discount_pct,
                              delivered, returned, created_at)
SELECT gen_random_uuid()::text, tenant_id, id, 1, product_id, quantity, unit_price, 0, delivered, 0, created_at
FROM sales_orders;
UPDATE sales_orders
SET subtotal = round(quantity * unit_price, 6),
    total = round(quantity * unit_price, 6),
    confirmed_at = created_at,
    updated_at = created_at;

-- ------------------------------------------------------------------ deliveries
ALTER TABLE deliveries
    ADD COLUMN sales_order_line_id text,
    ADD COLUMN revenue_amount numeric(20,6),
    ADD COLUMN cost_amount numeric(20,6),
    ADD COLUMN returned numeric(20,6) NOT NULL DEFAULT 0,
    ADD COLUMN created_by text REFERENCES users(id);
ALTER TABLE deliveries ADD CONSTRAINT deliveries_tenant_id_id_key UNIQUE (tenant_id, id);
UPDATE deliveries d
SET sales_order_line_id = l.id,
    revenue_amount = round(d.quantity * l.unit_price, 6)
FROM sales_order_lines l
WHERE l.tenant_id = d.tenant_id AND l.sales_order_id = d.sales_order_id;
-- The posted COGS of each existing delivery is the value of its CUSTOMER_DISPATCH ledger row (same transaction, so
-- the same timestamp); the lot unit cost is only a fallback.
UPDATE deliveries d
SET cost_amount = COALESCE(
        (SELECT -e.value_delta
         FROM stock_ledger e
         WHERE e.tenant_id = d.tenant_id AND e.lot_id = d.lot_id AND e.reference = d.reference
           AND e.reason = 'CUSTOMER_DISPATCH' AND e.delta = -d.quantity
         ORDER BY abs(extract(epoch FROM e.created_at - d.created_at)), e.id
         LIMIT 1),
        (SELECT round(d.quantity * l.unit_cost, 6) FROM lots l WHERE l.tenant_id = d.tenant_id AND l.id = d.lot_id));
ALTER TABLE deliveries
    ALTER COLUMN revenue_amount SET NOT NULL,
    ALTER COLUMN cost_amount SET NOT NULL,
    ADD CONSTRAINT deliveries_amounts_check CHECK (revenue_amount >= 0 AND cost_amount >= 0),
    ADD CONSTRAINT deliveries_returned_check CHECK (returned >= 0 AND returned <= quantity),
    ADD CONSTRAINT deliveries_sales_order_line_fk
        FOREIGN KEY (tenant_id, sales_order_id, sales_order_line_id)
        REFERENCES sales_order_lines(tenant_id, sales_order_id, id);
CREATE INDEX deliveries_tenant_order ON deliveries(tenant_id, sales_order_id, created_at);

-- ------------------------------------------------------------------ manufacturing orders
ALTER TABLE manufacturing_orders ADD COLUMN sales_order_line_id text;
UPDATE manufacturing_orders o
SET sales_order_line_id = l.id
FROM sales_order_lines l
WHERE l.tenant_id = o.tenant_id AND l.sales_order_id = o.sales_order_id;
ALTER TABLE manufacturing_orders
    ADD CONSTRAINT manufacturing_orders_sales_order_line_fk
        FOREIGN KEY (tenant_id, sales_order_id, sales_order_line_id)
        REFERENCES sales_order_lines(tenant_id, sales_order_id, id);
CREATE INDEX mo_tenant_sales_line ON manufacturing_orders(tenant_id, sales_order_line_id);

-- ------------------------------------------------------------------ header: drop the single-item columns
-- Their values now live on line 1 (product, quantity, unit price, delivered).
ALTER TABLE sales_orders
    DROP COLUMN product_id,
    DROP COLUMN quantity,
    DROP COLUMN unit_price,
    DROP COLUMN delivered;
ALTER TABLE sales_orders DROP CONSTRAINT sales_orders_status_check;
ALTER TABLE sales_orders
    ADD CONSTRAINT sales_orders_status_check
        CHECK (status IN ('DRAFT', 'CONFIRMED', 'PARTIAL', 'DELIVERED', 'CLOSED', 'CANCELLED')),
    ALTER COLUMN status SET DEFAULT 'DRAFT';
CREATE INDEX sales_orders_tenant_created ON sales_orders(tenant_id, created_at DESC, id DESC);
CREATE INDEX sales_orders_tenant_status ON sales_orders(tenant_id, status, created_at DESC);
CREATE INDEX sales_orders_tenant_customer ON sales_orders(tenant_id, customer_id);
CREATE TRIGGER sales_partner_active BEFORE INSERT OR UPDATE OF customer_id ON sales_orders
    FOR EACH ROW EXECUTE FUNCTION partner_must_be_active();

-- ------------------------------------------------------------------ customer returns (posted history)
CREATE TABLE customer_returns (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    number text NOT NULL,
    delivery_id text NOT NULL,
    sales_order_id text NOT NULL,
    sales_order_line_id text NOT NULL,
    lot_id text NOT NULL,
    inspection_id text NOT NULL,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    unit_cost numeric(20,6) NOT NULL CHECK (unit_cost >= 0),
    cost_amount numeric(20,6) NOT NULL CHECK (cost_amount >= 0),
    credit_amount numeric(20,6) NOT NULL CHECK (credit_amount >= 0),
    reason text NOT NULL CHECK (length(btrim(reason)) > 0),
    created_by text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    FOREIGN KEY (tenant_id, delivery_id) REFERENCES deliveries(tenant_id, id),
    FOREIGN KEY (tenant_id, sales_order_id, sales_order_line_id) REFERENCES sales_order_lines(tenant_id, sales_order_id, id),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lots(tenant_id, id),
    FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections(tenant_id, id));
CREATE INDEX customer_returns_tenant_order ON customer_returns(tenant_id, sales_order_id, created_at);
CREATE INDEX customer_returns_tenant_delivery ON customer_returns(tenant_id, delivery_id);
CREATE TRIGGER customer_returns_immutable BEFORE UPDATE OR DELETE ON customer_returns
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- Document activity feeds look audit events up by reference.
CREATE INDEX audit_tenant_reference ON audit_events(tenant_id, reference, created_at);
