ALTER TABLE access_grant ADD COLUMN issued_at timestamptz,
 ADD COLUMN browser_hash char(64),
 ADD COLUMN challenge_id uuid UNIQUE REFERENCES email_challenge(id),
 ADD COLUMN parent_grant_id uuid UNIQUE REFERENCES access_grant(id);
-- Historical grants have no verifiable browser/proof provenance. Preserve, but never trust them.
UPDATE access_grant SET revoked_at=COALESCE(revoked_at,clock_timestamp());
ALTER TABLE access_grant ADD CONSTRAINT guest_grant_shape CHECK(revoked_at IS NOT NULL OR (
 issued_at IS NOT NULL AND isfinite(issued_at) AND isfinite(expires_at) AND expires_at>issued_at
 AND browser_hash IS NOT NULL AND browser_hash ~ '^[a-f0-9]{64}$'
 AND ((challenge_id IS NULL)<>(parent_grant_id IS NULL))
 AND (consumed_at IS NULL OR (consumed_at>=issued_at AND consumed_at<expires_at))
 AND ((kind='CREATE' AND parent_grant_id IS NULL AND expires_at=issued_at+interval '30 minutes'
       AND ((booking_id IS NULL)=(consumed_at IS NULL)))
   OR (kind='MANAGE' AND booking_id IS NOT NULL AND consumed_at IS NULL AND expires_at<=issued_at+interval '2 hours'))));

CREATE FUNCTION protect_guest_grant() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE challenge email_challenge; parent access_grant; owner uuid; identity_email text;
BEGIN
 IF TG_OP='UPDATE' THEN
  IF (NEW.id,NEW.customer_id,NEW.kind,NEW.issued_at,NEW.expires_at,NEW.browser_hash,NEW.challenge_id,NEW.parent_grant_id)
       IS DISTINCT FROM (OLD.id,OLD.customer_id,OLD.kind,OLD.issued_at,OLD.expires_at,OLD.browser_hash,OLD.challenge_id,OLD.parent_grant_id)
    OR (OLD.booking_id IS NOT NULL AND NEW.booking_id IS DISTINCT FROM OLD.booking_id)
    OR (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at)
    OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at) THEN
   RAISE EXCEPTION 'immutable guest grant authority' USING ERRCODE='23514';
  END IF;
 END IF;
 IF NEW.revoked_at IS NOT NULL THEN RETURN NEW; END IF;
 IF NEW.booking_id IS NOT NULL THEN
  SELECT customer_id INTO owner FROM booking WHERE id=NEW.booking_id;
  IF owner IS DISTINCT FROM NEW.customer_id THEN
   RAISE EXCEPTION 'guest grant owner disagreement' USING ERRCODE='23514';
  END IF;
 END IF;
 IF NEW.challenge_id IS NOT NULL THEN
  SELECT * INTO challenge FROM email_challenge WHERE id=NEW.challenge_id;
  IF challenge.id IS NULL OR challenge.purpose<>NEW.kind OR challenge.consumed_at IS DISTINCT FROM NEW.issued_at
    OR challenge.expires_at<=NEW.issued_at THEN
   RAISE EXCEPTION 'guest grant proof disagreement' USING ERRCODE='23514';
  END IF;
  IF NEW.kind='CREATE' THEN
   SELECT email_key INTO identity_email FROM customer_identity WHERE id=NEW.customer_id;
   IF challenge.booking_id IS NOT NULL OR challenge.browser_hash IS DISTINCT FROM NEW.browser_hash
     OR challenge.email_key IS DISTINCT FROM identity_email THEN
    RAISE EXCEPTION 'guest creation scope disagreement' USING ERRCODE='23514';
   END IF;
  ELSIF challenge.booking_id IS DISTINCT FROM NEW.booking_id OR challenge.browser_hash IS NOT NULL THEN
   RAISE EXCEPTION 'guest management scope disagreement' USING ERRCODE='23514';
  END IF;
 ELSE
  SELECT * INTO parent FROM access_grant WHERE id=NEW.parent_grant_id;
  IF parent.id IS NULL OR parent.kind<>'CREATE' OR parent.revoked_at IS NOT NULL OR parent.consumed_at IS NULL
    OR parent.customer_id<>NEW.customer_id OR parent.browser_hash IS DISTINCT FROM NEW.browser_hash
    OR parent.booking_id IS DISTINCT FROM NEW.booking_id OR parent.consumed_at IS DISTINCT FROM NEW.issued_at
    OR NEW.kind<>'MANAGE' OR NEW.expires_at>parent.issued_at+interval '2 hours' THEN
   RAISE EXCEPTION 'guest derived scope disagreement' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guest_grant_authority BEFORE INSERT OR UPDATE ON access_grant
 FOR EACH ROW EXECUTE FUNCTION protect_guest_grant();
