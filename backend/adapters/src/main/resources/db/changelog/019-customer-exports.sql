-- Existing prototype exports lack authority/snapshot metadata and remain unreadable.
-- The expiry sweep also removes those legacy rows; no existing business facts are modified.
ALTER TABLE privacy_export ADD COLUMN customer_id uuid REFERENCES customer_identity(id);
ALTER TABLE privacy_export ADD COLUMN actor_id uuid REFERENCES admin_user(id);
ALTER TABLE privacy_export ADD COLUMN created_at timestamptz;
ALTER TABLE privacy_export ADD COLUMN digest char(64);
ALTER TABLE privacy_export ADD COLUMN released_at timestamptz;
ALTER TABLE privacy_export ADD CONSTRAINT export_shape CHECK (
 (customer_id IS NULL AND actor_id IS NULL AND created_at IS NULL AND digest IS NULL AND released_at IS NULL)
 OR (customer_id IS NOT NULL AND actor_id IS NOT NULL AND created_at IS NOT NULL AND digest IS NOT NULL
     AND digest ~ '^[0-9a-f]{64}$' AND expires_at=created_at+interval '1 hour'
     AND (released_at IS NULL OR (released_at>=created_at AND released_at<expires_at))));
CREATE INDEX privacy_export_expiry ON privacy_export(expires_at,id);
CREATE INDEX privacy_export_customer ON privacy_export(customer_id);
