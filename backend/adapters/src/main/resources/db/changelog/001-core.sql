CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE practitioner (id integer PRIMARY KEY CHECK(id=1), display_name varchar(120) NOT NULL);
CREATE TABLE scheduling_resource (id integer PRIMARY KEY CHECK(id=1), practitioner_id integer NOT NULL REFERENCES practitioner(id));
CREATE TABLE policy_version (version bigint PRIMARY KEY CHECK(version>0), document jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE business_settings (id integer PRIMARY KEY CHECK(id=1), policy_version bigint NOT NULL REFERENCES policy_version(version), calendar jsonb NOT NULL);
CREATE TABLE service (id uuid PRIMARY KEY, version bigint NOT NULL CHECK(version>0), name varchar(120) NOT NULL, duration_minutes integer NOT NULL CHECK(duration_minutes BETWEEN 1 AND 120), price_minor bigint NOT NULL CHECK(price_minor BETWEEN 0 AND 100000000), currency char(3) NOT NULL CHECK(currency='EUR'), active boolean NOT NULL);
CREATE TABLE customer_identity (id uuid PRIMARY KEY, email_key varchar(254) NOT NULL UNIQUE, verified_at timestamptz NOT NULL);
CREATE TABLE customer_account (id uuid PRIMARY KEY, customer_id uuid NOT NULL UNIQUE REFERENCES customer_identity(id), enrolled_at timestamptz NOT NULL);
CREATE TABLE customer_profile (customer_id uuid PRIMARY KEY REFERENCES customer_identity(id), version bigint NOT NULL DEFAULT 1, document jsonb NOT NULL);
CREATE TABLE admin_user (id uuid PRIMARY KEY, email_key varchar(254) NOT NULL UNIQUE, password_hash text NOT NULL, totp_secret text NOT NULL, last_totp_step bigint NOT NULL DEFAULT -1, enrolled boolean NOT NULL DEFAULT false, enabled boolean NOT NULL DEFAULT true);
CREATE UNIQUE INDEX one_enabled_admin ON admin_user ((enabled)) WHERE enabled;
CREATE TABLE admin_recovery_code (id uuid PRIMARY KEY, admin_id uuid NOT NULL REFERENCES admin_user(id), code_hash char(64) NOT NULL UNIQUE, consumed_at timestamptz);
CREATE TABLE email_challenge (id uuid PRIMARY KEY, token_hash char(64) NOT NULL UNIQUE, email_key varchar(254) NOT NULL, purpose varchar(40) NOT NULL CHECK(purpose IN ('CREATE','ACCOUNT','MANAGE','CONTACT')), booking_id uuid, browser_hash char(64), expires_at timestamptz NOT NULL, consumed_at timestamptz);
CREATE INDEX challenge_expiry ON email_challenge(expires_at);
CREATE TABLE access_grant (id uuid PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customer_identity(id), kind varchar(40) NOT NULL, booking_id uuid, expires_at timestamptz NOT NULL, consumed_at timestamptz, revoked_at timestamptz);
CREATE TABLE booking (
 id uuid PRIMARY KEY, reference varchar(80) NOT NULL UNIQUE, customer_id uuid NOT NULL REFERENCES customer_identity(id), resource_id integer NOT NULL DEFAULT 1 REFERENCES scheduling_resource(id), service_id uuid NOT NULL REFERENCES service(id),
 state varchar(40) NOT NULL CHECK(state IN ('PENDING_CONFIRMATION','CONFIRMED','CANCELLED','REJECTED','EXPIRED','COMPLETED','NO_SHOW')),
 origin varchar(40) NOT NULL CHECK(origin IN ('CUSTOMER','ADMINISTRATOR')), created_at timestamptz NOT NULL, start_at timestamptz NOT NULL,
 session_minutes integer NOT NULL CHECK(session_minutes BETWEEN 1 AND 120), session_end timestamptz NOT NULL, reserved_end timestamptz NOT NULL,
 business_date date NOT NULL, business_zone text NOT NULL DEFAULT 'Europe/Brussels' CHECK(business_zone='Europe/Brussels'), expires_at timestamptz,
 expiry_ceiling timestamptz, delivery_email varchar(254) NOT NULL, meeting_id varchar(12) UNIQUE, meeting_url text, confirmation_basis varchar(40),
 version bigint NOT NULL CHECK(version>0), calendar_revision integer NOT NULL CHECK(calendar_revision>=0), previously_confirmed boolean NOT NULL DEFAULT false,
 CHECK(session_end=start_at+session_minutes*interval '1 minute'), CHECK(reserved_end=start_at+interval '120 minutes'),
 CHECK(business_date=(start_at AT TIME ZONE 'Europe/Brussels')::date),
 CHECK(state<>'PENDING_CONFIRMATION' OR (expires_at IS NOT NULL AND expires_at>created_at AND expires_at<=expiry_ceiling)),
 CHECK(state<>'CONFIRMED' OR (meeting_id IS NOT NULL AND meeting_url IS NOT NULL AND confirmation_basis IN ('MANUAL_APPROVAL','EXTERNAL_PAYMENT'))),
 CHECK(meeting_url IS NULL OR meeting_url ~ '^https://[^/@:]+/j/[0-9]{9,12}(\?pwd=[A-Za-z0-9_.~-]{1,256})?$')
);
ALTER TABLE access_grant ADD CONSTRAINT grant_booking_fk FOREIGN KEY(booking_id) REFERENCES booking(id);
ALTER TABLE email_challenge ADD CONSTRAINT challenge_booking_fk FOREIGN KEY(booking_id) REFERENCES booking(id);
CREATE TABLE booking_snapshot (booking_id uuid PRIMARY KEY REFERENCES booking(id), document jsonb NOT NULL);
CREATE TABLE booking_occupancy (booking_id uuid PRIMARY KEY REFERENCES booking(id), resource_id integer NOT NULL REFERENCES scheduling_resource(id), occupied tstzrange NOT NULL, released_at timestamptz,
 CONSTRAINT occupancy_shape CHECK (NOT isempty(occupied) AND NOT lower_inf(occupied) AND NOT upper_inf(occupied) AND lower_inc(occupied) AND NOT upper_inc(occupied) AND upper(occupied)-lower(occupied)=interval '120 minutes'),
 CONSTRAINT practitioner_no_overlap EXCLUDE USING gist(resource_id WITH =, occupied WITH &&) WHERE(released_at IS NULL));
CREATE INDEX booking_day_state ON booking(resource_id,business_date,state);
CREATE INDEX booking_customer_active ON booking(customer_id,state,session_end);
CREATE INDEX pending_expiry ON booking(expires_at,id) WHERE state='PENDING_CONFIRMATION';
CREATE TABLE customer_request_day (customer_id uuid NOT NULL REFERENCES customer_identity(id), submission_day date NOT NULL, count integer NOT NULL CHECK(count>=0), PRIMARY KEY(customer_id,submission_day));
CREATE TABLE request_charge (booking_id uuid PRIMARY KEY REFERENCES booking(id), customer_id uuid NOT NULL REFERENCES customer_identity(id), submission_day date NOT NULL, origin varchar(40) NOT NULL CHECK(origin='CUSTOMER'));
CREATE TABLE payment_entry (id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES booking(id), kind varchar(20) NOT NULL CHECK(kind IN ('RECEIPT','REFUND')), amount_minor bigint NOT NULL CHECK(amount_minor>0 AND amount_minor<=100000000), currency char(3) NOT NULL, method varchar(80) NOT NULL, reference varchar(120) NOT NULL, verified_at timestamptz NOT NULL, receipt_id uuid REFERENCES payment_entry(id), UNIQUE(kind,method,reference), CHECK((kind='REFUND')=(receipt_id IS NOT NULL)));
CREATE INDEX payment_booking_time ON payment_entry(booking_id,verified_at);
CREATE TABLE refund_obligation (receipt_id uuid PRIMARY KEY REFERENCES payment_entry(id), due_minor bigint NOT NULL CHECK(due_minor>0), reason varchar(120) NOT NULL);
CREATE TABLE idempotency_record (scope varchar(240) NOT NULL, key varchar(120) NOT NULL, fingerprint char(64) NOT NULL, receipt jsonb NOT NULL, created_at timestamptz NOT NULL, PRIMARY KEY(scope,key));
CREATE TABLE audit_event (id uuid PRIMARY KEY, booking_id uuid REFERENCES booking(id), actor_id uuid, actor_kind varchar(40) NOT NULL, type varchar(80) NOT NULL, reason varchar(1000) NOT NULL DEFAULT '', occurred_at timestamptz NOT NULL, version bigint);
CREATE INDEX audit_booking_time ON audit_event(booking_id,occurred_at);
CREATE TABLE booking_note (id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES booking(id), actor_id uuid NOT NULL, note text NOT NULL CHECK(length(note)<=4000), created_at timestamptz NOT NULL);
CREATE TABLE booking_amendment (id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES booking(id), actor_id uuid NOT NULL, document jsonb NOT NULL, reason varchar(500) NOT NULL, created_at timestamptz NOT NULL);
CREATE TABLE outbox_event (id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES booking(id), type varchar(80) NOT NULL, version bigint NOT NULL, created_at timestamptz NOT NULL, expanded_at timestamptz);
CREATE TABLE notification_job (id uuid PRIMARY KEY, event_id uuid REFERENCES outbox_event(id), booking_id uuid REFERENCES booking(id), audience varchar(20) NOT NULL, template varchar(80) NOT NULL, version bigint NOT NULL DEFAULT 0, due_at timestamptz NOT NULL, grace_seconds integer, state varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','LEASED','SENT','SUPPRESSED','FAILED')), lease_until timestamptz, attempts integer NOT NULL DEFAULT 0, safe_error varchar(80), encrypted_envelope text, challenge_id uuid REFERENCES email_challenge(id), UNIQUE(event_id,audience,template,version));
CREATE INDEX notification_due ON notification_job(state,due_at,lease_until);
CREATE INDEX notification_booking ON notification_job(booking_id,audience,version);
CREATE TABLE delivery_attempt (id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES notification_job(id), attempted_at timestamptz NOT NULL, success boolean NOT NULL, safe_code varchar(80));
CREATE TABLE rate_limit_bucket (key char(64) PRIMARY KEY, count integer NOT NULL, reset_at timestamptz NOT NULL);
CREATE TABLE operation_control (id integer PRIMARY KEY CHECK(id=1), maintenance boolean NOT NULL DEFAULT false, delivery_suppressed boolean NOT NULL DEFAULT false, restore_started_at timestamptz);
CREATE TABLE erasure_tombstone (id uuid PRIMARY KEY, customer_id uuid NOT NULL, encrypted_document text NOT NULL, created_at timestamptz NOT NULL, applied_at timestamptz);
CREATE TABLE spring_session (primary_id char(36) PRIMARY KEY, session_id char(36) NOT NULL UNIQUE, creation_time bigint NOT NULL, last_access_time bigint NOT NULL, max_inactive_interval integer NOT NULL, expiry_time bigint NOT NULL, principal_name varchar(100));
CREATE INDEX spring_session_expiry ON spring_session(expiry_time);
CREATE INDEX spring_session_principal ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (session_primary_id char(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE, attribute_name varchar(200) NOT NULL, attribute_bytes bytea NOT NULL, PRIMARY KEY(session_primary_id,attribute_name));

CREATE FUNCTION immutable_fact() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable business fact' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_payment BEFORE UPDATE OR DELETE ON payment_entry FOR EACH ROW EXECUTE FUNCTION immutable_fact();
CREATE TRIGGER immutable_charge BEFORE UPDATE OR DELETE ON request_charge FOR EACH ROW EXECUTE FUNCTION immutable_fact();
CREATE TRIGGER immutable_snapshot BEFORE UPDATE OR DELETE ON booking_snapshot FOR EACH ROW EXECUTE FUNCTION immutable_fact();
CREATE TRIGGER immutable_policy BEFORE UPDATE OR DELETE ON policy_version FOR EACH ROW EXECUTE FUNCTION immutable_fact();

CREATE FUNCTION verify_occupancy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b booking; o booking_occupancy; target uuid;
BEGIN
 target := COALESCE(NEW.booking_id,OLD.booking_id);
 SELECT * INTO b FROM booking WHERE id=target;
 SELECT * INTO o FROM booking_occupancy WHERE booking_id=target;
 IF b.id IS NULL THEN RETURN NULL; END IF;
 IF o.booking_id IS NULL OR o.resource_id<>b.resource_id OR o.occupied<>tstzrange(b.start_at,b.reserved_end,'[)') THEN
   RAISE EXCEPTION 'booking occupancy disagreement' USING ERRCODE='23514';
 END IF;
 IF (b.state IN ('PENDING_CONFIRMATION','CONFIRMED','COMPLETED','NO_SHOW'))<>(o.released_at IS NULL) THEN
   RAISE EXCEPTION 'booking occupancy state disagreement' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER occupancy_consistency AFTER INSERT OR UPDATE ON booking_occupancy DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_occupancy();
CREATE FUNCTION verify_booking_occupancy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o booking_occupancy; snapshot jsonb;
BEGIN
 SELECT * INTO o FROM booking_occupancy WHERE booking_id=NEW.id;
 SELECT document INTO snapshot FROM booking_snapshot WHERE booking_id=NEW.id;
 IF o.booking_id IS NULL OR o.resource_id<>NEW.resource_id OR o.occupied<>tstzrange(NEW.start_at,NEW.reserved_end,'[)') OR
   ((NEW.state IN ('PENDING_CONFIRMATION','CONFIRMED','COMPLETED','NO_SHOW'))<>(o.released_at IS NULL)) OR
   snapshot IS NULL OR (snapshot->'service'->>'durationMinutes')::int<>NEW.session_minutes OR
   (snapshot->'service'->>'id')::uuid<>NEW.service_id THEN
   RAISE EXCEPTION 'booking invariant disagreement' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER booking_consistency AFTER INSERT OR UPDATE ON booking DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_booking_occupancy();
CREATE FUNCTION verify_refund() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE receipt payment_entry; total bigint;
BEGIN
 IF NEW.kind='REFUND' THEN
   SELECT * INTO receipt FROM payment_entry WHERE id=NEW.receipt_id FOR UPDATE;
   SELECT COALESCE(sum(amount_minor),0) INTO total FROM payment_entry WHERE receipt_id=NEW.receipt_id;
   IF receipt.kind<>'RECEIPT' OR receipt.booking_id<>NEW.booking_id OR receipt.currency<>NEW.currency OR total>receipt.amount_minor THEN
     RAISE EXCEPTION 'invalid refund allocation' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_consistency AFTER INSERT ON payment_entry DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_refund();
