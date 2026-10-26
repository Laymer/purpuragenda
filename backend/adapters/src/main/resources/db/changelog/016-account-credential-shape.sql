-- Preserve 015 and its applied checksum. SQL CHECK accepts UNKNOWN, so require
-- every credential component explicitly rather than relying on the hash regex.
ALTER TABLE customer_account DROP CONSTRAINT account_credential_shape;
ALTER TABLE customer_account ADD CONSTRAINT account_credential_shape_v2 CHECK(
 (login_email IS NULL AND password_hash IS NULL AND password_changed_at IS NULL)
 OR (login_email IS NOT NULL AND password_hash IS NOT NULL
     AND password_changed_at IS NOT NULL AND isfinite(password_changed_at)
     AND password_hash ~ '^\$argon2id\$v=19\$m=65536,t=3,p=1\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}$'));
