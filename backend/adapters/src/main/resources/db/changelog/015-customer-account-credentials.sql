-- Additive: an optional account is an explicit enrollment with an immutable login identity and password credential.
-- Existing account rows (fixtures only; nothing enrolled before this migration) keep NULL credentials.
ALTER TABLE customer_account
 ADD COLUMN login_email varchar(254) UNIQUE,
 ADD COLUMN password_hash text,
 ADD COLUMN credential_version bigint NOT NULL DEFAULT 1 CHECK(credential_version>0),
 ADD COLUMN password_changed_at timestamptz;
ALTER TABLE customer_account ADD CONSTRAINT account_credential_shape CHECK(
 (login_email IS NULL AND password_hash IS NULL AND password_changed_at IS NULL)
 OR (login_email IS NOT NULL AND password_changed_at IS NOT NULL AND isfinite(password_changed_at)
     AND password_hash ~ '^\$argon2id\$v=19\$m=65536,t=3,p=1\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}$'));
CREATE FUNCTION protect_customer_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE identity_email text;
BEGIN
 IF NEW.login_email IS NOT NULL THEN
  SELECT email_key INTO identity_email FROM customer_identity WHERE id=NEW.customer_id;
  IF identity_email IS DISTINCT FROM NEW.login_email THEN
   RAISE EXCEPTION 'account login identity must equal its verified identity' USING ERRCODE='23514';
  END IF;
 END IF;
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.customer_id<>OLD.customer_id OR NEW.enrolled_at<>OLD.enrolled_at
    OR NEW.login_email IS DISTINCT FROM OLD.login_email
    OR (OLD.password_hash IS NOT NULL AND NEW.password_hash IS NULL)
    OR NEW.credential_version<OLD.credential_version
    OR (NEW.password_hash IS DISTINCT FROM OLD.password_hash AND NEW.credential_version<=OLD.credential_version)) THEN
  RAISE EXCEPTION 'immutable account identity or credential history' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER customer_account_protected BEFORE INSERT OR UPDATE ON customer_account
 FOR EACH ROW EXECUTE FUNCTION protect_customer_account();
-- Failed account logins are reserved like administrator attempts: five per fixed fifteen minutes per account and network.
ALTER TABLE rate_limit_bucket DROP CONSTRAINT rate_bucket_policy;
ALTER TABLE rate_limit_bucket ADD CONSTRAINT rate_bucket_policy CHECK(
 isfinite(window_started_at) AND isfinite(reset_at) AND count>=0 AND
 ((kind='EMAIL_COOLDOWN' AND count<=1 AND reset_at=window_started_at+interval '60 seconds')
  OR (kind='EMAIL_ADDRESS' AND count<=5 AND reset_at=window_started_at+interval '1 hour')
  OR (kind='EMAIL_IP' AND count<=30 AND reset_at=window_started_at+interval '1 hour')
  OR (kind='ADMIN_ATTEMPT' AND count<=5 AND reset_at=window_started_at+interval '15 minutes')
  OR (kind='ACCOUNT_ATTEMPT' AND count<=5 AND reset_at=window_started_at+interval '15 minutes')
  OR (kind='AVAILABILITY_IP' AND count<=120 AND reset_at=window_started_at+interval '60 seconds')));
