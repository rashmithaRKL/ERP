CREATE FUNCTION protect_approved_bom() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status='APPROVED' THEN RAISE EXCEPTION 'Approved BOM revisions are immutable; create a new revision'; END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER approved_bom_immutable BEFORE UPDATE OR DELETE ON boms FOR EACH ROW EXECUTE FUNCTION protect_approved_bom();
CREATE FUNCTION protect_approved_bom_line() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_status text;
BEGIN
  IF TG_OP IN ('UPDATE','DELETE') THEN
    SELECT status INTO owner_status FROM boms WHERE tenant_id=OLD.tenant_id AND id=OLD.bom_id;
    IF owner_status='APPROVED' THEN RAISE EXCEPTION 'Approved BOM lines are immutable'; END IF;
  END IF;
  IF TG_OP IN ('INSERT','UPDATE') THEN
    SELECT status INTO owner_status FROM boms WHERE tenant_id=NEW.tenant_id AND id=NEW.bom_id;
    IF owner_status='APPROVED' THEN RAISE EXCEPTION 'Approved BOM lines are immutable'; END IF;
    RETURN NEW;
  END IF;
  RETURN OLD;
END; $$;
CREATE TRIGGER approved_bom_line_immutable BEFORE INSERT OR UPDATE OR DELETE ON bom_lines FOR EACH ROW EXECUTE FUNCTION protect_approved_bom_line();
