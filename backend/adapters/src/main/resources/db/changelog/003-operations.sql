ALTER TABLE notification_job ADD CONSTRAINT one_delivery_per_audience EXCLUDE USING gist(booking_id WITH =,audience WITH =) WHERE(state='LEASED');
CREATE TABLE privacy_request (id uuid PRIMARY KEY,customer_id uuid NOT NULL REFERENCES customer_identity(id),kind varchar(40) NOT NULL,state varchar(40) NOT NULL,reason varchar(500) NOT NULL,created_at timestamptz NOT NULL);
CREATE TABLE privacy_export (id uuid PRIMARY KEY,encrypted_document text NOT NULL,expires_at timestamptz NOT NULL);
