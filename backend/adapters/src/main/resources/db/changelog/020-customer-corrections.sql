CREATE TABLE privacy_correction (
 id uuid PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customer_identity(id), actor_id uuid NOT NULL REFERENCES admin_user(id),
 kind varchar(10) NOT NULL CHECK(kind IN ('PROFILE','NOTE','INTAKE')), target_id uuid NOT NULL,
 created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL CHECK(expires_at=created_at+interval '1 hour'),
 digest varchar(64) NOT NULL CHECK(digest ~ '^[0-9a-f]{64}$'), encrypted_proposal text,
 confirmed_at timestamptz, CHECK((confirmed_at IS NULL AND encrypted_proposal IS NOT NULL) OR (confirmed_at IS NOT NULL AND confirmed_at>=created_at AND confirmed_at<expires_at AND encrypted_proposal IS NULL))
);
CREATE INDEX privacy_correction_expiry ON privacy_correction(expires_at) WHERE encrypted_proposal IS NOT NULL;
CREATE TABLE privacy_correction_fact (
 id uuid PRIMARY KEY REFERENCES privacy_correction(id), customer_id uuid NOT NULL REFERENCES customer_identity(id),
 kind varchar(10) NOT NULL CHECK(kind IN ('PROFILE','NOTE','INTAKE')), target_id uuid NOT NULL,
 revision bigint NOT NULL CHECK(revision>1), prior_fingerprint varchar(64) NOT NULL CHECK(prior_fingerprint ~ '^[0-9a-f]{64}$'),
 request_fingerprint varchar(64) NOT NULL CHECK(request_fingerprint ~ '^[0-9a-f]{64}$'),
 UNIQUE(kind,target_id,revision)
);
CREATE TRIGGER immutable_correction_fact BEFORE UPDATE OR DELETE ON privacy_correction_fact FOR EACH ROW EXECUTE FUNCTION immutable_fact();
