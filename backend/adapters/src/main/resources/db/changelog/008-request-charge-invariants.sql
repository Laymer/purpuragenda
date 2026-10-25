-- Do not invent charges for historical bookings; validate existing facts and counters only.
ALTER TABLE customer_request_day ADD CONSTRAINT three_requests_per_submission_day CHECK(count<=3);
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM request_charge c JOIN booking b ON b.id=c.booking_id
   WHERE c.customer_id<>b.customer_id OR b.origin<>'CUSTOMER'
      OR c.submission_day<>(b.created_at AT TIME ZONE 'Europe/Brussels')::date)
 OR EXISTS(SELECT 1 FROM customer_request_day d FULL JOIN
   (SELECT customer_id,submission_day,count(*) AS charges FROM request_charge GROUP BY customer_id,submission_day) c
   USING(customer_id,submission_day) WHERE COALESCE(d.count,0)<>COALESCE(c.charges,0)) THEN
   RAISE EXCEPTION 'invalid historical request accounting' USING ERRCODE='23514';
 END IF;
END $$;

CREATE FUNCTION verify_request_charge_shape() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b booking;
BEGIN
 IF TG_TABLE_NAME='booking' THEN
   IF EXISTS(SELECT 1 FROM request_charge c WHERE c.booking_id=NEW.id AND
      (c.customer_id<>NEW.customer_id OR NEW.origin<>'CUSTOMER'
       OR c.submission_day<>(NEW.created_at AT TIME ZONE 'Europe/Brussels')::date)) THEN
     RAISE EXCEPTION 'request charge identity disagreement' USING ERRCODE='23514';
   END IF;
 ELSE
   SELECT * INTO b FROM booking WHERE id=NEW.booking_id;
   IF b.id IS NULL OR NEW.customer_id<>b.customer_id OR b.origin<>'CUSTOMER'
      OR NEW.submission_day<>(b.created_at AT TIME ZONE 'Europe/Brussels')::date THEN
     RAISE EXCEPTION 'request charge shape disagreement' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER request_charge_shape BEFORE INSERT ON request_charge FOR EACH ROW EXECUTE FUNCTION verify_request_charge_shape();
CREATE TRIGGER charged_booking_identity BEFORE UPDATE OF customer_id,created_at,origin ON booking
 FOR EACH ROW EXECUTE FUNCTION verify_request_charge_shape();

CREATE FUNCTION protect_request_day_key() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.customer_id<>OLD.customer_id OR NEW.submission_day<>OLD.submission_day THEN
   RAISE EXCEPTION 'immutable request day identity' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER request_day_key BEFORE UPDATE ON customer_request_day FOR EACH ROW EXECUTE FUNCTION protect_request_day_key();

CREATE FUNCTION verify_request_day_count() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE identity_id uuid; day date; actual bigint; counter integer;
BEGIN
 IF TG_OP='DELETE' THEN identity_id:=OLD.customer_id; day:=OLD.submission_day;
 ELSE identity_id:=NEW.customer_id; day:=NEW.submission_day; END IF;
 SELECT count(*) INTO actual FROM request_charge WHERE customer_id=identity_id AND submission_day=day;
 SELECT count INTO counter FROM customer_request_day WHERE customer_id=identity_id AND submission_day=day;
 IF actual<>COALESCE(counter,0) THEN
   RAISE EXCEPTION 'request counter disagreement' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER request_counter_consistency AFTER INSERT OR UPDATE OR DELETE ON customer_request_day
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_request_day_count();
CREATE CONSTRAINT TRIGGER request_charge_counter_consistency AFTER INSERT ON request_charge
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_request_day_count();
