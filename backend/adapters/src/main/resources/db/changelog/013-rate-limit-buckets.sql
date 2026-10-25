-- No earlier code wrote this table; its rows are ephemeral throttle state with no history value.
DELETE FROM rate_limit_bucket;
ALTER TABLE rate_limit_bucket
 ADD COLUMN kind varchar(20) NOT NULL,
 ADD COLUMN key_id varchar(64) NOT NULL,
 ADD COLUMN window_started_at timestamptz NOT NULL;
ALTER TABLE rate_limit_bucket ADD CONSTRAINT rate_bucket_digest CHECK(key ~ '^[a-f0-9]{64}$' AND key_id ~ '^[A-Za-z0-9_-]{1,64}$');
-- Count ceilings and window lengths mirror the approved policy so a defective caller cannot widen them.
ALTER TABLE rate_limit_bucket ADD CONSTRAINT rate_bucket_policy CHECK(
 isfinite(window_started_at) AND isfinite(reset_at) AND count>=0 AND
 ((kind='EMAIL_COOLDOWN' AND count<=1 AND reset_at=window_started_at+interval '60 seconds')
  OR (kind='EMAIL_ADDRESS' AND count<=5 AND reset_at=window_started_at+interval '1 hour')
  OR (kind='EMAIL_IP' AND count<=30 AND reset_at=window_started_at+interval '1 hour')
  OR (kind='ADMIN_ATTEMPT' AND count<=5 AND reset_at=window_started_at+interval '15 minutes')
  OR (kind='AVAILABILITY_IP' AND count<=120 AND reset_at=window_started_at+interval '60 seconds')));
CREATE INDEX rate_bucket_reset ON rate_limit_bucket(reset_at);
