-- The original deadline never follows retry due_at; historical immediate jobs remain unchanged.
ALTER TABLE notification_job ADD COLUMN scheduled_at timestamptz;
ALTER TABLE notification_job ADD CONSTRAINT notification_reminder_window CHECK (
 template NOT IN ('REMINDER_48H','REMINDER_2H') OR (
   audience='CUSTOMER' AND booking_id IS NOT NULL AND event_id IS NOT NULL AND scheduled_at IS NOT NULL
   AND isfinite(scheduled_at) AND grace_seconds IS NOT NULL
   AND ((template='REMINDER_48H' AND grace_seconds=3600)
     OR (template='REMINDER_2H' AND grace_seconds=900))));
-- A retry may move due_at, never the original reminder identity or grace deadline.
CREATE FUNCTION protect_reminder_schedule() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.template IN ('REMINDER_48H','REMINDER_2H') AND
  (NEW.scheduled_at IS DISTINCT FROM OLD.scheduled_at OR NEW.grace_seconds IS DISTINCT FROM OLD.grace_seconds
   OR NEW.booking_id IS DISTINCT FROM OLD.booking_id OR NEW.event_id IS DISTINCT FROM OLD.event_id
   OR NEW.audience IS DISTINCT FROM OLD.audience OR NEW.template IS DISTINCT FROM OLD.template
   OR NEW.version IS DISTINCT FROM OLD.version) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='REMINDER_SCHEDULE_IMMUTABLE';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reminder_schedule_immutable BEFORE UPDATE ON notification_job
 FOR EACH ROW EXECUTE FUNCTION protect_reminder_schedule();
