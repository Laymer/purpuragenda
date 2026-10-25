CREATE TABLE booking_contact_change(id uuid PRIMARY KEY,booking_id uuid NOT NULL REFERENCES booking(id),old_email varchar(254) NOT NULL,new_email varchar(254) NOT NULL,changed_at timestamptz NOT NULL);
CREATE TABLE booking_override(id uuid PRIMARY KEY,booking_id uuid NOT NULL REFERENCES booking(id),booking_version bigint NOT NULL,policy_version bigint NOT NULL,rules jsonb NOT NULL,blackout_ids jsonb NOT NULL,reason varchar(500) NOT NULL,created_at timestamptz NOT NULL);
ALTER TABLE outbox_event ADD COLUMN payload jsonb NOT NULL DEFAULT '{}';
ALTER TABLE audit_event ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}';
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_event FOR EACH ROW EXECUTE FUNCTION immutable_fact();
CREATE TRIGGER immutable_contact_history BEFORE UPDATE OR DELETE ON booking_contact_change FOR EACH ROW EXECUTE FUNCTION immutable_fact();
CREATE TRIGGER immutable_override BEFORE UPDATE OR DELETE ON booking_override FOR EACH ROW EXECUTE FUNCTION immutable_fact();
CREATE FUNCTION protect_booking_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.customer_id<>OLD.customer_id OR NEW.service_id<>OLD.service_id OR NEW.session_minutes<>OLD.session_minutes OR NEW.created_at<>OLD.created_at OR NEW.reference<>OLD.reference OR NEW.origin<>OLD.origin OR NEW.resource_id<>OLD.resource_id THEN
   RAISE EXCEPTION 'immutable booking identity or snapshot' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER booking_identity_immutable BEFORE UPDATE ON booking FOR EACH ROW EXECUTE FUNCTION protect_booking_identity();
CREATE FUNCTION verify_refund_obligation() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE receipt payment_entry; BEGIN
 SELECT * INTO receipt FROM payment_entry WHERE id=NEW.receipt_id FOR UPDATE;
 IF receipt.id IS NULL OR receipt.kind<>'RECEIPT' OR NEW.due_minor>receipt.amount_minor THEN RAISE EXCEPTION 'invalid refund obligation' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER refund_obligation_shape BEFORE INSERT OR UPDATE ON refund_obligation FOR EACH ROW EXECUTE FUNCTION verify_refund_obligation();
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='purpuragenda_app') THEN
   REVOKE INSERT,UPDATE,DELETE ON databasechangelog,databasechangeloglock FROM purpuragenda_app;
 END IF;
END $$;
