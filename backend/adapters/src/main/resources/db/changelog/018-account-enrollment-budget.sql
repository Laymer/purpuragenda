-- Network admission precedes password hashing for explicit optional account enrollment.
ALTER TABLE rate_limit_bucket ALTER COLUMN kind TYPE varchar(24);
ALTER TABLE rate_limit_bucket DROP CONSTRAINT rate_bucket_policy;
ALTER TABLE rate_limit_bucket ADD CONSTRAINT rate_bucket_policy_v2 CHECK(
 isfinite(window_started_at) AND isfinite(reset_at) AND count>=0 AND
 ((kind='EMAIL_COOLDOWN' AND count<=1 AND reset_at=window_started_at+interval '60 seconds')
  OR (kind='EMAIL_ADDRESS' AND count<=5 AND reset_at=window_started_at+interval '1 hour')
  OR (kind='EMAIL_IP' AND count<=30 AND reset_at=window_started_at+interval '1 hour')
  OR (kind='ADMIN_ATTEMPT' AND count<=5 AND reset_at=window_started_at+interval '15 minutes')
  OR (kind='ACCOUNT_ATTEMPT' AND count<=5 AND reset_at=window_started_at+interval '15 minutes')
  OR (kind='ACCOUNT_ENROLLMENT_IP' AND count<=5 AND reset_at=window_started_at+interval '15 minutes')
  OR (kind='AVAILABILITY_IP' AND count<=120 AND reset_at=window_started_at+interval '60 seconds')));
