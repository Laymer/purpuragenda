ALTER TABLE booking ADD COLUMN meeting_erased boolean NOT NULL DEFAULT false;
ALTER TABLE customer_identity ADD COLUMN privacy_erased boolean NOT NULL DEFAULT false;
ALTER TABLE privacy_correction ADD COLUMN erased boolean NOT NULL DEFAULT false;
ALTER TABLE operation_control ADD COLUMN privacy_owner uuid;
CREATE TABLE privacy_hold(id uuid PRIMARY KEY,customer_id uuid NOT NULL REFERENCES customer_identity(id),kind varchar(12) NOT NULL,target_id uuid NOT NULL,reason varchar(500) NOT NULL,starts_on date NOT NULL,through date,released_at timestamptz,CHECK(through IS NULL OR through>=starts_on));
CREATE TABLE privacy_erasure(id uuid PRIMARY KEY,customer_id uuid NOT NULL REFERENCES customer_identity(id),actor_id uuid NOT NULL,created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL CHECK(expires_at=created_at+interval '1 hour'),digest varchar(64) NOT NULL CHECK(digest ~ '^[0-9a-f]{64}$'),encrypted_proposal text, state varchar(12) NOT NULL CHECK(state IN ('PREVIEW','APPROVED','APPLIED')),approved_at timestamptz,applied_at timestamptz,previous_maintenance boolean NOT NULL DEFAULT false,previous_suppressed boolean NOT NULL DEFAULT false,confirm_key varchar(120),journal_digest varchar(64),reconciliation_required boolean NOT NULL DEFAULT false);
CREATE TABLE privacy_erasure_target(request_id uuid NOT NULL REFERENCES privacy_erasure(id),kind varchar(12) NOT NULL,target_id uuid NOT NULL,PRIMARY KEY(request_id,kind,target_id));
CREATE TABLE privacy_journal_state(id integer PRIMARY KEY CHECK(id=1),installation_id uuid NOT NULL);
-- Fail closed even for an HTTP request that passed the barrier before waiting for its account/proof lock.
CREATE FUNCTION privacy_write_barrier() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE blocked boolean;
BEGIN
 IF TG_TABLE_NAME='app_session' THEN
  IF NEW.kind NOT IN ('ACCOUNT','GUEST') THEN RETURN NEW; END IF;
 END IF;
 SELECT maintenance INTO blocked FROM operation_control WHERE id=1;
 IF blocked=false THEN SELECT maintenance INTO blocked FROM operation_control WHERE id=1 FOR SHARE; END IF;
 IF TG_TABLE_NAME='customer_profile' AND TG_OP='UPDATE' THEN
  IF blocked AND privacy_context(OLD.customer_id,'PROFILE',OLD.customer_id) AND NEW.document=' {"firstName":"","lastName":"","telephone":""}'::jsonb AND NEW.version>OLD.version THEN RETURN NEW; END IF;
 END IF;
 IF blocked IS DISTINCT FROM false THEN RAISE EXCEPTION 'privacy maintenance' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER privacy_profile_write BEFORE INSERT OR UPDATE ON customer_profile FOR EACH ROW EXECUTE FUNCTION privacy_write_barrier();
CREATE TRIGGER privacy_account_create BEFORE INSERT OR UPDATE ON customer_account FOR EACH ROW EXECUTE FUNCTION privacy_write_barrier();
CREATE TRIGGER privacy_grant_create BEFORE INSERT ON access_grant FOR EACH ROW EXECUTE FUNCTION privacy_write_barrier();
CREATE TRIGGER privacy_session_create BEFORE INSERT ON app_session FOR EACH ROW EXECUTE FUNCTION privacy_write_barrier();
CREATE TRIGGER privacy_proof_create BEFORE INSERT ON account_authentication_proof FOR EACH ROW EXECUTE FUNCTION privacy_write_barrier();
CREATE TRIGGER privacy_challenge_create BEFORE INSERT ON email_challenge FOR EACH ROW EXECUTE FUNCTION privacy_write_barrier();
-- Redaction is the sole exception to immutability, bound to an approved request and its owned barrier.
CREATE FUNCTION privacy_context(target_customer uuid,target_kind text,target uuid) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE request uuid;
BEGIN
 BEGIN request:=current_setting('purpuragenda.erasure',true)::uuid; EXCEPTION WHEN invalid_text_representation THEN RETURN false; END;
 RETURN EXISTS(SELECT 1 FROM privacy_erasure e JOIN operation_control o ON o.id=1 AND o.privacy_owner=e.id JOIN privacy_erasure_target t ON t.request_id=e.id WHERE e.id=request AND e.customer_id=target_customer AND e.state='APPROVED' AND o.maintenance AND t.kind=target_kind AND t.target_id=target);
END $$;
CREATE FUNCTION privacy_immutable_redaction() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE customer uuid; allowed boolean:=false;
BEGIN
 IF TG_OP<>'UPDATE' THEN RAISE EXCEPTION 'immutable business fact' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='booking_snapshot' THEN
  SELECT customer_id INTO customer FROM booking WHERE id=OLD.booking_id;
  allowed:= NEW.booking_id=OLD.booking_id AND
   (NEW.document-'intake'-'intakeErased'-'creationEmail'-'displayZone')=(OLD.document-'intake'-'intakeErased'-'creationEmail'-'displayZone') AND
   ((NEW.document->'intake'=OLD.document->'intake' AND NEW.document->'intakeErased' IS NOT DISTINCT FROM OLD.document->'intakeErased') OR (privacy_context(customer,'INTAKE',OLD.booking_id) AND NEW.document->'intake'='null'::jsonb AND NEW.document->'intakeErased'='true'::jsonb)) AND
   ((NEW.document->'creationEmail'=OLD.document->'creationEmail' AND NEW.document->'displayZone'=OLD.document->'displayZone') OR (privacy_context(customer,'IDENTITY',customer) AND NEW.document->>'creationEmail'='erased@privacy.invalid' AND NEW.document->>'displayZone'='Europe/Brussels'));
 ELSIF TG_TABLE_NAME='audit_event' THEN
  SELECT customer_id INTO customer FROM booking WHERE id=OLD.booking_id;
  IF customer IS NULL THEN SELECT customer_id INTO customer FROM customer_account WHERE id=OLD.actor_id; END IF;
  IF customer IS NULL THEN BEGIN customer:=(OLD.metadata->>'customerId')::uuid; EXCEPTION WHEN invalid_text_representation THEN customer:=NULL; END; END IF;
  allowed:=privacy_context(customer,'AUDIT',OLD.id) AND NEW.reason='' AND NEW.metadata='{}'::jsonb AND (to_jsonb(NEW)-'reason'-'metadata')=(to_jsonb(OLD)-'reason'-'metadata');
 ELSIF TG_TABLE_NAME='booking_contact_change' THEN
  SELECT customer_id INTO customer FROM booking WHERE id=OLD.booking_id;
  allowed:=privacy_context(customer,'IDENTITY',customer) AND NEW.old_email='erased@privacy.invalid' AND NEW.new_email='erased@privacy.invalid' AND (to_jsonb(NEW)-'old_email'-'new_email')=(to_jsonb(OLD)-'old_email'-'new_email');
 ELSIF TG_TABLE_NAME='booking_override' THEN
  SELECT customer_id INTO customer FROM booking WHERE id=OLD.booking_id;
  allowed:=privacy_context(customer,'INTAKE',OLD.booking_id) AND NEW.reason='' AND (to_jsonb(NEW)-'reason')=(to_jsonb(OLD)-'reason');
 ELSIF TG_TABLE_NAME='privacy_correction_fact' THEN
  allowed:=privacy_context(OLD.customer_id,CASE OLD.kind WHEN 'NOTE' THEN 'NOTE' ELSE OLD.kind END,OLD.target_id) AND NEW.prior_fingerprint=repeat('0',64) AND NEW.request_fingerprint=repeat('0',64) AND (to_jsonb(NEW)-'prior_fingerprint'-'request_fingerprint')=(to_jsonb(OLD)-'prior_fingerprint'-'request_fingerprint');
 END IF;
 IF allowed IS DISTINCT FROM true THEN RAISE EXCEPTION 'immutable business fact' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER immutable_snapshot ON booking_snapshot;
CREATE TRIGGER immutable_snapshot BEFORE UPDATE OR DELETE ON booking_snapshot FOR EACH ROW EXECUTE FUNCTION privacy_immutable_redaction();
DROP TRIGGER immutable_audit ON audit_event;
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_event FOR EACH ROW EXECUTE FUNCTION privacy_immutable_redaction();
DROP TRIGGER immutable_contact_history ON booking_contact_change;
CREATE TRIGGER immutable_contact_history BEFORE UPDATE OR DELETE ON booking_contact_change FOR EACH ROW EXECUTE FUNCTION privacy_immutable_redaction();
DROP TRIGGER immutable_override ON booking_override;
CREATE TRIGGER immutable_override BEFORE UPDATE OR DELETE ON booking_override FOR EACH ROW EXECUTE FUNCTION privacy_immutable_redaction();
DROP TRIGGER immutable_correction_fact ON privacy_correction_fact;
CREATE TRIGGER immutable_correction_fact BEFORE UPDATE OR DELETE ON privacy_correction_fact FOR EACH ROW EXECUTE FUNCTION privacy_immutable_redaction();

ALTER TABLE booking DROP CONSTRAINT confirmation_history;
ALTER TABLE booking ADD CONSTRAINT confirmation_history CHECK(
 previously_confirmed=(confirmation_basis IS NOT NULL)
 AND (NOT previously_confirmed OR ((meeting_id IS NOT NULL AND meeting_url IS NOT NULL OR meeting_erased) AND confirmation_basis IN ('MANUAL_APPROVAL','EXTERNAL_PAYMENT')))
 AND (NOT meeting_erased OR (meeting_id IS NULL AND meeting_url IS NULL AND state NOT IN ('PENDING_CONFIRMATION','CONFIRMED')))
 AND (state NOT IN ('PENDING_CONFIRMATION','REJECTED','EXPIRED') OR NOT previously_confirmed)
 AND (state NOT IN ('CONFIRMED','COMPLETED','NO_SHOW') OR previously_confirmed));
CREATE OR REPLACE FUNCTION verify_booking_occupancy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b booking; o booking_occupancy; snapshot jsonb; service_snapshot jsonb; policy_snapshot jsonb;
 lifetime interval; buffer interval;
BEGIN
 -- Deferred events may carry earlier row images; validate the final stored booking.
 SELECT * INTO b FROM booking WHERE id=NEW.id;
 IF b.id IS NULL THEN
   RAISE EXCEPTION 'missing booking invariant target' USING ERRCODE='23514';
 END IF;
 SELECT * INTO o FROM booking_occupancy WHERE booking_id=b.id;
 SELECT document INTO snapshot FROM booking_snapshot WHERE booking_id=b.id;
 service_snapshot:=snapshot->'service'; policy_snapshot:=snapshot->'policy';
 IF jsonb_typeof(snapshot) IS DISTINCT FROM 'object'
 OR NOT snapshot ?& ARRAY['service','policy','intake','creationEmail','displayZone','privacyNoticeVersion']
 OR jsonb_typeof(service_snapshot) IS DISTINCT FROM 'object'
 OR NOT service_snapshot ?& ARRAY['id','version','name','durationMinutes','price','active']
 OR jsonb_typeof(policy_snapshot) IS DISTINCT FROM 'object'
 OR NOT policy_snapshot ?& ARRAY['version','gridMinutes','dailyCapacity','minimumNotice','pendingLifetime','expiryBuffer','customerCutoff']
 OR (snapshot ? 'intakeErased' AND jsonb_typeof(snapshot->'intakeErased') IS DISTINCT FROM 'boolean')
 OR ((jsonb_typeof(snapshot->'intake')='object' AND (snapshot->'intake') ?& ARRAY['firstName','lastName','telephone','birthDate','birthTime','birthplace','message'] AND NOT EXISTS(SELECT 1 FROM unnest(ARRAY['firstName','lastName','telephone','birthDate','birthTime','birthplace','message']) field WHERE jsonb_typeof(snapshot->'intake'->field) IS DISTINCT FROM 'string') AND COALESCE(snapshot->'intakeErased','false'::jsonb)='false'::jsonb) OR (snapshot->'intake'='null'::jsonb AND snapshot->'intakeErased'='true'::jsonb)) IS DISTINCT FROM true
 OR (service_snapshot->>'id')::uuid IS DISTINCT FROM b.service_id
 OR (service_snapshot->>'durationMinutes')::integer IS DISTINCT FROM b.session_minutes
 OR (service_snapshot->>'version')::bigint IS NULL
 OR (policy_snapshot->>'version')::bigint IS NULL
 OR (service_snapshot->>'version')::bigint<=0 OR (policy_snapshot->>'version')::bigint<=0
 OR (policy_snapshot->>'gridMinutes')::integer IS NULL OR (policy_snapshot->>'gridMinutes')::integer NOT BETWEEN 1 AND 120
 OR (policy_snapshot->>'dailyCapacity')::integer IS NULL OR (policy_snapshot->>'dailyCapacity')::integer<0
 OR jsonb_typeof(service_snapshot->'active') IS DISTINCT FROM 'boolean'
 OR service_snapshot->>'name' IS NULL OR service_snapshot->'price'->>'currency' IS DISTINCT FROM 'EUR'
 OR (service_snapshot->'price'->>'minor')::bigint IS NULL
 OR (service_snapshot->'price'->>'minor')::bigint NOT BETWEEN 0 AND 100000000
 OR snapshot->>'creationEmail' IS NULL OR snapshot->>'displayZone' IS NULL
 OR snapshot->>'privacyNoticeVersion' IS NULL
 OR o.booking_id IS NULL OR o.resource_id IS DISTINCT FROM b.resource_id
 OR o.occupied IS DISTINCT FROM tstzrange(b.start_at,b.reserved_end,'[)')
 OR ((b.state IN ('PENDING_CONFIRMATION','CONFIRMED','COMPLETED','NO_SHOW')) IS DISTINCT FROM (o.released_at IS NULL)) THEN
   RAISE EXCEPTION 'booking invariant disagreement' USING ERRCODE='23514';
 END IF;
 lifetime:=snapshot_elapsed(policy_snapshot->'pendingLifetime'); buffer:=snapshot_elapsed(policy_snapshot->'expiryBuffer');
 IF lifetime IS NULL OR buffer IS NULL OR NOT isfinite(lifetime) OR NOT isfinite(buffer)
 OR lifetime<=interval '0' OR buffer<interval '0'
 OR snapshot_elapsed(policy_snapshot->'minimumNotice') IS NULL OR snapshot_elapsed(policy_snapshot->'minimumNotice')<interval '0'
 OR snapshot_elapsed(policy_snapshot->'customerCutoff') IS NULL OR snapshot_elapsed(policy_snapshot->'customerCutoff')<interval '0'
 OR (b.state='PENDING_CONFIRMATION' AND
     b.expiry_ceiling IS DISTINCT FROM LEAST(b.created_at+lifetime,b.start_at-buffer)) THEN
   RAISE EXCEPTION 'booking policy disagreement' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
EXCEPTION WHEN data_exception THEN
 RAISE EXCEPTION 'invalid booking snapshot' USING ERRCODE='23514';
END $$;
