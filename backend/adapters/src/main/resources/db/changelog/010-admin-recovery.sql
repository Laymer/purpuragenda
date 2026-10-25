ALTER TABLE admin_user ADD COLUMN security_version bigint NOT NULL DEFAULT 0 CHECK(security_version>=0);
ALTER TABLE admin_recovery_code ADD COLUMN revoked_at timestamptz;
ALTER TABLE admin_recovery_code ADD CONSTRAINT recovery_code_owner UNIQUE(id,admin_id);
CREATE TABLE admin_reenrollment_grant (
 id uuid PRIMARY KEY, admin_id uuid NOT NULL REFERENCES admin_user(id),
 recovery_code_id uuid NOT NULL UNIQUE REFERENCES admin_recovery_code(id),
 purpose varchar(40) NOT NULL CHECK(purpose='ADMIN_REENROLLMENT'),
 token_hash char(64) NOT NULL UNIQUE CHECK(token_hash ~ '^[a-f0-9]{64}$'),
 browser_hash char(64) NOT NULL CHECK(browser_hash ~ '^[a-f0-9]{64}$'),
 request_hash char(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 security_version bigint NOT NULL CHECK(security_version>0),
 issued_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 consumed_at timestamptz, revoked_at timestamptz,
 FOREIGN KEY(recovery_code_id,admin_id) REFERENCES admin_recovery_code(id,admin_id),
 CHECK(isfinite(issued_at) AND isfinite(expires_at) AND expires_at=issued_at+interval '10 minutes'),
 CHECK(consumed_at IS NULL OR (isfinite(consumed_at) AND consumed_at>=issued_at)),
 CHECK(revoked_at IS NULL OR (isfinite(revoked_at) AND revoked_at>=issued_at))
);
CREATE INDEX admin_reenrollment_pending ON admin_reenrollment_grant(admin_id) WHERE consumed_at IS NULL AND revoked_at IS NULL;
