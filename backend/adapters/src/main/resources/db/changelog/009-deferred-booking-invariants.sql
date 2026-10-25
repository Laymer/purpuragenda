-- Keep applied001–008 immutable; only repair deferred final-state lookup.
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
 OR jsonb_typeof(snapshot->'intake') IS DISTINCT FROM 'object'
 OR NOT (snapshot->'intake') ?& ARRAY['firstName','lastName','telephone','birthDate','birthTime','birthplace','message']
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


-- Revalidate existing history; inconsistent upgrades fail atomically.
UPDATE booking SET version=version;
