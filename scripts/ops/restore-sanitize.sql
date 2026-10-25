BEGIN;
SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE;
DELETE FROM spring_session;
UPDATE access_grant SET revoked_at=clock_timestamp();
UPDATE email_challenge SET consumed_at=clock_timestamp();
UPDATE notification_job SET state='SUPPRESSED',lease_until=NULL,encrypted_envelope=NULL;
UPDATE outbox_event SET expanded_at=clock_timestamp() WHERE expanded_at IS NULL;
UPDATE operation_control SET maintenance=true,delivery_suppressed=true,restore_started_at=clock_timestamp() WHERE id=1;
COMMIT;
