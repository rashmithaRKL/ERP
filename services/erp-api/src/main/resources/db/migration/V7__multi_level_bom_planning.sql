-- V7 multi-level BOM and planning: parent/child manufacturing orders, BOM revision lineage (copy-to-draft) and the
-- indexes used by the explosion, where-used, child-order and MRP read models.
-- Multi-level structure itself needs no new table: a SEMI_FINISHED component with its own approved BOM is the next
-- level. Cycle and missing-sub-BOM checks run when a revision is approved (catalog.BomService).

-- ------------------------------------------------------------------ parent/child manufacturing orders
-- A child order produces a semi-finished component that its parent order consumes. The link is set when the child is
-- created and never changes, so a child always references an existing (older) order and links cannot form a cycle.
ALTER TABLE manufacturing_orders
  ADD COLUMN parent_order_id text,
  ADD CONSTRAINT mo_parent_fk FOREIGN KEY (tenant_id, parent_order_id) REFERENCES manufacturing_orders(tenant_id, id),
  ADD CONSTRAINT mo_parent_not_self CHECK (parent_order_id IS NULL OR parent_order_id <> id);
CREATE INDEX mlb_mo_parent ON manufacturing_orders(tenant_id, parent_order_id) WHERE parent_order_id IS NOT NULL;

CREATE FUNCTION protect_mo_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.parent_order_id IS DISTINCT FROM OLD.parent_order_id THEN
    RAISE EXCEPTION 'A manufacturing order''s parent order cannot change' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER mo_parent_immutable BEFORE UPDATE OF parent_order_id ON manufacturing_orders
  FOR EACH ROW EXECUTE FUNCTION protect_mo_parent();

-- ------------------------------------------------------------------ BOM revision lineage
-- The approved revision a draft was copied from (POST /api/boms/{id}/copy). Adding a column fires no row trigger, so
-- approved revisions stay untouched (approved_bom_immutable, V2).
ALTER TABLE boms
  ADD COLUMN source_bom_id text,
  ADD CONSTRAINT boms_source_fk FOREIGN KEY (tenant_id, source_bom_id) REFERENCES boms(tenant_id, id);

-- ------------------------------------------------------------------ read-model indexes
-- Latest approved revision per product (explosion, MRP, child orders).
CREATE INDEX mlb_boms_product_status ON boms(tenant_id, product_id, status, created_at DESC);
-- (Output lots of an order are found through V5's lots_tenant_source index.)
-- Open demand per material (MRP).
CREATE INDEX mlb_order_materials_item ON order_materials(tenant_id, item_id);
