ALTER TABLE access_grant ADD CONSTRAINT grant_session_owner UNIQUE(id,customer_id);
ALTER TABLE customer_account ADD CONSTRAINT account_session_owner UNIQUE(id,customer_id);
CREATE TABLE app_session (
 id uuid PRIMARY KEY, token_hash char(64) NOT NULL UNIQUE CHECK(token_hash ~ '^[a-f0-9]{64}$'),
 browser_hash char(64) NOT NULL CHECK(browser_hash ~ '^[a-f0-9]{64}$'),
 csrf_hash char(64) NOT NULL CHECK(csrf_hash ~ '^[a-f0-9]{64}$'),
 kind varchar(20) NOT NULL CHECK(kind IN ('ANONYMOUS','GUEST','ACCOUNT','ADMIN')),
 grant_id uuid REFERENCES access_grant(id), customer_id uuid REFERENCES customer_identity(id),
 account_id uuid REFERENCES customer_account(id), admin_id uuid REFERENCES admin_user(id),
 admin_security_version bigint CHECK(admin_security_version>=0),
 issued_at timestamptz NOT NULL, last_seen_at timestamptz NOT NULL, absolute_expires_at timestamptz NOT NULL,
 authenticated_at timestamptz, revoked_at timestamptz,
 FOREIGN KEY(grant_id,customer_id) REFERENCES access_grant(id,customer_id),
 FOREIGN KEY(account_id,customer_id) REFERENCES customer_account(id,customer_id),
 CHECK(isfinite(issued_at) AND isfinite(last_seen_at) AND isfinite(absolute_expires_at)
   AND last_seen_at>=issued_at AND last_seen_at<absolute_expires_at),
 CHECK(absolute_expires_at=issued_at+CASE kind WHEN 'ADMIN' THEN interval '8 hours' WHEN 'ACCOUNT' THEN interval '12 hours' ELSE interval '2 hours' END),
 CHECK(authenticated_at IS NULL OR (isfinite(authenticated_at) AND authenticated_at>=issued_at AND authenticated_at<=last_seen_at)),
 CHECK(revoked_at IS NULL OR (isfinite(revoked_at) AND revoked_at>=issued_at)),
 CHECK((kind='ANONYMOUS' AND grant_id IS NULL AND customer_id IS NULL AND account_id IS NULL AND admin_id IS NULL AND admin_security_version IS NULL AND authenticated_at IS NULL)
   OR (kind='GUEST' AND grant_id IS NOT NULL AND customer_id IS NOT NULL AND account_id IS NULL AND admin_id IS NULL AND admin_security_version IS NULL AND authenticated_at IS NOT NULL)
   OR (kind='ACCOUNT' AND grant_id IS NULL AND customer_id IS NOT NULL AND account_id IS NOT NULL AND admin_id IS NULL AND admin_security_version IS NULL AND authenticated_at IS NOT NULL)
   OR (kind='ADMIN' AND grant_id IS NULL AND customer_id IS NULL AND account_id IS NULL AND admin_id IS NOT NULL AND admin_security_version IS NOT NULL AND authenticated_at IS NOT NULL))
);
CREATE INDEX session_expiry ON app_session(absolute_expires_at);
CREATE INDEX session_admin ON app_session(admin_id) WHERE admin_id IS NOT NULL;
CREATE TABLE admin_authentication_proof (
 id uuid PRIMARY KEY, admin_id uuid NOT NULL REFERENCES admin_user(id),
 token_hash char(64) NOT NULL UNIQUE CHECK(token_hash ~ '^[a-f0-9]{64}$'),
 purpose varchar(40) NOT NULL CHECK(purpose='ADMIN_AUTHENTICATION'),
 browser_hash char(64) NOT NULL CHECK(browser_hash ~ '^[a-f0-9]{64}$'),
 request_hash char(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 security_version bigint NOT NULL CHECK(security_version>=0),
 issued_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, consumed_at timestamptz, revoked_at timestamptz,
 CHECK(isfinite(issued_at) AND isfinite(expires_at) AND expires_at=issued_at+interval '60 seconds'),
 CHECK(consumed_at IS NULL OR (isfinite(consumed_at) AND consumed_at>=issued_at AND consumed_at<expires_at)),
 CHECK(revoked_at IS NULL OR (isfinite(revoked_at) AND revoked_at>=issued_at))
);
CREATE INDEX admin_proof_expiry ON admin_authentication_proof(expires_at);
CREATE FUNCTION protect_admin_security_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.security_version<OLD.security_version THEN
  RAISE EXCEPTION 'administrator security epoch cannot decrease' USING ERRCODE='23514';
 END IF;
 IF NEW.enabled IS DISTINCT FROM OLD.enabled OR NEW.password_hash IS DISTINCT FROM OLD.password_hash
    OR NEW.totp_secret IS DISTINCT FROM OLD.totp_secret OR NEW.enrolled IS DISTINCT FROM OLD.enrolled THEN
  NEW.security_version:=GREATEST(NEW.security_version,OLD.security_version+1);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER admin_security_epoch BEFORE UPDATE ON admin_user
 FOR EACH ROW EXECUTE FUNCTION protect_admin_security_epoch();
