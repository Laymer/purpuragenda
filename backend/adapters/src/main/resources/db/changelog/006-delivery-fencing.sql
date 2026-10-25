ALTER TABLE notification_job ADD COLUMN lease_token uuid;
ALTER TABLE delivery_attempt ADD COLUMN lease_token uuid;
