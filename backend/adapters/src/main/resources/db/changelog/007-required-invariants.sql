-- Applied changesets remain immutable; repair their nullable checks additively.
ALTER TABLE payment_entry DROP CONSTRAINT payment_entry_amount_minor_check;
ALTER TABLE payment_entry ADD CONSTRAINT positive_actual_money CHECK(amount_minor>0);
ALTER TABLE booking ADD CONSTRAINT finite_booking_times CHECK(
 isfinite(created_at) AND isfinite(start_at) AND isfinite(session_end) AND isfinite(reserved_end)
 AND start_at>created_at AND (expires_at IS NULL OR isfinite(expires_at))
 AND (expiry_ceiling IS NULL OR isfinite(expiry_ceiling)));
ALTER TABLE booking ADD CONSTRAINT required_pending_ceiling CHECK(
 state<>'PENDING_CONFIRMATION' OR expiry_ceiling IS NOT NULL);
ALTER TABLE booking ADD CONSTRAINT confirmation_history CHECK(
 previously_confirmed=(confirmation_basis IS NOT NULL)
 AND (NOT previously_confirmed OR (meeting_id IS NOT NULL AND meeting_url IS NOT NULL
      AND confirmation_basis IN ('MANUAL_APPROVAL','EXTERNAL_PAYMENT')))
 AND (state NOT IN ('PENDING_CONFIRMATION','REJECTED','EXPIRED') OR NOT previously_confirmed)
 AND (state NOT IN ('CONFIRMED','COMPLETED','NO_SHOW') OR previously_confirmed));

CREATE FUNCTION snapshot_elapsed(value jsonb) RETURNS interval LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE duration interval;
BEGIN
 duration:=CASE WHEN jsonb_typeof(value)='number' THEN (value#>>'{}')::numeric*interval '1 second'
   ELSE (value#>>'{}')::interval END;
 IF duration IS NULL OR NOT isfinite(duration) OR extract(year FROM duration)<>0 OR extract(month FROM duration)<>0 THEN
   RAISE EXCEPTION 'invalid elapsed policy duration' USING ERRCODE='23514';
 END IF;
 -- Normalize civil days to elapsed seconds, independent of connection timezone/DST.
 RETURN extract(epoch FROM duration)*interval '1 second';
END $$;

CREATE OR REPLACE FUNCTION verify_booking_occupancy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o booking_occupancy; snapshot jsonb; service_snapshot jsonb; policy_snapshot jsonb;
 lifetime interval; buffer interval;
BEGIN
 SELECT * INTO o FROM booking_occupancy WHERE booking_id=NEW.id;
 SELECT document INTO snapshot FROM booking_snapshot WHERE booking_id=NEW.id;
 service_snapshot:=snapshot->'service'; policy_snapshot:=snapshot->'policy';
 IF jsonb_typeof(snapshot) IS DISTINCT FROM 'object'
 OR NOT snapshot ?& ARRAY['service','policy','intake','creationEmail','displayZone','privacyNoticeVersion']
 OR jsonb_typeof(service_snapshot) IS DISTINCT FROM 'object'
 OR NOT service_snapshot ?& ARRAY['id','version','name','durationMinutes','price','active']
 OR jsonb_typeof(policy_snapshot) IS DISTINCT FROM 'object'
 OR NOT policy_snapshot ?& ARRAY['version','gridMinutes','dailyCapacity','minimumNotice','pendingLifetime','expiryBuffer','customerCutoff']
 OR jsonb_typeof(snapshot->'intake') IS DISTINCT FROM 'object'
 OR NOT (snapshot->'intake') ?& ARRAY['firstName','lastName','telephone','birthDate','birthTime','birthplace','message']
 OR (service_snapshot->>'id')::uuid IS DISTINCT FROM NEW.service_id
 OR (service_snapshot->>'durationMinutes')::integer IS DISTINCT FROM NEW.session_minutes
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
 OR o.booking_id IS NULL OR o.resource_id IS DISTINCT FROM NEW.resource_id
 OR o.occupied IS DISTINCT FROM tstzrange(NEW.start_at,NEW.reserved_end,'[)')
 OR ((NEW.state IN ('PENDING_CONFIRMATION','CONFIRMED','COMPLETED','NO_SHOW')) IS DISTINCT FROM (o.released_at IS NULL)) THEN
   RAISE EXCEPTION 'booking invariant disagreement' USING ERRCODE='23514';
 END IF;
 lifetime:=snapshot_elapsed(policy_snapshot->'pendingLifetime'); buffer:=snapshot_elapsed(policy_snapshot->'expiryBuffer');
 IF lifetime IS NULL OR buffer IS NULL OR NOT isfinite(lifetime) OR NOT isfinite(buffer)
 OR lifetime<=interval '0' OR buffer<interval '0'
 OR snapshot_elapsed(policy_snapshot->'minimumNotice') IS NULL OR snapshot_elapsed(policy_snapshot->'minimumNotice')<interval '0'
 OR snapshot_elapsed(policy_snapshot->'customerCutoff') IS NULL OR snapshot_elapsed(policy_snapshot->'customerCutoff')<interval '0'
 OR (NEW.state='PENDING_CONFIRMATION' AND
     NEW.expiry_ceiling IS DISTINCT FROM LEAST(NEW.created_at+lifetime,NEW.start_at-buffer)) THEN
   RAISE EXCEPTION 'booking policy disagreement' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
EXCEPTION WHEN data_exception THEN
 RAISE EXCEPTION 'invalid booking snapshot' USING ERRCODE='23514';
END $$;

-- Deletion must not remove the independent capacity barrier for an existing booking.
CREATE CONSTRAINT TRIGGER occupancy_delete_consistency AFTER DELETE ON booking_occupancy
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_occupancy();
CREATE FUNCTION protect_occupancy_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.booking_id IS DISTINCT FROM OLD.booking_id THEN
   RAISE EXCEPTION 'immutable occupancy identity' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER occupancy_identity_immutable BEFORE UPDATE ON booking_occupancy
 FOR EACH ROW EXECUTE FUNCTION protect_occupancy_identity();
-- Validate already-applied history too; migration must fail on inconsistent old rows.
UPDATE booking SET version=version;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='purpuragenda_app') THEN
   REVOKE ALL ON databasechangelog,databasechangeloglock FROM purpuragenda_app;
 END IF;
END $$;
