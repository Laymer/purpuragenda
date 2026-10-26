-- Existing ACCOUNT sessions predate verifiable credential epochs. Preserve but revoke them.
ALTER TABLE app_session ADD COLUMN account_credential_version bigint CHECK(account_credential_version>0);
UPDATE app_session s SET account_credential_version=a.credential_version,
 revoked_at=COALESCE(s.revoked_at,GREATEST(clock_timestamp(),s.issued_at))
 FROM customer_account a WHERE s.kind='ACCOUNT' AND a.id=s.account_id;
ALTER TABLE app_session ADD CONSTRAINT account_session_epoch_shape CHECK(
 (kind='ACCOUNT' AND account_credential_version IS NOT NULL)
 OR (kind<>'ACCOUNT' AND account_credential_version IS NULL));
CREATE INDEX session_account ON app_session(account_id) WHERE account_id IS NOT NULL;
CREATE TABLE account_authentication_proof (
 id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES customer_account(id),
 token_hash char(64) NOT NULL UNIQUE CHECK(token_hash ~ '^[a-f0-9]{64}$'),
 purpose varchar(40) NOT NULL CHECK(purpose='ACCOUNT_AUTHENTICATION'),
 browser_hash char(64) NOT NULL CHECK(browser_hash ~ '^[a-f0-9]{64}$'),
 request_hash char(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 credential_version bigint NOT NULL CHECK(credential_version>0),
 issued_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 consumed_at timestamptz, revoked_at timestamptz,
 CHECK(isfinite(issued_at) AND isfinite(expires_at) AND expires_at=issued_at+interval '60 seconds'),
 CHECK(consumed_at IS NULL OR (isfinite(consumed_at) AND consumed_at>=issued_at AND consumed_at<expires_at)),
 CHECK(revoked_at IS NULL OR (isfinite(revoked_at) AND revoked_at>=issued_at))
);
CREATE INDEX account_proof_expiry ON account_authentication_proof(expires_at);
